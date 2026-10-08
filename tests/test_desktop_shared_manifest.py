"""Pin the desktop (Windows) shared-source manifest contract.

The Windows port compiles selected files straight from the Android app tree
(desktop/shared-sources.json is the single source of truth, consumed by
desktop/build.gradle.kts). This test fails loudly when:

- a listed file stops being android-free (import, fully-qualified reference,
  or Android-only library such as the zhanghai libarchive JNI wrapper),
- the manifest stops being consumed by the desktop build,
- the AndroidRuntimeClock / BuildConfig desktop twins drift out of place
  (shared sources reference them; without the twins the desktop build breaks
  and with the android originals listed it cannot compile),
- a NEW android-free file appears in the Android main tree and is neither
  shared nor allowlisted with a reason (DESK-130 drift scan: the shared set
  only grows by conscious edits, never by silent divergence).
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = ROOT / "desktop" / "shared-sources.json"
ANDROID_TREE_ROOT = "android/app/src/main/java/com/pocketrealm"
ANDROID_TEST_TREE_ROOT = "android/app/src/test/java/com/pocketrealm"
ANDROID_DEBUG_TREE_ROOT = "android/app/src/debug/java/com/pocketrealm"
DESKTOP_BUILD_FILE = ROOT / "desktop" / "build.gradle.kts"

IMPORT_ANDROID = re.compile(r"^import (android|androidx)\.", re.MULTILINE)
# Fully-qualified android.* class usage (android.os.SystemClock style) in
# code or comments; a package component followed by a class-looking token.
INLINE_ANDROID = re.compile(
    r"\bandroid\.(os|util|content|net|database|provider|hardware"
    r"|app|service|view|widget|telephony|preference"
    # remaining framework packages a fully-qualified reference could
    # sneak in under (android.system.Os, android.graphics.Bitmap, ...)
    r"|system|graphics|media|text|security|animation|drm|speech"
    r"|bluetooth|location|accounts|gles|renderscript|usb)\.[A-Za-z_]"
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
    assert manifest["android_test_tree_root"] == ANDROID_TEST_TREE_ROOT
    assert manifest["android_debug_tree_root"] == ANDROID_DEBUG_TREE_ROOT
    for key in ("shared", "shared_tests", "shared_debug"):
        entries = manifest[key]
        assert entries, f"{key} list must not be empty"
        assert entries == sorted(entries), f"{key} list must stay sorted"
        assert len(entries) == len(set(entries)), f"{key} list must not repeat files"
        for entry in entries:
            assert not entry.startswith("/"), entry
            assert "\\" not in entry, f"manifest paths use '/' separators: {entry}"
            assert not entry.endswith("Test.kt") or key == "shared_tests", (
                f"test file belong in shared_tests: {entry}"
            )


# Guard literals: a SHARED test may legitimately spell a fully-qualified
# android.* class INSIDE an absence-assertion — a pin that proves the shared
# production source does NOT reference it (the android-freedom contract from
# the asserting side). Those exact literals are exempt per file; any other
# fully-qualified android.* reference in a shared file still fails.
GUARD_LITERAL_ALLOWLIST = {
    # theTempFileSuffixStaysPlatformNeutral pins that FriendInviteStore.kt
    # never references android.os.Process (desktop JVM absence) — the test
    # must spell the banned symbol to assert its absence
    "supervisor/FriendInviteStoreTest.kt": ("android.os.Process",),
}


def test_every_shared_file_exists_and_is_android_free():
    tree_roots = {
        "shared": ANDROID_TREE_ROOT,
        "shared_tests": ANDROID_TEST_TREE_ROOT,
        "shared_debug": ANDROID_DEBUG_TREE_ROOT,
    }
    manifest = _manifest()
    for key, tree_root in tree_roots.items():
        for entry in manifest[key]:
            path = ROOT / tree_root / entry
            assert path.is_file(), f"{key} lists missing file: {entry}"
            text = path.read_text(encoding="utf-8")
            matched = IMPORT_ANDROID.search(text)
            assert matched is None, (
                f"{entry} imports {matched.group(0) if matched else ''}"
            )
            guard_literals = GUARD_LITERAL_ALLOWLIST.get(entry, ())
            scrubbed = text
            for guard in guard_literals:
                assert guard in scrubbed, (
                    f"{entry} lost its android-freedom guard literal {guard!r} "
                    "(prune the stale GUARD_LITERAL_ALLOWLIST entry)"
                )
                scrubbed = scrubbed.replace(guard, '"' * len(guard))
            matched = INLINE_ANDROID.search(scrubbed)
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


# ---------------------------------------------------------------------------
# DESK-130 drift fence.
#
# The shared set grows only by manual manifest edits, so a NEW android-free
# file in the Android main tree would silently diverge on desktop until a
# human re-audits. Every android-free .kt under the Android main tree must
# therefore be either SHARED (listed in desktop/shared-sources.json "shared",
# so the desktop build compiles it) or ALLOWLISTED here with a documented
# reason. A new shareable file fails this test until it is either shared or
# allowlisted with a reason.
#
# Allowlist values must start with one of these documented categories:
#
# - "shareable candidate:"  believed JVM-compilable and useful to the Windows
#   port; move it into shared-sources.json "shared" (and prove it with a
#   desktop build) instead of letting the list grow.
# - "needs desktop twin:"   the desktop provides a same-package+name JVM twin
#   in desktop/src/main/kotlin, so the Android original must stay unshared.
# - "android-side lane:"    implements an Android-only mechanism (JNI .so
#   shims, SAF/MediaStore, Winlator/Box64/Vulkan-driver plumbing, Wine
#   sessions) that the Windows port replaces natively; do not share.
# ---------------------------------------------------------------------------
UNSHARED_ANDROID_FREE_ALLOWLIST = {
    "client/AndroidSystemVulkanProbe.kt":
        "android-side lane: probes Android's Vulkan loader via "
        "com.winlator GPUHelper (not on the desktop classpath; WoW runs "
        "native on Windows)",
    "client/ArmSessionEnvironment.kt":
        "android-side lane: Box64/ARM Wine session environment construction; "
        "the desktop lane launches WoW.exe natively",
    "client/ArmTranslationBackend.kt":
        "android-side lane: ARM translated-Wine provider enum (box64); no "
        "desktop ARM lane exists",
    "client/ClientRuntime.kt":
        "android-side lane: proprietary-client execution boundary with "
        "Wine-prefix preparation; the desktop runtime backend launches "
        "WoW.exe directly",
    "client/ClientTeardownGate.kt":
        "android-side lane: activity-display release gate after runtime "
        "proofs; desktop teardown follows the Compose window lifecycle",
    "client/CommunityVulkanDriverDownload.kt":
        "android-side lane: community Turnip driver download for the ARM "
        "lane; also depends on the unshared update.AppUpdateCoordinator",
    "client/CommunityVulkanDrivers.kt":
        "android-side lane: pinned community Mesa Turnip catalog for the "
        "Android Vulkan lane",
    "client/GeneratedCommunityVulkanDrivers.kt":
        "android-side lane: generated catalog rows for the community driver "
        "download lane",
    "client/GeneratedVulkanDriverCatalog.kt":
        "android-side lane: generated APK-installed Vulkan driver catalog",
    "client/RendererPackageCatalog.kt":
        "android-side lane: virgl/turnip renderer package catalog for the "
        "Winlator session",
    "client/SinglePlayerAutoLogin.kt":
        "android-side lane: Android login-window topology + scheduler for "
        "the qualified vanilla client; desktop auto-login runs "
        "Win32AutoLogin (DESK-57 tracks the window-probe parity)",
    "client/UserVulkanCrashGuard.kt":
        "android-side lane: crash guard for user-imported Android Vulkan "
        "drivers",
    "client/UserVulkanDriverRegistry.kt":
        "android-side lane: app-private registry of user-imported Vulkan "
        "drivers (also depends on the unshared fs.DurableFiles twin)",
    "client/UserVulkanDriverResolution.kt":
        "android-side lane: seam between the user-driver lane and the "
        "Android launch environment",
    "client/UserVulkanDriverValidator.kt":
        "android-side lane: offline import gate for user-provided Turnip "
        "drivers",
    "client/VulkanDriverCatalog.kt":
        "android-side lane: closed, APK-installed Vulkan-driver catalog",
    "client/WineRunResult.kt":
        "android-side lane: Wine process exit taxonomy; the desktop spawns "
        "WoW.exe without Wine",
    "database/DatabaseNative.kt":
        "android-side lane: JNI shim over the Android .so SQLite seam; the "
        "desktop seam is pocket_sqlite.dll via DesktopRuntimeBackend",
    "database/StackUpComposite.kt":
        "android-side lane: the stack-up-bot composite's fail-fast core; its "
        "only consumer is the host console relay (androidTest) driving the "
        "real Binder legs — the desktop has no console-relay lane",
    "importer/ArchiveClientScanner.kt":
        "android-side lane: delegates client-root detection to "
        "client.SafClientScanner (SAF); the desktop provides a same-name "
        "twin over client.DesktopClientScanner (DESK-97)",
    "importer/ScratchArchiveExtractor.kt":
        "android-side lane: its RAR legs bind importer.RarArchiveSource "
        "(libarchive); the desktop provides a same-name twin with an honest "
        "RAR refusal (DESK-97; a RAR reader is DESK-35/64's lane)",
    "pkg/ExperimentResult.kt":
        "android-side lane: packaging-experiment evidence for the tests/avd "
        "device lanes (also depends on the unshared log.AppLog)",
    "pkg/PkgNative.kt":
        "android-side lane: JNI shim for the packaging-experiment native "
        "library",
    "realm/RealmNative.kt":
        "android-side lane: JNI shim over the Android realm .so; the desktop "
        "compiles the shared server/RealmNative.kt instead",
    "realm/RealmState.kt":
        "android-side lane: Android app-level supervisor state machine "
        "(depends on the unshared supervisor.RealmEndpoint/RuntimeMode); the "
        "desktop drives the shared RuntimeModel",
    "ui/LanPresenceModel.kt":
        "needs desktop twin: pure presentation shaping for the Android "
        "Compose LanScreen players-online line; the desktop lane renders "
        "the same shared observeWorldPresence contract through "
        "DesktopAppModel.lanPlayersOnline + budgetLine instead",
    "ui/UserVulkanDriverPresentation.kt":
        "android-side lane: presentation rules for the Android user-driver "
        "picker",
    "wine/WineSpikeNative.kt":
        "android-side lane: JNI bridge to libwine_spike.so; the desktop has "
        "no Wine lane",
}

ALLOWLIST_REASON_CATEGORIES = (
    "shareable candidate:",
    "needs desktop twin:",
    "android-side lane:",
)


def _android_tree_kt_files():
    return sorted((ROOT / ANDROID_TREE_ROOT).rglob("*.kt"))


def _is_android_free(text):
    """The shared android-free predicate (same regexes as the pinned test)."""
    if IMPORT_ANDROID.search(text) or INLINE_ANDROID.search(text):
        return False
    return all(lib not in text for lib in ANDROID_ONLY_LIBS)


def test_unshared_android_free_files_are_shared_or_allowlisted():
    manifest = _manifest()
    shared = set(manifest["shared"])
    drift = []
    for path in _android_tree_kt_files():
        rel = path.relative_to(ROOT / ANDROID_TREE_ROOT).as_posix()
        if rel in shared:
            continue
        text = path.read_text(encoding="utf-8")
        if _is_android_free(text):
            drift.append(rel)
    unclassified = [rel for rel in drift if rel not in UNSHARED_ANDROID_FREE_ALLOWLIST]
    assert not unclassified, (
        "New android-free file(s) in the Android tree are neither shared nor "
        "allowlisted (DESK-130 drift fence). Either list each in "
        "desktop/shared-sources.json 'shared' (proving the desktop build "
        "compiles it) or add an entry to UNSHARED_ANDROID_FREE_ALLOWLIST "
        "with a documented reason:\n  " + "\n  ".join(unclassified)
    )


def test_unshared_allowlist_entries_stay_accurate():
    manifest = _manifest()
    shared = set(manifest["shared"])
    tree_root = ROOT / ANDROID_TREE_ROOT
    for rel, reason in UNSHARED_ANDROID_FREE_ALLOWLIST.items():
        path = tree_root / rel
        assert path.is_file(), (
            f"allowlist entry points at a vanished file (prune it): {rel}"
        )
        assert rel not in shared, (
            f"allowlist entry is now shared (prune it): {rel}"
        )
        assert reason.startswith(ALLOWLIST_REASON_CATEGORIES), (
            f"allowlist reason for {rel} must start with one of "
            f"{list(ALLOWLIST_REASON_CATEGORIES)}: {reason!r}"
        )
        text = path.read_text(encoding="utf-8")
        assert _is_android_free(text), (
            f"allowlist entry is no longer android-free (the entry is dead "
            f"weight; prune it): {rel}"
        )


# ---------------------------------------------------------------------------
# Direct-run support: `python tests/test_desktop_shared_manifest.py` runs the
# same checks the pytest lane runs (stdlib only — no pytest dependency), so
# the literal invocation is meaningful instead of a silent no-op. Exit 0 =
# all checks passed; exit 1 = at least one check failed (its assertion error
# is printed).
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    _CHECKS = [
        value
        for name, value in sorted(globals().items())
        if name.startswith("test_") and callable(value)
    ]
    _failed = 0
    for _check in _CHECKS:
        try:
            _check()
            print(f"PASS {_check.__name__}")
        except AssertionError as _error:
            _failed += 1
            print(f"FAIL {_check.__name__}: {_error}")
    if _failed:
        raise SystemExit(f"{_failed} of {len(_CHECKS)} checks failed")
    print(f"OK: all {len(_CHECKS)} manifest checks passed")
