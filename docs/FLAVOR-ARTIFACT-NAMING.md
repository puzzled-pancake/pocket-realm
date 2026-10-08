# Per-Flavor Artifact Naming Schema (S0 item 3 / S1 items 5–8, F3-1 design)

**Status:** design of record for wave S1 (2026-09-30). The single resolver is
`tools/flavor_layout.py`; everything below is what it returns, why the names
look the way they do, and how the unqualified-name aliases retire.

**Principles** (port plan §8.1 risk 8 — "ledger/lockfile combinatorial drift"):
one resolver, no hand-edited names; the vanilla lane keeps its *existing*
unqualified names byte-for-byte (invariant 1) with per-flavor canonical names
added additively; CI divergence checks compare every flavor's pin set, never a
hand-maintained table.

## 1. Flavor ids

`vanilla` · `tbc` · `wotlk` — exactly `schemas/flavor.json`'s `flavors[].id`.

## 2. Naming schema

| Artifact | vanilla (existing name = alias) | per-flavor canonical |
|---|---|---|
| Android realm-runtime lockfile | `schemas/realm-runtime-lockfile.json` / `…-{abi}.json` | `schemas/realm-runtime-lockfile-{flavor}[-{abi}].json` |
| Win SQLite-seam lockfile | `schemas/realm-runtime-lockfile-sqlite-win.json` | `schemas/realm-runtime-lockfile-sqlite-win-{flavor}.json` |
| o11 extractor lockfile | `schemas/o11-extractor-lockfile.json` / `…-{abi}.json` | `schemas/o11-extractor-lockfile-{flavor}[-{abi}].json` |
| o11 build/staging root | `native/.build-o11-{abi}` | `native/.build-o11-{abi}-{flavor}` |
| o09 build root | `native/.build-o09-{abi}` | `native/.build-o09-{abi}-{flavor}` |
| build_native deps prefix / build dir | `native/.deps/prefix-{triple}` / `native/.build-{triple}` | same + `-{flavor}` suffix on the build dir only (deps prefixes are flavor-agnostic: same NDK deps) |
| Migration ledger (F3-1) | `schemas/database-migrations.json` (alias) | `schemas/database-migrations-{flavor}.json` |
| Packaged ledger asset | `database/migrations/manifest.json` (vanilla asset name, never renamed — the datadir seal binds these *bytes*, G3-3) | `database/migrations/{flavor}/manifest.json` + per-flavor `.sqlz` sets |
| SQLite seed baseline | `schemas/sqlite-seed-baseline.json` (alias) | `schemas/sqlite-seed-baseline-{flavor}.json` |
| Conf matrix (H1-17) | — | `schemas/conf-matrix-{flavor}.json` |
| APK `.so` names (D4) | `libpocket_ad.so` … | `libpocket_ad_{flavor}.so` … (selected at S6; naming per flavor is fail-closed) |
| DB names | `classic*` (kept, D1) | `tbc*` / `wotlk*` |
| Wine prefix segment | `wow5875` | `wow8606` / `wow12340` |


vanilla's canonical name is **scheme-specific** (round-3 R6-2 correction):
`schemas/flavor_layout.py` resolves vanilla to the UNSUFFIXED historical
literal for the lockfile families (`realm-runtime-lockfile-sqlite-win.json`,
`o11-extractor-lockfile.json` — no `…-vanilla` twins exist) and to the
suffixed form only where the migration ledger renamed it
(`database-migrations-vanilla.json`, with the unsuffixed name kept as the
byte-identical alias per §4). Where a table above leaves the vanilla cell
"existing name", the resolver returns that exact path for `vanilla` so
pre-S1 tooling bytes are untouched.

## 3. F3-1 ledger schema v2 (design; executed at S4)

Per-flavor ledger `schemas/database-migrations-{flavor}.json` — v2 adds, on top
of the v1 fields (`schema`, `app_build_id`, `ordering`, `source_commits`,
`expected_revisions`, `entries`):

- `"schema": 2`, `"flavorId"` — must equal the flavor's id; a v2 ledger whose
  `flavorId` disagrees with the session flavor fails closed
  (`DB-REVISION: ledger flavor mismatch`).
- `"databaseNames": {realm, characters, logs, world}` — values must match
  `ClientFlavor.DatabaseNames` for the flavor (cross-checked by the engine and
  by `tools/check_flavor.py` data).
- `"probe"` — the revision-probe check tuples (P2-3 item 1) so the engine has
  zero per-flavor literals.
- `"revisionFormat"` — one of `classic-zrev` / `tbc-srev` / `wotlk-numeric`
  (exact enum set confirmed by the fork verification in P2-3 item 4).
- `"source_commits"` keys become per-flavor (`cmangos-tbc`, `classic-db-tbc`,
  `playerbots-tbc`, …) and every value is validated against
  `schemas/sources.json`'s per-flavor entries (the divergence CI of S1 item 3).

The v1 single file stays schema 1 and is the vanilla lane's alias; the engine
accepts v1 (legacy vanilla) and v2 (split) manifests, enforcing the schema it
claims. ACID provenance lands in `source_commits` as `[ACID]` commit ids +
digests pulled from the flavor DB repo's history (correction C5), validated by
`tools/check_sources.py`.

## 4. Alias / deprecation windows (S1 item 8 — opened here, closed at wave S9)

- **Window policy.** Every unqualified (vanilla-era) name stays on disk as a
  byte-identical alias of its canonical per-flavor twin for the whole migration
  window. Consumers migrate one module at a time; CI
  (`tests/test_s1_flavor_parameterization.py::test_ledger_alias_is_byte_identical_to_canonical`,
  the migration-ledger pair) asserts alias == canonical byte-for-byte so no
  drift can hide behind that rename; the lockfile families keep their
  unsuffixed vanilla names outright (no alias pair exists for them). The window **closes at roadmap wave S9** (F3-8's retirement part per
  G3-3 item 2, vanilla-regression-guards.md:66): alias *files* are deleted,
  `tools/leak_sweep.py` allowlist entries retire with them, and a Decision Log
  entry records the rotation. An indefinitely-live alias is exactly what the
  sweep's `retires` model forbids.
- **Wave S1 exit precondition** (not the deletion itself): *no consumer reads
  unqualified names* — the migration precondition, verified by grep + the
  resolver's consumer list. The alias artifact deletion is S9-owned.
- **Special case — the vanilla migration ledger.** The alias
  (`schemas/database-migrations.json`, 208,495 bytes, sha256
  `3771fc35b4ceddbadd6f786d76b10902db319d07c7d6372156417e4e3d81dc68`, 414
  entries, schema 1) is seal-critical: `DatabaseMigrationSeal` binds the
  *packaged asset bytes* (`database/migrations/manifest.json`), never the
  schema filename, so deployed vanilla devices survive both the alias and its
  eventual deletion. The canonical `schemas/database-migrations-vanilla.json`
  is created as a byte copy; G3-3's golden gate
  (`tools/check_ledger_golden.py`, wave 0) pins the sha forever after.
- **`--flavor vanilla` byte-identity proof.** For every renamed artifact the
  vanilla resolution is the *old literal path*; the S1 negative tests pin the
  exact resolution strings, and the full-build lockfile-diff proof
  (port plan S1 end state) is the acceptance gate.

## 5. Resolver contract (`tools/flavor_layout.py`)

- `resolve(flavor_id)` → dataclass with every path above; unknown id →
  `ValueError` naming the valid ids.
- `require_flavor_pins(flavor_id, manifest)` → reads `schemas/flavor.json` and
  raises `FlavorPinError` with an actionable message when a group the lane
  needs is `status: "unresolved"` (unregistered-pin class: names the owning
  port-plan stage and the fork to register) or pinned-but-mismatched vs the
  working tree (pin-mismatch class: names expected vs actual). Build lanes call
  this *before* configure.

Not in the resolver: the o09 overlay split accessor
`overlays_for(flavor_id, backend)` lives where the overlay table lives —
`tools/build_o09_realm_runtime.py` (expansion-neutral overlay list + the
flavor's own overlay definitions; a flavor with no registered per-flavor
overlay set → loud failure naming the S2 re-derivation work item). The
resolver only carries naming and pin verification.
