# Choosing a Vulkan Driver

Pocket Realm translates the Windows game client through Box64 and DXVK, and
DXVK needs a Vulkan driver to talk to your GPU. The Settings → "Vulkan
driver" section controls which one is used on ARM64 devices (the Adreno GPU
lane this project is built around).

## The three lanes

- **Auto (recommended)** follows the GPU vendor like Winlator: Adreno GPUs
  get the packaged Mesa Turnip driver, every other GPU gets the system
  Vortek bridge over Android's own Vulkan driver.
- **System / Turnip (packaged)** are pinned drivers that ship inside the
  app. They are byte-pinned and attested at launch; Turnip 26.1.0 is the
  qualified production default for the Retroid Pocket 6 / Adreno 740 lane.
- **Imported drivers** are your own Mesa Turnip builds, imported from a
  `.so` file or a `.zip` archive. This is the user lane: experimental, never
  chosen automatically, and turned off by default (Settings → "Allow
  imported drivers").

## Where Turnip builds come from

The packaged driver and the user lane both run Mesa Turnip, the freedreno
Vulkan driver. Fresh builds are published by Mesa's GitLab CI as artifacts
of the `mesa` project — look for `debian/arm64` build artifacts or the
community turnip builds derived from them. You need the aarch64
`libvulkan_freedreno.so` (and optionally its ICD manifest JSON).

Requirements checked at import — a build that fails any of these is
rejected with the exact reason shown in Settings:

1. **ELF64 aarch64** — the library must target ARM64 (`EM_AARCH64`).
2. **16 KB page alignment** — every loadable segment must be aligned for
   16 KB pages. Android 15+ kernels (including the RP6's) load 16 KB-page
   libraries and a 4 KB-aligned build crashes on load. Most CI builds are
   fine; if yours is rejected, rebuild with
   `-Wl,-z,max-page-size=0x4000`.
3. **One driver per import** — the archive must contain exactly one `.so`,
   at most one ICD JSON manifest, and at most one AdrenoTools `meta.json`
   (the pack format Eden-class emulators distribute; its name/version become
   the driver label and Vulkan version). Anything else (readmes, extra
   libraries) is rejected.
4. **Size cap** — 256 MiB.

A Vulkan version below 1.3 is only a warning: DXVK 2.4.1 needs Vulkan 1.3,
so pair an older build with the DXVK 1.10.3 compatibility package or expect
DXVK to fail to initialize.

### Known-good community builds

Since 0.102.0-alpha the same import path also accepts the AdrenoTools pack
format (one `.so` + `meta.json`), and Settings → "Community drivers…" offers
a small reviewed list of pinned downloads: the size and SHA-256 recorded in
`schemas/community-vulkan-drivers.json` are verified before anything is
imported, the download only ever resolves through GitHub release hosts, and
the ordinary import rules plus the crash guard still apply — a community
download is just an import with fewer steps. The list changes only through
repo review (it is compiled into the app, never fetched).

Builds verified against the import rules when this list was seeded:

| Build | Source | Format | SHA-256 | Notes |
|---|---|---|---|---|
| Turnip 26.0.0 R8 | K11MCH1/AdrenoToolsDrivers `v26.0.0-rc08` | adrenotools zip | `e634db0f929e2205e95511c769071817d0390180ec72c8e690bc76375e813715` | reports Vulkan 1.4.335; ships "unsupported gpu hacks" per its release notes |
| Turnip 25.1.0 R2 | K11MCH1/WinlatorTurnipDrivers `winlator_v25.1_r2` | bare `.so` | `fe222ea204d5ac312eae2955da4a7b78c087009f28403edceec73e4ed1ae64da` | conservative fallback that predates the pack format |

Not accepted, by policy: Qualcomm-extracted vendor blobs (not Mesa, murky
licensing) and Winlator `.tzst`/`.wcp` component packages (wrong container —
extract the `.so` first). If an upstream asset is replaced after review, the
digest check fails with the exact reason and nothing is imported.

## Adreno only

Turnip drives Adreno GPUs. On any other GPU (Mali, PowerVR, Xclipse…) the
imported-driver rows are disabled with that reason and the system Vortek
bridge is the automatic choice.

## Crash quarantine

Imported drivers run inside the client process, so a bad build can kill the
session before the game appears. The crash guard counts consecutive early
deaths (sessions that fail within 10 seconds): after two in a row the
driver is quarantined, the selection resets to Auto, and the Settings row
explains "quarantined after 2 early crashes". Quarantined drivers stay
listed and can be deleted, but cannot be selected; the packaged lanes are
qualified and never quarantined. Deleting a driver removes it from app
storage — the packaged drivers are app assets and are never touched.

## Reporting results

If you test a Turnip build that works (or explodes), the Diagnostics screen
can export a consent-triggered support bundle that includes the imported-
driver registry and the last user-driver session record (driver identity,
digest, the Vulkan environment variables that were injected, uptime, and
any quarantine event) — no absolute paths or file contents are included.
Include that bundle when reporting.
