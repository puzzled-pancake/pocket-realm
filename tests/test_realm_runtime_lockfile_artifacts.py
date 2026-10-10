"""Committed realm-runtime lockfiles must pin REAL, FIXED bytes
(R2-2/R2-3, native-server round 2).

R2-2: all four committed android-lane lockfiles pinned .so hashes that
existed NOWHERE on disk while the staged jniLibs trees the APK packages
still carried the pre-fix roster payload — committed provenance
self-inconsistent (the finding-5.24 "lockfile records inputs that were
never built" class, CROSS-PLATFORM-COMPLETION-LEDGER.md:430). Every
committed lockfile artifact hash must resolve to a byte-identical file at
its recorded path.

R2-3: the tbc/wotlk win lockfiles bound CURRENT patch-source hashes to
PRE-fix DLLs — binaries that still emitted the lastLogonIp roster member
(plan §1 rule 7 payload class) and still ignored POCKET_REALM_DATA_ROOT
(the crash trace landed on %LOCALAPPDATA% — the C:-write class). The
LV-server-db-36 byte-presence pattern applied to lockfile-PINNED
artifacts: `lastLogonIp` ABSENT and `POCKET_REALM_DATA_ROOT` PRESENT in
every pinned pocket_{world,realmd}_runtime binary (the exception shim is
the win-lane tracer, so the override rides the .dll lanes; the android
.so lanes carry the roster fix only).
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCKFILES = sorted((ROOT / "schemas").glob("realm-runtime-lockfile*.json"))
RUNTIME_BINARY_RE = re.compile(r"pocket_(world|realmd)_runtime\.(so|dll)$")


def _lockfile_records():
    for path in LOCKFILES:
        yield path, json.loads(path.read_text(encoding="utf-8"))


def test_every_committed_lockfile_artifact_resolves_on_disk():
    """R2-2: a pin no build on disk can satisfy is the provenance-that-rot
    class — the committed lockfile must describe bytes that exist."""
    drift: list[str] = []
    checked = 0
    for path, record in _lockfile_records():
        for artifact in record.get("artifacts", []):
            rel = artifact.get("path")
            pinned = artifact.get("sha256")
            if not rel or not pinned:
                continue
            file = ROOT / rel
            checked += 1
            if not file.is_file():
                drift.append(f"{path.name}: {rel} missing on disk")
                continue
            actual = hashlib.sha256(file.read_bytes()).hexdigest()
            if actual != pinned:
                drift.append(
                    f"{path.name}: {rel} hashes {actual[:12]} != pinned {pinned[:12]}")
    assert checked >= 8, (
        f"expected the committed lanes' artifacts, scanned only {checked}")
    assert not drift, (
        "committed lockfile pins that no build on disk satisfies — rerun the "
        "lane builds (tools/build_o09_realm_runtime.py / "
        "tools/build_win_realm_runtime.py, matching flavor/backend) so the "
        "committed pins describe real bytes:\n  " + "\n  ".join(drift))


def test_pinned_runtime_binaries_carry_the_roster_and_root_fixes():
    """R2-3: a lockfile whose SOURCE pins are current but whose ARTIFACT
    pins describe pre-fix binaries ships the exact classes the fixes
    closed. Byte-presence on every pinned pocket_{world,realmd}_runtime
    binary that exists on disk."""
    failures: list[str] = []
    scanned = 0
    for path, record in _lockfile_records():
        for artifact in record.get("artifacts", []):
            rel = artifact.get("path") or ""
            if not RUNTIME_BINARY_RE.search(rel):
                continue
            file = ROOT / rel
            if not file.is_file():
                continue  # resolvability is the other test's finding
            scanned += 1
            data = file.read_bytes()
            if b"lastLogonIp" in data:
                failures.append(
                    f"{path.name}: {rel.name} still emits the lastLogonIp "
                    "roster member (plan §1 rule 7)")
            if rel.endswith(".dll") and b"POCKET_REALM_DATA_ROOT" not in data:
                failures.append(
                    f"{path.name}: {rel.name} ignores POCKET_REALM_DATA_ROOT "
                    "(the crash tracer strands the trace on C:)")
    assert scanned >= 12, (
        f"expected the runtime binaries of all committed lanes on disk, "
        f"scanned {scanned}")
    assert not failures, (
        "lockfile-pinned binaries predate this slice's fixes — rebuild the "
        "owning lanes so the committed pins carry the fixed bytes:\n  "
        + "\n  ".join(failures))
