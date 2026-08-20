"""Contract for tools/generate_community_vulkan_drivers.py (plan
community-turnip-list §7): the reviewed manifest is the only source of the
generated Kotlin list, the checked-in projection is fresh, and the generator
rejects unpinned or unprovenanced edits — with exactly the semantics the
Kotlin data-class init will apply (full-match regexes, UTF-16 label length)."""

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools" / "generate_community_vulkan_drivers.py"
MANIFEST = ROOT / "schemas" / "community-vulkan-drivers.json"
GENERATED = (
    ROOT / "android" / "app" / "src" / "main" / "java" / "com" /
    "pocketrealm" / "client" / "GeneratedCommunityVulkanDrivers.kt"
)


def load_tool():
    spec = importlib.util.spec_from_file_location("gen_community_drivers", TOOL)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def manifest_document():
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def write_mutated(tmp_path, mutate):
    document = manifest_document()
    mutate(document)
    path = tmp_path / "community-vulkan-drivers.json"
    path.write_text(json.dumps(document), encoding="utf-8")
    return path


def test_check_mode_passes_on_the_checked_in_tree():
    result = subprocess.run(
        [sys.executable, str(TOOL), "--check"], capture_output=True, text=True,
    )
    assert result.returncode == 0, result.stderr


def test_projection_is_exactly_the_rendered_manifest():
    module = load_tool()
    assert module.render(module.load_manifest()) == GENERATED.read_bytes()


def test_check_mode_fails_when_the_projection_drifts(tmp_path, monkeypatch):
    module = load_tool()
    monkeypatch.setattr(module, "OUTPUT", tmp_path / "Generated.kt")
    (tmp_path / "Generated.kt").write_bytes(b"// doctored\n")
    monkeypatch.setattr(sys, "argv", [str(TOOL), "--check"])
    with pytest.raises(RuntimeError):
        module.main()


def test_seed_manifest_pins_the_audited_builds():
    document = manifest_document()
    assert {driver["id"] for driver in document["drivers"]} == {
        "community-turnip-26.0.0-r8",
        "community-turnip-25.1.0-r2",
    }
    for driver in document["drivers"]:
        source = driver["source"]
        assert source["license"] == "MIT"
        assert source["format"] in {"adrenotools-zip", "bare-so"}
        assert source["url"].startswith(
            f"https://github.com/{source['repo']}/releases/download/{source['release']}/"
        )
        assert 0 < source["size"] <= 64 * 1024 * 1024
        assert len(source["sha256"]) == 64


@pytest.mark.parametrize(
    "name,mutate",
    [
        ("schema-bump", lambda d: d.__setitem__("schema", 2)),
        ("policy-change", lambda d: d.__setitem__("policy", "latest-from-github")),
        ("no-drivers", lambda d: d.__setitem__("drivers", [])),
        ("trailing-newline-id", lambda d: d["drivers"][0].__setitem__("id", "community-turnip\n")),
        ("catalog-id-shape", lambda d: d["drivers"][0].__setitem__("id", "turnip-26.1.0")),
        ("duplicate-id", lambda d: d["drivers"][1].__setitem__("id", d["drivers"][0]["id"])),
        ("blank-version", lambda d: d["drivers"][0].__setitem__("version", " ")),
        ("blank-label", lambda d: d["drivers"][0]["display"].__setitem__("label", " ")),
        (
            "utf16-oversized-label",
            lambda d: d["drivers"][0]["display"].__setitem__("label", "\N{GRINNING FACE}" * 33),
        ),
        ("blank-note", lambda d: d["drivers"][0]["display"].__setitem__("note", " ")),
        ("dot-segment-repo", lambda d: d["drivers"][0]["source"].__setitem__("repo", "../AdrenoToolsDrivers")),
        ("slashless-repo", lambda d: d["drivers"][0]["source"].__setitem__("repo", "AdrenoToolsDrivers")),
        ("blank-release", lambda d: d["drivers"][0]["source"].__setitem__("release", " ")),
        (
            "foreign-host-url",
            lambda d: d["drivers"][0]["source"].__setitem__(
                "url", "https://evil.example.com/Turnip_v26.0.0_R8.zip"),
        ),
        (
            "wrong-repo-url",
            lambda d: d["drivers"][0]["source"].__setitem__(
                "url", "https://github.com/other/repo/releases/download/"
                       "v26.0.0-rc08/Turnip_v26.0.0_R8.zip"),
        ),
        (
            "wrong-release-url",
            lambda d: d["drivers"][0]["source"].__setitem__(
                "url", "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/"
                       "download/other/Turnip_v26.0.0_R8.zip"),
        ),
        (
            "query-string-url",
            lambda d: d["drivers"][0]["source"].__setitem__(
                "url", d["drivers"][0]["source"]["url"] + "?token=1"),
        ),
        ("zero-size", lambda d: d["drivers"][0]["source"].__setitem__("size", 0)),
        (
            "over-cap-size",
            lambda d: d["drivers"][0]["source"].__setitem__("size", 64 * 1024 * 1024 + 1),
        ),
        ("non-hex-digest", lambda d: d["drivers"][0]["source"].__setitem__("sha256", "z" * 64)),
        (
            "trailing-newline-digest",
            lambda d: d["drivers"][0]["source"].__setitem__("sha256", "a" * 64 + "\n"),
        ),
        (
            "bad-library-digest",
            lambda d: d["drivers"][0]["source"].__setitem__("library_sha256", "short"),
        ),
        ("non-mit-license", lambda d: d["drivers"][0]["source"].__setitem__("license", "Proprietary")),
        ("vendor-blob-format", lambda d: d["drivers"][0]["source"].__setitem__("format", "qualcomm-blob")),
        ("blank-upstream", lambda d: d["drivers"][0]["source"].__setitem__("upstream", " ")),
    ],
)
def test_generator_rejects_unpinned_edits(tmp_path, monkeypatch, name, mutate):
    module = load_tool()
    monkeypatch.setattr(module, "MANIFEST", write_mutated(tmp_path, mutate))
    with pytest.raises(RuntimeError):
        module.load_manifest()


def test_library_sha256_stays_optional():
    module = load_tool()
    document = manifest_document()
    del document["drivers"][0]["source"]["library_sha256"]
    path = MANIFEST.parent.parent / "tmp" / "test-community-manifest.json"
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(document), encoding="utf-8")
    monkeypatch_manifest = path
    original = module.MANIFEST
    module.MANIFEST = monkeypatch_manifest
    try:
        rendered = module.render(module.load_manifest()).decode("utf-8")
        assert "librarySha256 = null" in rendered
    finally:
        module.MANIFEST = original
        path.unlink()
