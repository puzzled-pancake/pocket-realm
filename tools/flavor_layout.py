#!/usr/bin/env python3
"""Single per-flavor artifact naming resolver (the naming schema of record).

The schema itself is documented in `docs/FLAVOR-ARTIFACT-NAMING.md`; this
module is its executable form. Rules:

- `resolve(flavor_id)` returns every artifact path a flavor's build/ledger lane
  needs. The vanilla lane resolves to the repo's pre-existing unqualified names
  byte-for-byte (invariant 1: `--flavor vanilla` must be byte-identical), and
  tbc/wotlk resolve to `-{flavor}`-suffixed names. Per docs §4 the vanilla
  migration ledger additionally gets a canonical `-vanilla` byte-copy alias
  pair (G3-3) — see `migration_ledger`.
- `require_flavor_pins(flavor_id, ...)` is the fail-loud pin check every build
  lane runs *before* configure. Two failure classes, both actionable:
    * unregistered pin — the manifest group is `status: "unresolved"` (or the
      checkout directory is absent): the error names the owning port-plan
      stage and the fork to register;
    * pin mismatch — the checkout exists but its HEAD differs from the pinned
      commit (the "Playerbots pin mismatch" error class).

Usage (import, not run):
    from tools import flavor_layout
    layout = flavor_layout.resolve("tbc")
    flavor_layout.require_flavor_pins("tbc", worktree=ROOT)
"""
from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass, field
from pathlib import Path, PureWindowsPath

ROOT = Path(__file__).resolve().parents[1]
FLAVOR_MANIFEST = ROOT / "schemas" / "flavor.json"
FLAVOR_IDS = ("vanilla", "tbc", "wotlk")

# Per-flavor name suffix. Vanilla carries NO suffix: every path below equals
# the pre-parameterization literal for vanilla, which is the byte-identity
# proof surface. (The vanilla migration ledger is the one seal-critical case
# with an explicit canonical/alias pair — G3-3/.)
_SUFFIX = {"vanilla": "", "tbc": "-tbc", "wotlk": "-wotlk"}

# Flavor -> port-plan stage that registers each group's fork/pin.
_PIN_REGISTRATION_STAGE = {
    "server_core": "S2 item 1",
    "db_content": "S4 item 1",
    "bots": "wave-0 fork registration (H3-1/D34)",
}


class FlavorPinError(RuntimeError):
    """A build lane refused to run because a flavor's source pin is not
    registered or does not match its checkout. Fail-loud by design."""


@dataclass(frozen=True)
class FlavorLayout:
    flavor_id: str

    # Source trees (submodule paths, relative to the repo root).
    core_submodule: str
    bots_submodule: str
    db_submodule: str

    # Build/staging roots.
    def build_native_dir(self, triple: str) -> str:
        """build_native.py cmake build dir: native/.build-<triple> for vanilla."""
        return f"native/.build-{triple}{_SUFFIX[self.flavor_id]}"

    def o11_build_root(self, abi: str) -> str:
        """o11 extractor build root: native/.build-o11-{abi} for vanilla."""
        return f"native/.build-o11-{abi}{_SUFFIX[self.flavor_id]}"

    def o09_build_root(self, abi: str) -> str:
        return f"native/.build-o09-{abi}{_SUFFIX[self.flavor_id]}"

    # Lockfiles / ledgers (schemas/ paths, relative to the repo root).
    # Per docs/FLAVOR-ARTIFACT-NAMING.md §2, the flavor segment comes BEFORE
    # the abi segment: <name>-{flavor}[-{abi}].json — so
    # realm-runtime-lockfile-tbc-arm64-v8a.json, not ...-arm64-v8a-tbc.json.
    def realm_runtime_lockfile(self, abi: str) -> str:
        return self._suffixed_name("schemas/realm-runtime-lockfile", abi)

    def sqlite_win_lockfile(self) -> str:
        suffix = _SUFFIX[self.flavor_id]
        if not suffix:
            return "schemas/realm-runtime-lockfile-sqlite-win.json"
        return f"schemas/realm-runtime-lockfile-sqlite-win{suffix}.json"

    def o11_lockfile(self, abi: str) -> str:
        return self._suffixed_name("schemas/o11-extractor-lockfile", abi)

    def _suffixed_name(self, base: str, abi: str) -> str:
        """<base>[-{abi}].json with the flavor segment inserted before the abi
        segment; no suffix (vanilla) returns the historical literal name."""
        suffix = _SUFFIX[self.flavor_id]
        if not suffix:
            return f"{base}.json" if abi == "x86_64" else f"{base}-{abi}.json"
        return f"{base}{suffix}.json" if abi == "x86_64" else f"{base}{suffix}-{abi}.json"

    def migration_ledger(self) -> tuple[str, str | None]:
        """(canonical, alias) per-flavor migration ledger names.

        vanilla: canonical = schemas/database-migrations-vanilla.json (the
        G3-3 additive byte copy), alias = the historical
        schemas/database-migrations.json — byte-identical by CI assertion
        until the wave-S9 alias retirement (F3-8/G3-3; decision D35).
        tbc/wotlk: canonical only."""
        if not _SUFFIX[self.flavor_id]:
            return ("schemas/database-migrations-vanilla.json",
                    "schemas/database-migrations.json")
        return (f"schemas/database-migrations-{self.flavor_id}.json", None)

    def sqlite_seed_baseline(self) -> str:
        if not _SUFFIX[self.flavor_id]:
            return "schemas/sqlite-seed-baseline.json"
        return f"schemas/sqlite-seed-baseline-{self.flavor_id}.json"

    def conf_matrix(self) -> str:
        return f"schemas/conf-matrix-{self.flavor_id}.json"


_VANILLA = FlavorLayout(
    flavor_id="vanilla",
    core_submodule="native/cmangos",
    bots_submodule="native/playerbots",
    db_submodule="native/classic-db",
)
_TBC = FlavorLayout(
    flavor_id="tbc",
    core_submodule="native/cmangos-tbc",
    bots_submodule="native/playerbots-tbc",
    db_submodule="native/classic-db-tbc",
)
_WOTLK = FlavorLayout(
    flavor_id="wotlk",
    core_submodule="native/cmangos-wotlk",
    bots_submodule="native/playerbots-wotlk",
    db_submodule="native/classic-db-wotlk",
)

LAYOUTS = {"vanilla": _VANILLA, "tbc": _TBC, "wotlk": _WOTLK}


def resolve(flavor_id: str) -> FlavorLayout:
    """Resolve a flavor id to its artifact layout; unknown ids are refused
    with the valid set named (fail-closed, never a silent default)."""
    layout = LAYOUTS.get(flavor_id)
    if layout is None:
        raise ValueError(
            f"unknown flavor {flavor_id!r} — valid flavors: {', '.join(FLAVOR_IDS)}"
        )
    return layout


def load_flavor_manifest(path: Path = FLAVOR_MANIFEST) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def db_content_seed_error(flavor_id: str, seed: str,
                          db_submodule: str | None = None) -> str | None:
    """Containment rule for a flavor manifest's `db_content.seed` (review
    round 1, R2-1 — serious): the seed must name the world-content archive
    INSIDE the flavor's own db repo. Every consumer joins the seed onto the
    db repo root (seed_realm_db's `_flavor_seed_plan`,
    stage_database_migrations' `_full_db_seed`), and nothing downstream
    catches a wrong-flavor archive (the floors are MINIMUMS — a
    higher-volume foreign Full_DB clears them — and the version-table leg
    is an honest WARN), so a doctored seed like
    "../classic-db-wotlk/Full_DB/..." (or an absolute / drive path) would
    seed foreign world content under the flavor's DB names with rc=0.

    The rule is data-shaped and enforced here, once, for both consumers:
    the seed is a relative path with no '..' parts and no Windows drive,
    and its join with the flavor's db repo root resolves INSIDE that root.
    Returns None when the seed is contained; an actionable error string
    otherwise. Missing/empty seeds are the caller's policy (the vanilla
    glob fallback / stage's registered-pin refusal), not this check's."""
    layout = resolve(flavor_id)
    db_submodule = db_submodule or layout.db_submodule
    db_root = (ROOT / db_submodule).resolve()
    if Path(seed).is_absolute() or PureWindowsPath(seed).drive:
        return (
            f"flavor {flavor_id!r} db_content.seed {seed!r} is an absolute/"
            f"drive path — it must be relative to its own db repo "
            f"({db_submodule}); a foreign absolute seed would seed "
            "wrong-flavor world content the floor check cannot catch "
            "(floors are minimums)"
        )
    parts = PureWindowsPath(seed.replace("\\", "/")).parts
    if ".." in parts:
        return (
            f"flavor {flavor_id!r} db_content.seed {seed!r} escapes its own "
            f"db repo ({db_submodule}) via '..' — a doctored seed pointing "
            "at another flavor's Full_DB would seed wrong-flavor world "
            "content under this flavor's DB names (the floors are minimums "
            "and cannot catch it); register THIS flavor's own archive"
        )
    joined = (db_root / seed).resolve()
    try:
        joined.relative_to(db_root)
    except ValueError:
        return (
            f"flavor {flavor_id!r} db_content.seed {seed!r} resolves to "
            f"{joined}, outside its own db repo ({db_root}) — a seed that "
            "leaves the flavor's db repo breaks the provenance chain "
            "(sources.json pin -> checkout -> Full_DB archive)"
        )
    return None


def _flavor_block(manifest: dict, flavor_id: str) -> dict:
    for flavor in manifest.get("flavors", []):
        if isinstance(flavor, dict) and flavor.get("id") == flavor_id:
            return flavor
    raise ValueError(f"flavor {flavor_id!r} not found in {FLAVOR_MANIFEST}")


def _submodule_head(worktree: Path, submodule: str) -> str | None:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=str(worktree / submodule),
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip() or None


def _submodule_dirty(worktree: Path, submodule: str) -> bool:
    """True when the submodule working tree carries modifications or
    untracked files (round-2 R2-3): a tampered checkout AT the pinned HEAD
    passed every provenance gate because only HEAD was compared — the
    provenance chain must attest the bytes, not just the commit name."""
    result = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=str(worktree / submodule),
        capture_output=True,
        text=True,
    )
    return result.returncode == 0 and bool(result.stdout.strip())


def require_flavor_pins(
    flavor_id: str,
    manifest: dict | None = None,
    worktree: Path = ROOT,
    groups: tuple[str, ...] = ("server_core", "db_content", "bots"),
) -> dict:
    """Fail loud before configure unless every requested pin group for this
    flavor is registered AND its checkout (when present) matches the pin.

    Returns the resolved pins {group: commit} for the flavor. Raises
    FlavorPinError naming the missing pin, its owning port-plan stage, and the
    fix — the two S1 negative-test failure classes:
      1. unregistered pin: status "unresolved" in flavor.json, or the flavor's
         submodule directory absent;
      2. pin mismatch: checkout HEAD != pinned commit.
    A registered pin whose checkout has not landed yet (the tbc/wotlk bots
    case before the fork registration wave) fails class 1 with the checkout
    path named — building the lane is impossible until it exists.
    """
    manifest = manifest if manifest is not None else load_flavor_manifest()
    layout = resolve(flavor_id)
    block = _flavor_block(manifest, flavor_id)
    submodule_for = {
        "server_core": layout.core_submodule,
        "db_content": layout.db_submodule,
        "bots": layout.bots_submodule,
    }
    pins: dict[str, str] = {}
    for group in groups:
        group_data = block.get(group)
        group_data = group_data if isinstance(group_data, dict) else {}
        commit = group_data.get("commit")
        status = group_data.get("status")
        submodule = submodule_for[group]
        if not isinstance(commit, str) or not commit or status == "unresolved":
            raise FlavorPinError(
                f"flavor {flavor_id!r} {group} pin is unresolved — register the "
                f"fork and commit pin (port plan {_PIN_REGISTRATION_STAGE[group]}) "
                f"before building this lane; sources.json entry expected for "
                f"{submodule}"
            )
        if not (worktree / submodule).exists():
            raise FlavorPinError(
                f"flavor {flavor_id!r} {group} pin {commit[:12]} is registered but "
                f"the checkout is missing at {submodule} — complete "
                f"{_PIN_REGISTRATION_STAGE[group]} (git submodule add/update) "
                "before building this lane"
            )
        actual = _submodule_head(worktree, submodule)
        if actual is None:
            raise FlavorPinError(
                f"flavor {flavor_id!r} {group}: {submodule} is not a git checkout; "
                "cannot verify the pin"
            )
        if actual != commit:
            raise FlavorPinError(
                f"flavor {flavor_id!r} {group} pin mismatch: expected {commit}, "
                f"{submodule} is at {actual} — align the checkout with the pin "
                "(a deliberate wrong-pin build must fail before compilation)"
            )
        if _submodule_dirty(worktree, submodule):
            raise FlavorPinError(
                f"flavor {flavor_id!r} {group}: {submodule} is at the pinned "
                f"commit {commit[:12]} but its working tree is DIRTY — the pin "
                "attests the commit, not the bytes; restore the pristine tree "
                f"(git -C {submodule} checkout -- . && git -C {submodule} "
                "clean -fd) before building this lane"
            )
        pins[group] = commit
    return pins
