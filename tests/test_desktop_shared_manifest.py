"""Pin the desktop (Windows) shared-source manifest contract.

The Windows port compiles selected files straight from the Android app tree
(desktop/shared-sources.json is the single source of truth, consumed by
desktop/build.gradle.kts). This test fails loudly when:

- a listed file stops being android-free (import, fully-qualified reference,
  or Android-only library such as the zhanghai libarchive JNI wrapper),
- the manifest stops being consumed by the desktop build,
- the AndroidRuntimeClock / BuildConfig desktop twins drift out of place
  (shared sources reference them; without the twins the desktop build breaks
  and with the android originals listed it cannot compile).
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = ROOT / "desktop" / "shared-sources.json"
ANDROID_TREE_ROOT = "android/app/src/main/java/com/pocketrealm"
DESKTOP_BUILD_FILE = ROOT / "desktop" / "build.gradle.kts"

IMPORT_ANDROID = re.compile(r"^import (android|androidx)\.", re.MULTILINE)
# Fully-qualified android.* class usage (android.os.SystemClock style) in
# code or comments; a package component followed by a class-looking token.
INLINE_ANDROID = re.compile(
    r"\bandroid\.(os|util|content|net|database|provider|hardware"
    r"|app|service|view|widget|telephony|preference)\.[A-Za-z_]"
)
ANDROID_ONLY_LIBS = ("me.zhanghai.",)

DESKTOP_TWINS = {
    "supervisor/AndroidRuntimeClock.kt":
        "desktop/src/main/kotlin/com/pocketrealm/supervisor/AndroidRuntimeClock.kt",
    "BuildConfig (AGP-generated on Android)":
        "desktop/src/main/kotlin/com/pocketrealm/BuildConfig.kt",
}


def _manifest():
    with MANIFEST_PATH.open(encoding="utf-8") as handle:
        return json.load(handle)


def test_manifest_shape_is_pinned():
    manifest = _manifest()
    assert manifest["android_tree_root"] == ANDROID_TREE_ROOT
    shared = manifest["shared"]
    assert shared, "shared list must not be empty"
    assert shared == sorted(shared), "shared list must stay sorted"
    assert len(shared) == len(set(shared)), "shared list must not repeat files"
    for entry in shared:
        assert not entry.startswith("/"), entry
        assert "\\" not in entry, f"manifest paths use '/' separators: {entry}"


def test_every_shared_file_exists_and_is_android_free():
    for entry in _manifest()["shared"]:
        path = ROOT / ANDROID_TREE_ROOT / entry
        assert path.is_file(), f"manifest lists missing file: {entry}"
        text = path.read_text(encoding="utf-8")
        matched = IMPORT_ANDROID.search(text)
        assert matched is None, f"{entry} imports {matched.group(0) if matched else ''}"
        matched = INLINE_ANDROID.search(text)
        assert matched is None, (
            f"{entry} references {matched.group(0) if matched else ''} "
            "fully-qualified; it needs a desktop twin instead of manifest entry"
        )
        for lib in ANDROID_ONLY_LIBS:
            assert lib not in text, f"{entry} depends on Android-only library {lib}"


def test_android_clock_split_stays_in_place():
    shared = _manifest()["shared"]
    assert "supervisor/RuntimeContracts.kt" in shared
    # The android-tree clock object is NOT shared: the desktop provides its
    # own same-named twin (see DESKTOP_TWINS below).
    assert "supervisor/AndroidRuntimeClock.kt" not in shared


def test_desktop_twins_exist():
    for label, twin in DESKTOP_TWINS.items():
        assert (ROOT / twin).is_file(), f"missing desktop twin for {label}: {twin}"


def test_desktop_build_consumes_the_manifest():
    text = DESKTOP_BUILD_FILE.read_text(encoding="utf-8")
    assert "shared-sources.json" in text, (
        "desktop/build.gradle.kts must consume shared-sources.json "
        "(single source of truth for the compiled shared set)"
    )
