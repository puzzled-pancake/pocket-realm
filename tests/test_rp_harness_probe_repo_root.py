"""Pin the rp_harness probe scripts to derive REPO from their own
location (Path(__file__).resolve().parents[2], the probe_admission.py
idiom) instead of a hardcoded C-drive checkout — on the authoritative E:
a hardcoded C-drive path made every probe import desktop_host from a
foreign or missing tree (ImportError)."""

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tools" / "rp_harness"

PROBES = [
    HARNESS / "probe_world_auth.py",
    HARNESS / "probe_whisper_dump.py",
    HARNESS / "probe_whisper_hunt.py",
    HARNESS / "probe_whisper_hunt2.py",
]


def load_probe(path):
    # Module-level code only wires sys.path and imports DesktopHost;
    # every probe guards its live work behind `if __name__ == "__main__"`.
    spec = importlib.util.spec_from_file_location(path.stem + "_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_probes_resolve_repo_to_the_tree_they_live_in():
    for path in PROBES:
        module = load_probe(path)
        assert module.REPO == ROOT, (
            f"{path.name}: REPO must be the root containing this probe, "
            f"got {module.REPO}"
        )


def test_probe_repo_root_carries_the_harness_they_import():
    for path in PROBES:
        module = load_probe(path)
        desktop_host = module.REPO / "tools" / "rp_harness" / "desktop_host.py"
        assert desktop_host.is_file(), (
            f"{path.name}: REPO {module.REPO} does not carry tools/rp_harness"
        )


def test_no_harness_script_hardcodes_a_drive_letter_repo():
    for path in sorted(HARNESS.glob("*.py")):
        if "__pycache__" in path.parts:
            continue
        text = path.read_text(encoding="utf-8")
        assert "pocket_realm_complete" not in text, (
            f"{path.name}: hardcodes a checkout path"
        )
