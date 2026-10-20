"""R2-1 (W5 code-red round 1): every renderer package the arm64 full lane
requires must have a surviving producer across the stager's atomic
renderer-packages replace.

The deleted buildGladioClientArm task was the last (re)publisher of the
box64-gladio-eaa2a8d package while tools/stage_renderer_packages.py deletes
the whole renderer-packages tree on every run — the arm64 assembleDebug could
never go green again from anything in the repo (the bytes are gitignored).
The repair vendors the retired lane's reviewed bytes at
native/prebuilt-renderers/box64-gladio-eaa2a8d/ and makes the stager itself
re-stage them (fail-closed on digest). These pins hold that contract:
a required package id with no producer, a drifted vendored byte, or a stager
that stops re-staging gladio all fail here.
"""
from __future__ import annotations

import hashlib
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD_GRADLE = ROOT / "android" / "app" / "build.gradle.kts"
STAGER = ROOT / "tools" / "stage_renderer_packages.py"
VIRGL_STAGER = ROOT / "tools" / "stage_virgl_renderer.py"
PREBUILT = ROOT / "native" / "prebuilt-renderers" / "box64-gladio-eaa2a8d" / "libGL.so.1"

sys.path.insert(0, str(ROOT / "tools"))


def _required_package_ids() -> set[str]:
    """Package ids the gradle closure gate requires under renderer-packages/."""
    text = BUILD_GRADLE.read_text(encoding="utf-8")
    return set(re.findall(
        r"renderer-packages/([A-Za-z0-9._-]+)/", text,
    ))


def test_every_required_renderer_package_has_a_surviving_producer():
    import stage_renderer_packages as stager

    producers = {str(package["id"]) for package in stager.PACKAGES}
    producers.add(stager.GLADIO_PACKAGE_ID)
    virgl_text = VIRGL_STAGER.read_text(encoding="utf-8")
    virgl_match = re.search(r'["\']([A-Za-z0-9._-]*virgl[A-Za-z0-9._-]*)["\']', virgl_text)
    assert virgl_match, "stage_virgl_renderer.py lost its package id"
    producers.add(virgl_match.group(1))

    required = _required_package_ids()
    assert required, "closure-gate renderer-package parsing found nothing"
    unproducible = sorted(required - producers)
    assert not unproducible, (
        f"the arm64 full lane requires renderer packages nothing produces: "
        f"{unproducible} — restore a producer in tools/stage_renderer_packages.py "
        "(or the owning stager) or retire the requirement; a required id whose "
        "bytes are gitignored with no producer is the R2-1 class"
    )


def test_vendored_gladio_bytes_match_every_pin():
    import stage_renderer_packages as stager

    assert PREBUILT.is_file(), (
        f"the vendored gladio package is missing: {PREBUILT} — it is the only "
        "producer of the box64-gladio-eaa2a8d package the closure gate requires"
    )
    data = PREBUILT.read_bytes()
    assert len(data) == stager.GLADIO_SIZE
    digest = hashlib.sha256(data).hexdigest()
    assert digest == stager.GLADIO_SHA256

    # The gradle closure gate pins the same bytes.
    gradle = BUILD_GRADLE.read_text(encoding="utf-8")
    assert stager.GLADIO_SHA256 in gradle, (
        "the stager's gladio pin and the gradle closure gate disagree — "
        "re-vendoring must move both together"
    )
    assert f'length() == {stager.GLADIO_SIZE}' in gradle.replace("_", ""), (
        "the gradle gladio size pin drifted from the vendored artifact"
    )

    # The vendored tree carries the origin provenance (retired-lane record).
    provenance = PREBUILT.parent / "BUILD_PROVENANCE.json"
    assert provenance.is_file(), (
        "the vendored gladio package lost its BUILD_PROVENANCE.json — the "
        "origin record (retired lane, gladio-eaa2a8d pin) is the provenance chain"
    )


def test_stager_restages_gladio_after_the_atomic_replace(tmp_path, monkeypatch):
    """Functional wipe->restage proof: a full main() run against an EMPTY
    staged tree must come up with both dxvk packages AND the gladio package
    (the exact scenario R2-1 flagged — the atomic replace erasing gladio).
    The vulkan-driver leg is stubbed; it has its own gates."""
    import json

    import stage_renderer_packages as stager

    renderer_stage = tmp_path / "renderer-packages"
    driver_stage = tmp_path / "vulkan-drivers"
    monkeypatch.setattr(stager, "RENDERER_STAGE", renderer_stage)
    monkeypatch.setattr(stager, "DRIVER_STAGE", driver_stage)
    monkeypatch.setattr(stager, "stage_vulkan_drivers", lambda: {"drivers": []})

    # main() returns None on success and raises loudly on any pin mismatch.
    stager.main()

    for package_id in (
        "box64-dxvk-2.4.1", "box64-dxvk-1.10.3", stager.GLADIO_PACKAGE_ID,
    ):
        assert (renderer_stage / package_id).is_dir(), (
            f"the stager's atomic replace left {package_id} unstaged — the "
            "arm64 full lane would fail its closure gate again (R2-1)"
        )
    staged = renderer_stage / stager.GLADIO_PACKAGE_ID / "libGL.so.1"
    assert staged.read_bytes() == PREBUILT.read_bytes()

    # the manifest SHAPE is part of the contract. `packages` is the
    # closed DXVK SELECTION catalog the app enumerates and the gradle gate
    # pins (exact 2-row set, every row backend=='dxvk'); gladio is recorded
    # under `opengl_packages` — exactly once, with the pinned sha256. The
    # variant appended the gladio row into `packages`, which fails
    # the gradle closed-catalog check; both shapes must not be able to pass.
    manifest = json.loads(
        (renderer_stage / "catalog.json").read_text(encoding="utf-8"))
    packages = manifest["packages"]
    assert [row["id"] for row in packages] == [
        "box64-dxvk-2.4.1", "box64-dxvk-1.10.3",
    ], f"the dxvk selection set is not the closed 2-row set: {packages}"
    assert all(row["backend"] == "dxvk" for row in packages), (
        "a non-dxvk row entered the closed selection set"
    )
    opengl = manifest["opengl_packages"]
    assert [row["id"] for row in opengl] == [stager.GLADIO_PACKAGE_ID], (
        "the gladio package must appear exactly once under opengl_packages"
    )
    assert opengl[0]["source"]["sha256"] == stager.GLADIO_SHA256
    assert opengl[0]["files"][0]["sha256"] == stager.GLADIO_SHA256

    # The host's REAL staged catalog (when present) must carry the same shape.
    real_catalog = ROOT / (
        "native/.build-arm64/wine-staging/assets/arm-translated/"
        "renderer-packages/catalog.json"
    )
    if real_catalog.is_file():
        real = json.loads(real_catalog.read_text(encoding="utf-8"))
        assert [row["id"] for row in real["packages"]] == [
            "box64-dxvk-2.4.1", "box64-dxvk-1.10.3",
        ]
        assert all(row["backend"] == "dxvk" for row in real["packages"])
        assert [row["id"] for row in real["opengl_packages"]] == [
            stager.GLADIO_PACKAGE_ID,
        ]


def test_index_blob_carries_the_worktree_manifest_contract():
    """R1-r2-1: the producer fix must live in the git INDEX, not only the
    working tree — an exit commit made from a stale index would ship the
    closure-gate-breaking round-1 stager while every worktree-reading test
    stays green. Reads the index blob via `git show :<path>`."""
    import subprocess

    result = subprocess.run(
        ["git", "show", ":tools/stage_renderer_packages.py"],
        cwd=ROOT, capture_output=True,
    )
    assert result.returncode == 0, (
        "tools/stage_renderer_packages.py is not in the index — stage it "
        "(git add) before exit"
    )
    index_blob = result.stdout.decode("utf-8", errors="replace")
    assert "opengl_packages" in index_blob, (
        "the INDEXED stager predates the R2-r2-1 manifest-shape fix — "
        "re-stage tools/stage_renderer_packages.py or the exit commit ships "
        "the closure-gate-breaking variant"
    )
    assert 'manifest["packages"] = records' not in index_blob, (
        "the INDEXED stager appends the gladio row into the closed dxvk "
        "selection set — the exact R1-r2-1 regression"
    )
    # And the index must agree with the tested worktree bytes.
    worktree = STAGER.read_bytes()
    assert index_blob.encode("utf-8") == worktree, (
        "the indexed stager differs from the tested worktree file — re-stage "
        "after every edit (the R1-r2-1 MM-state class)"
    )


def test_sources_json_gladio_entry_matches_the_vendored_identity():
    """R3-r4-1: the sources.json gladio-eaa2a8d ledger entry must describe
    the SAME byte identity the gates pin (the vendored 498656 / c02fb727
    artifact) — a ledger naming a superseded digest for the only foreign
    binary the wave ships sends every provenance reader to a conflicting
    identity. The superseded identity may survive only beside an explicit
    retirement annotation."""
    import json

    sources = json.loads(
        (ROOT / "schemas" / "sources.json").read_text(encoding="utf-8"))
    entries = sources.get("sources") or []
    entry = next(e for e in entries if e.get("id") == "gladio-eaa2a8d")
    role = entry.get("role", "")
    assert "498656" in role and (
        "c02fb7275463bebcc3aa3fcf3e8e6de668bd2e6f39bda57052d3352801636d08" in role
    ), (
        "the sources.json gladio entry must record the vendored identity "
        "(498656 / c02fb727...) the closure gate, stager, and vendored "
        "BUILD_PROVENANCE.json all pin"
    )
    assert "native/prebuilt-renderers/box64-gladio-eaa2a8d" in role, (
        "the entry must cite the vendored artifact path"
    )
    if "1d9663bb" in role or "530400" in role:
        assert "superseded" in role, (
            "the superseded 530400/1d9663bb identity may appear only beside "
            "an explicit retirement annotation"
        )
