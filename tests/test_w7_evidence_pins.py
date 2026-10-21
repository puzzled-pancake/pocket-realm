"""W7 code-red R6-2: the release version lockstep pin.

The 0.110.0 bump spans five sites with no automated binding — nothing
failed if android versionName, the desktop gradle version, the AppVersion
constant, or the vcpkg version-string drifted apart at a future bump.
This pin parses all four machine-readable sites plus the latest release
notes and asserts they move together.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def _android_version() -> tuple[str, int]:
    text = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    name = re.search(r'versionName = "([^"]+)"', text)
    code = re.search(r"versionCode = (\d+)", text)
    assert name and code, "android build.gradle.kts lost its version lines"
    return name.group(1), int(code.group(1))


def test_release_version_is_in_lockstep_across_all_sites():
    """All four machine-readable version sites must carry one identical,
    non-alpha version string (the release milestone posture)."""
    android_name, android_code = _android_version()

    desktop_gradle = (ROOT / "desktop/build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'version = "([^"]+)"', desktop_gradle)
    assert m, "desktop/build.gradle.kts lost its version line"
    desktop_version = m.group(1)

    gateway_gradle = (ROOT / "gateway/build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'version = "([^"]+)"', gateway_gradle)
    assert m, "gateway/build.gradle.kts lost its version line"
    gateway_version = m.group(1)

    app_version = (ROOT / "desktop/src/main/kotlin/com/pocketrealm/desktop/"
                   "AppVersion.kt").read_text(encoding="utf-8")
    m = re.search(r'APP_VERSION: String = "([^"]+)"', app_version)
    assert m, "AppVersion.kt lost its APP_VERSION constant"
    kt_version = m.group(1)

    vcpkg = json.loads(
        (ROOT / "native/win-deps/vcpkg.json").read_text(encoding="utf-8"))
    vcpkg_version = vcpkg["version-string"]

    versions = {
        "android versionName": android_name,
        "desktop gradle version": desktop_version,
        "gateway gradle version": gateway_version,
        "AppVersion.kt": kt_version,
        "vcpkg version-string": vcpkg_version,
    }
    assert len(set(versions.values())) == 1, (
        f"version sites drifted apart: {versions}")
    (version,) = set(versions.values())
    assert "alpha" not in version and "beta" not in version and "rc" not in version, (
        f"the release milestone version must be the non-prerelease form, "
        f"got {version!r}")


def test_release_notes_match_the_build_version_and_code():
    """The newest RELEASE-NOTES doc must name the build's exact version and
    versionCode — the notes are the release-facing truth the owner tags."""
    android_name, android_code = _android_version()
    notes = _notes_by_version((ROOT / "docs").glob("RELEASE-NOTES-*.md"))
    assert notes, "no RELEASE-NOTES-*.md found"
    latest = notes[-1]
    m = re.search(r"RELEASE-NOTES-(\d+\.\d+\.\d+)\.md", latest.name)
    assert m, f"unparseable release-notes filename: {latest.name}"
    assert m.group(1) == android_name, (
        f"the newest release notes ({latest.name}) do not match the build "
        f"version {android_name}")
    text = latest.read_text(encoding="utf-8")
    m = re.search(r"versionCode`?\s*(\d+)", text)
    assert m, f"{latest.name} lost its versionCode upgrade note"
    assert int(m.group(1)) == android_code, (
        f"{latest.name} quotes versionCode {m.group(1)} but the build "
        f"carries {android_code}")


def _notes_by_version(notes):
    """W7-R6-r2-8: order RELEASE-NOTES by the parsed (major, minor, patch)
    tuple — the raw filename sort misbinds for any future version whose
    digits sort before an existing longer one (0.9x > 0.110.x)."""
    import re as _re

    def key(path):
        m = _re.search(r"RELEASE-NOTES-(\d+)\.(\d+)\.(\d+)\.md", path.name)
        return (int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else (0, 0, 0)
    return sorted(notes, key=key)
