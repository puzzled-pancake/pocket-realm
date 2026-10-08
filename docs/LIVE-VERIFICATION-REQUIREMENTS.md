# Live-Verification Requirements Manifest — user-provided artifacts

Compiled 2026-10-02 by the requirement-analyst step of the live-testing workflow authoring run.
Scope: every artifact **the USER must obtain or create** so a future live-testing workflow can run
**all** groups on this Windows host, plus an explicit list of what is **already in the repo** and
must NOT be re-downloaded.

**Sources read** (all paths E: = `E:/pocket_realm_complete` unless noted):

**Relocation 2026-10-08 (W0):** the genuine clients moved from C:\Wow clients\ to E:\Wow clients\ (exe sha256s re-verified against the capture-manifest pins). Procedure rows below now stage from E:. Historical machine-state snapshots and recorded evidence keep their capture-time C: paths verbatim.

| Source | What was taken from it |
|---|---|
| `docs/FEATURE-PARITY-GAPS.md` §0/§3 (E:) | corrections C1/C2/C6/C7 (realmbuilds, MPQ sets, pristine exe MD5s, not-LAA); §3 verification record (puzzled-pancake fork pin caveat) |
| `out/live-verification/campaign-inventory.md` (C: working tree) | "Externally blocked (need genuine clients / devices / forks)" — the gap IDs each lane unblocks (:270-280); MP:TUNNEL-LANE (Tailscale recommended, cloudflared documented, :246); mariadb-lane + llm-lane + multiplayer rows |
| `docs/DESKTOP-PARITY-GAPS.md` (C: copy) | register headings: DESK-4 = gamepad (:490), **DESK-5 = MariaDB engine lane** (:545), DESK-9 = embedded LLM lane (:1119) |
| `schemas/sources.json` (E:) | per-flavor pins: cmangos-core-tbc / cmangos-core-wotlk `status:"unresolved"` with fork-creation status_note; playerbots-tbc/wotlk `registered` @ 7e2cd2fb; llama-cpp-6d05498 entry |
| `schemas/flavor.json` (E:) | tbc/wotlk `qualified:false`, `server_core/db_content fork/commit:null`, realmd accepted_builds `8606`/`12340`, C2 MPQ-set notes, vanilla client sha/size |
| `tools/build_win_realm_runtime.py` (E:) | MariaDB provider staging contract (:445-548: "a PHYSICAL artifact the lane cannot synthesize"; `WIN_PROVIDER_ID="mariadb-11.5.2-windows-x86_64"` :464; staging dir :59) |
| `tools/build_win_llama_server.py` + `native/llm/lockfile-win-x86_64.json` (E:) | llama-server.exe is **built in-repo** from pinned source; toolchain + artifact sha256 |
| `android/app/src/main/java/com/pocketrealm/llm/LlmModelRegistry.kt` + `desktop/.../DesktopLlmModelStaging.kt` (E:) | GGUF model pins; the two tuned models have `url=""` → user-supplied |
| `desktop/.../DesktopMariaDbProvider.kt` (E:) | `STAGED_ROOT_REPOSITORY_PATH = "native/.build-win-x86_64/mariadb-provider"` (:259) |

**Checks run during this ask** (existence/pin checks via shell; external availability via live web fetch, 2026-10-02):

- `ls E:/pocket_realm_complete/native/.build-win-x86_64` → **no** `llama-staging/`, **no** `mariadb-provider/` (both lanes unstaged).
- `ls E:/pocket_realm_complete/schemas` → `realm-runtime-lockfile-sqlite-win.json` **exists** (vanilla Windows runtime lane locked); **no** `realm-runtime-lockfile-mariadb-win.json`, **no** `database-provider-lockfile-win.json` (MariaDB lane never staged/built).
- `ls E:/pocket_realm_complete/native/.providers/llama-cpp-6d05498…` → pinned tarball cached (36,865,467 B); source tree present at `native/.deps/src/llama/6d05498…/`.
- `git -C native/cmangos log -1` → `ce83805d48f9c98b2af617096be5347acb8a1f17` (= sources.json pin); `git -C native/playerbots log -1` → `7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d` (= pin).
- `find %LOCALAPPDATA%/PocketRealm/content/client/generations` → **empty**; `ls "C:/Wow clients"` → absent; bounded `find` for `Wow.exe` under `C:/Users/<owner>` (depth 4) and `E:/` (depth 3) → no hits. No WoW client of any flavor is staged on this machine.
- `find … -iname "*.gguf"` over E:/repo/tmp + Downloads/Documents → only llama.cpp vocab-test ggufs; **no production chat model on disk**.
- Live web: `api.github.com/users/puzzled-pancake/repos` → repos include `mangos-classic`, `playerbots`, `pocket-realm`; **`mangos-tbc` and `mangos-wotlk` DO NOT exist**. `anzz1/wow-client-checksums` → 8606 MD5 `57c5c03097103e15f9abe2803aebdc3c`; 12340 MD5 `45892bdedd0ad70aed4ccd22d9fb5984` (identical across locales). `archive.mariadb.org/mariadb-11.5.2/winx64-packages/` → `mariadb-11.5.2-winx64.zip` (87 MB) + `sha256sums.txt` + `.asc`. `cloudflare/cloudflared/releases` → latest 2026.9.3, asset `cloudflared-windows-amd64.exe` (sha256 `f096265ec2fcbe9bb6e2d64268db167ced3fcbb83d894bdb9e2fcdb26f2ea7e2`). `tailscale.com/download` → official Windows client page.
- No builds/tests were executed: this ask is a requirements compile.

---

## 1. Test groups assumed

The ask did not enumerate the workflow's groups; these are derived from the campaign record's
blocked lanes (`campaign-inventory.md:270-280`) and the DESK lanes they exercise:

| # | Group | Live behavior |
|---|---|---|
| G1 | Client qualification & import (w5 lane) | import 8606/12340 folders/archives; VAL gates, MPQ exact-set, exe hash cross-check |
| G2 | TBC server boot & play | qualified TBC client vs forked mangos-tbc boot (S2-lane), realmbuilds 8606 auth |
| G3 | WotLK server boot & play | qualified 12340 client vs forked mangos-wotlk boot, realmbuilds 12340 |
| G4 | Vanilla regression live | 5875 client + in-repo vanilla lane (must not regress; G3-x guards) |
| G5 | MariaDB provider lane (DESK-5/47/78/106) | dual-provider switch, mariadbd.exe lifecycle, migration ledger, ban-op pragma fix (finding 5.34) |
| G6 | Embedded LLM lane (DESK-9/28) | llama-server.exe sidecar + staged GGUF + world chatter |
| G7 | LAN multiplayer | LAN_HOST/join, guest bans, provisioning |
| G8 | Tunnel lane (optional) | Tailscale/cloudflared advertise + friend join (MP:TUNNEL-LANE) |

Groups the artifacts below can **never** unblock on this host (need hardware/people, per
`campaign-inventory.md:277-280`): RP6 physical-device measurements (w6), device-matrix soaks +
`J2-7` conformance soak, Android instrumented lane (`TF-9`, G3 device pins), `W2-4` N≥2-human LAN
soak, real two-machine tunnel join.

> **Scope annotation (added 2026-10-02, audit round 1 — recorded so the ruling travels with the
> manifest):** the "Android instrumented lane" exclusion above refers to the *physical-device*
> instrumented lane (RP6). **Emulator-instrumented rows ARE in scope for the unattended lane**:
> the governing environment decision rules "Emulator-only is the sanctioned default" — the
> fail-closed serial gate (android/app/build.gradle.kts:105-134) refuses non-emulator serials and
> passes emulators, and the headless PocketDiff_x86_64 AVD is the chosen lane
> (environment.md:105-122). The `am instrument` / WorldConsoleRelay rows in the live-verification
> matrices run against that emulator only; RP6 remains excluded. Basis: environment.md's recorded
> decision plus audit round 1's directive; no human owner signed a new ruling beyond those.

---

## 2. REQUIRED artifacts (user must obtain / create)

### R1. Genuine TBC 2.4.3 **enUS** client — build **8606**

- **Why:** the tbc flavor is `qualified:false` and every tbc live group starts from w5
  qualification: pristine-MD5 cross-check + SHA-256 registration (C6), exact-set MPQ
  qualification (C2; unknown `patch-*.mpq` = VAL-04), locale observation, login/char-select
  captures, TOS/EULA run, render/sound QA. Unblocks: **G1, G2** (and every tbc-dependent leg).
  Blocked gap IDs carried by the campaign for this artifact: `G2-5, H2R2-3, H2-8, P1-5, H5R2-8,
  H4R2-1, H4R2-11, H4-10, H4R2-9, G2-4, H3R2-7, H2-13, H2-14, H4-9, J2/J2-7 (capture groundwork)`
  (`campaign-inventory.md:276`).
- **Where to get:** Blizzard no longer distributes 2.4.3; use the user's own install media or a
  community archive of the original enUS 2.4.3 installer. **Provenance does not matter — bytes
  do:** the import is accepted only if `WoW.exe` matches pristine MD5 `57c5c030…`
  (`57c5c03097103e15f9abe2803aebdc3c` per anzz1/wow-client-checksums, verified live 2026-10-02).
- **Form:** unpacked folder, `.zip/.7z/.rar`, or Inno Setup installer (import lanes DESK-2/35/64/65).
  Must be **unmodified**: no LAA/d3d9ex/repack exes (H2R2-3 negative vectors), no
  Repair.exe/Launcher.exe in the managed copy (G2-6). Realmlist target build: `8606`
  (`realmbuilds`, flavor.json). Expected MPQ root set (C2): `common, expansion, patch, patch-2` +
  `Data\enUS\` locale/speech set.
- **Storage:** plan for ~25 GiB class budget (register P3-2 TBC row).

### R2. Genuine WotLK 3.3.5a **enUS** client — build **12340**

- **Why:** same as R1 for the wotlk flavor; additionally the memory-envelope facts (C7: not
  large-address-aware, 2 GB VA) get their live datapoint only from this client. Unblocks: **G1,
  G3**. Same campaign gap-ID list as R1.
- **Where to get:** user's own media / community archive of the original enUS 3.3.5a installer;
  verify `WoW.exe` MD5 `45892bde…` (`45892bdedd0ad70aed4ccd22d9fb5984`; identical across all
  locales per anzz1/wow-client-checksums) — exe ~12.6 MB (C6).
- **Form:** as R1. Expected MPQ root set (C2): `common, common-2, expansion, lichking, patch,
  patch-2, patch-3` (**no patch-4**) + `Data\enUS\` set; tolerated auxiliaries `base-enUS`,
  `backup-enUS`. Realmbuilds: `12340`.
- **Storage:** ~35 GiB class budget (P3-2 WotLK row); extracted tree ~16-17 GB.

### R3. Vanilla 1.12.1 client — build **5875** (likely already owned; NOT currently staged here)

- **Why:** group **G4** (vanilla regression live) and every vanilla control run needs the user's
  1.12.1.5875 client — it is user-supplied by design (`flavor.json`: "user-supplied; PE/build fast
  scan … full managed import") and never bundled (licensing; `sources.json` cmangos-db
  redistribution note: client-derived data comes only from the user's managed import).
- **Status on this machine:** **absent** — `%LOCALAPPDATA%/PocketRealm/content/client/generations`
  is empty, `C:\Wow clients` staging folder doesn't exist, bounded Wow.exe scan negative (checks
  listed in the header). If the user has the archive from earlier vanilla live testing, place it in
  the staging folder; otherwise obtain it as for R1/R2.
- **Gate values:** exe size 4,775,986 B, SHA-256 `b4756d38ef207c02ed651f4952bd89a70b4857b73a33413339e1b285b28d2dc7`
  (`flavor.json` vanilla `wine.known_exe_sha256`; `tools/install_client_windows.ps1`
  `EXPECTED_EXE_BYTES`). Sourcing helper: `tools/install_client_windows.ps1 -StagingRoot "E:\Wow clients"`.
- **Unblocks:** G4 only.

### R4. GitHub forks — `puzzled-pancake/mangos-tbc` + `puzzled-pancake/mangos-wotlk`, branch `pocket-realm`

- **Why:** the tbc/wotlk server cores cannot be fetched, pinned, or built until the forks exist.
  `schemas/sources.json` `cmangos-core-tbc`/`cmangos-core-wotlk`: `status:"unresolved"`, commit
  `null`, status_note — *"the fork (puzzled-pancake/mangos-tbc) and its commit pin land with the
  wave-0 fork registration; tools/check_sources.py reports PENDING (expected-loud, non-fatal) until
  then, and building the tbc lane fails loud naming this entry."* Campaign records the same:
  forks are externally blocked — `C4` (fork-creation half), `H3-1` (registration half)
  (`campaign-inventory.md:275`).
- **Verified 2026-10-02 (live):** `puzzled-pancake` has `mangos-classic` and `playerbots` forks but
  **no `mangos-tbc`, no `mangos-wotlk`**.
- **What the user does:** fork `github.com/cmangos/mangos-tbc` and `github.com/cmangos/mangos-wotlk`
  under `puzzled-pancake`; create branch `pocket-realm` in each; land the port-plan S2 adaptation
  commits; register the resulting commit pins in `schemas/sources.json` + `schemas/flavor.json`
  (wave-0 fork registration). Note: **`tbc-db`/`wotlk-db` are NOT forks** — they are pinned from
  upstream `cmangos/tbc-db` / `cmangos/wotlk-db` at S4 (sources.json `branch:"master"`, commit pin
  is an S4 decision); no user artifact there.
- **Unblocks:** **G2, G3** (and tbc/wotlk qualification legs that need a running server).

### R5. MariaDB 11.5.2 Windows x86_64 distribution (DESK-5/47/78 lane)

> Label correction: the ask called this the "DESK-4 lane"; DESK-4 is gamepad/controller
> (`DESKTOP-PARITY-GAPS.md:490`). The MariaDB engine lane is **DESK-5** (:545), with DESK-47/78/106
> as the provider/dual-provider rows.

- **Why:** the Windows provider distribution is *"a PHYSICAL artifact the lane cannot synthesize"*
  — the build fails loud rather than fabricating a server lane (`tools/build_win_realm_runtime.py`
  :445-466). The desktop engine spawns real `mariadbd.exe`/`mariadb.exe` (`DesktopMariaDbEngine.kt`
  :45-58) and resolves staging at `native/.build-win-x86_64/mariadb-provider/`
  (`DesktopMariaDbProvider.kt:259`). Today that directory **does not exist**, and neither
  `realm-runtime-lockfile-mariadb-win.json` nor `database-provider-lockfile-win.json` exists — the
  lane has never been staged. Unblocks: **G5** (and the MariaDB marker leg of timeframe overlays).
- **Where to get (official, verified live):** `https://archive.mariadb.org/mariadb-11.5.2/winx64-packages/`
  — `mariadb-11.5.2-winx64.zip` (87 MB; `.msi` also available), with `sha256sums.txt` and GPG
  `.asc` signatures. (11.5.2 is the exact provider id the lane pins:
  `WIN_PROVIDER_ID = "mariadb-11.5.2-windows-x86_64"`, `build_win_realm_runtime.py:464`.)
- **What to stage** (per `verify_mariadb_provider_staging`, `build_win_realm_runtime.py:468-548`):
  into `native/.build-win-x86_64/mariadb-provider/` — `bin/mariadbd.exe`, `bin/mariadb.exe`,
  `bootstrap.sql`, `migration-manifest.json` (with an `entries` array), and `runtime-manifest.json`
  (schema 1, `provider:"mariadb-11.5.2-windows-x86_64"`, `abi:"windows-x86_64"`, `bootstrap_sha256`,
  `executables{mariadbd,mariadb}` each with matching `sha256`/`size`/staging-relative `name`). Then
  `build_win_realm_runtime.py --database-backend mariadb` verifies fail-loud, and
  `--write-lockfile` records `schemas/database-provider-lockfile-win.json` +
  `realm-runtime-lockfile-mariadb-win.json`.

### R6. Tuned GGUF chat models ×2 (DESK-9/28 LLM lane)

- **Why:** the registry pins the product's tuned models with **empty URLs** — the app cannot
  download them (`LlmModelRegistry.kt`: `gemma4-E2B-TUNED-q4_0.gguf` `url=""` :80-83, sha256
  `e267e9793bac7db4340103b840bce8b52412ec3545827160b449119379853665`; `qwen35-08b-CLEAN-tuned-q4_0.gguf`
  `url=""` :112-115, sha256 `d87581a7ddd118f3193073226748881ea5fcbf48af40676e50071574b5e1f7aa`).
  No `.gguf` chat model exists on this machine (checked). Unblocks: **G6**.
- **Where to get:** the user's own training outputs (the project's tuned GGUFs — arm1b/epoch-3
  Qwen, tuned Gemma per registry comments). Place each file at
  `%LOCALAPPDATA%\PocketRealm\runtime\llm\models\<fileName>`; the lane accepts a pre-placed copy
  only after sha256 verification (`DesktopLlmModelStaging.kt:73-79`).
- **Not needed:** the third registry entry `gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf` self-downloads from
  `huggingface.co/unsloth/gemma-4-E2B-it-qat-GGUF` (registry :141-147) — needs network on first
  download, no user action.

### R7. Tunnel binaries — OPTIONAL (group G8)

- **Why:** MP:TUNNEL-LANE verifies the advertise/join lane over a real tunnel; the design
  recommends **Tailscale/ZeroTier** and documents **cloudflared** (`campaign-inventory.md:246`).
  The app never spawns these binaries — they appear only as copy/recipes
  (`DesktopRuntimeBackend.kt:514-678`) — so they must be installed at OS level by the user.
- **Where to get (verified live):** Tailscale — `https://tailscale.com/download` (Windows client);
  cloudflared — `https://github.com/cloudflare/cloudflared/releases`, asset
  `cloudflared-windows-amd64.exe` (2026.9.3 sha256 `f096265ec2fcbe9bb6e2d64268db167ced3fcbb83d894bdb9e2fcdb26f2ea7e2`).
  ZeroTier — `https://www.zerotier.com/download/` (alternative; not fetched this session).
- **Caveat:** a *meaningful* tunnel test also needs a second peer (second machine/account) — that
  is a harness constraint (§1), not satisfied by the binary alone. Skip R7 if G8 stays out of scope;
  G7 (plain LAN) needs none of these.

---

## 3. NOT needed — already in the repo (do not download)

| Item | Evidence it is already in-repo |
|---|---|
| **llama.cpp source / "pinned llama.cpp build"** (ask candidate) | Pin is the SOURCE, not a binary: tarball cached `native/.providers/llama-cpp-6d05498314….tar.gz` (36,865,467 B) + staged tree `native/.deps/src/llama/6d05498…/`; `sources.json` entry `llama-cpp-6d05498`. The exe is **built in-repo** by `tools/build_win_llama_server.py` (CPU-only, MSVC or WinLibs MinGW) and sha-pinned in `native/llm/lockfile-win-x86_64.json` (expected `llama-server.exe` 29,898,240 B, sha256 `850697f8…`). The exe is **not yet built** on E: (`llama-staging/` absent) — that is a build action, not a user download. |
| **playerbots** (all flavors, one pin) | `puzzled-pancake/playerbots` fork exists (verified live); submodule `native/playerbots` checked out at exactly `7e2cd2fb…` (git log run this ask); tbc/wotlk entries `registered` in sources.json — only the submodule *checkouts* remain, which land with R4's registration, not a download. |
| **Vanilla core + DB** | `native/cmangos` @ `ce83805d…` (git log run this ask; = sources.json/flavor.json pin), `native/classic-db` @ `be1a5206…` per sources.json. Windows vanilla runtime lane already built/locked: `schemas/realm-runtime-lockfile-sqlite-win.json` exists. |
| **tbc-db / wotlk-db content repos** | Fetched by the build when the S4 commit pins land; `sources.json` `cmangos-db-tbc/wotlk` pin from upstream `cmangos/*-db` — no fork, no user artifact. |
| **Android/glibc MariaDB runtimes** | Staged with lockfiles: `schemas/mariadb-runtime-lockfile.json` (x86_64 glibc 11.5.2), `…arm64-v8a.json` (Termux bionic 12.3.2); trees under `native/.build-arm64/mariadb-arm/`, `tools/stage_mariadb_*.py`. These are for Android, not the Windows desktop lane. |
| **Checksum corpus** | `anzz1/wow-client-checksums` is a read-only reference; values are transcribed into `flavor.json` at qualification — *"no pin required"* (`FEATURE-PARITY-FIX-PLAN.md:1434`). |
| **vanilla-tweaks, extractors, sqlite seam, wine tooling, vcpkg deps** | `schemas/vanilla-tweaks-lockfile-win.json` + `realm-runtime-lockfile-sqlite-win.json` exist; `native/win-deps/vcpkg_installed` present; extractor/o11 lockfiles staged (`PORT-D36`). |
| **The WoW clients' checksum values** | Already recorded as prefixes in `flavor.json` (`57c5c030…`, `45892bde…`) — only the R1/R2 full hashes are computed at qualification. |

---

## 4. Group × artifact matrix

| Group | R1 8606 | R2 12340 | R3 5875 | R4 forks | R5 MariaDB | R6 GGUF | R7 tunnel |
|---|---|---|---|---|---|---|---|
| G1 client qualification | **✔** | **✔** | ✔ | — | — | — | — |
| G2 TBC boot | **✔** | — | — | **✔** | optional¹ | — | — |
| G3 WotLK boot | — | **✔** | — | **✔** | optional¹ | — | — |
| G4 vanilla regression | — | — | **✔** | — | optional¹ | — | — |
| G5 MariaDB lane | — | — | — | — | **✔** | — | — |
| G6 embedded LLM | — | — | — | — | — | **✔** | — |
| G7 LAN multiplayer | — | — | ✔ (any client) | — | optional¹ | — | — |
| G8 tunnel (optional) | — | — | ✔ | — | — | — | **✔** + 2nd peer |

¹ tbc/wotlk/vanilla boots run on the default SQLite backend; the MariaDB backend is its own lane.

---

## 5. Honesty notes

- No build, import, or test gate was executed for this manifest; every "absent/present" claim is an
  `ls`/`find`/`git log` result run this ask (commands in the header), and every external-URL claim
  was fetched live on 2026-10-02.
- The workflow's group list (§1) was **derived** from the campaign record; the ask did not define
  it. If the workflow's groups differ, re-map §4 accordingly — the artifact set itself is pinned by
  the lane code and docs cited per row.
- Client "where to get" for R1/R2/R3 cannot name an official source (Blizzard distributes none of
  these builds); the acceptance gate is the pristine-MD5 cross-check, so any source whose bytes
  match qualifies. The community-checksum reference is `anzz1/wow-client-checksums` (verified live;
  matches register C6 exactly).
- Hardware/people-dependent groups (RP6 device, device matrix, Android instrumented lane, N≥2-human
  soaks, true two-machine tunnel join) are **out of artifact scope** — no download unblocks them
  (`campaign-inventory.md:277-280`).
