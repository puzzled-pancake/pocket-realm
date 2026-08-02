#!/usr/bin/env python3
"""Qualify the full O06 ClientRuntime lifecycle on one pinned AVD lane."""
from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
ADB = SDK / "platform-tools" / "adb.exe"
APP = ANDROID / "app/build/outputs/apk/clientRuntime/app-clientRuntime.apk"
TEST = ANDROID / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
TEST_CLASS = "com.pocketrealm.client.ClientRuntimeLifecycleTest"
LANES = {
    "modern": ("AVD-Modern-x86_64-v1", 4096),
    "16k": ("AVD-16K-x86_64-v1", 16384),
}


def run(args: list[str], *, timeout: int = 600, binary: bool = False):
    return subprocess.run(args, capture_output=True, timeout=timeout,
                          text=not binary, check=False)


def adb(serial: str, *args: str, timeout: int = 600, binary: bool = False):
    return run([str(ADB), "-s", serial, *args], timeout=timeout, binary=binary)


def prop(serial: str, name: str) -> str:
    return adb(serial, "shell", "getprop", name, timeout=20).stdout.strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lane", required=True, choices=LANES)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--no-install", action="store_true")
    parser.add_argument("--clear-data", action="store_true")
    options = parser.parse_args()
    avd, expected_page = LANES[options.lane]

    if not options.no_build:
        gradle = ANDROID / "gradlew.bat"
        result = run([str(gradle), ":app:assembleClientRuntime", ":app:assembleDebugAndroidTest",
                      "-Pandroid.suppressUnsupportedCompileSdk=35", "-p", str(ANDROID)])
        if result.returncode:
            print(result.stdout + result.stderr, file=sys.stderr)
            return result.returncode
    if not APP.is_file() or not TEST.is_file():
        print("O06 APKs are missing", file=sys.stderr)
        return 2

    page = adb(options.serial, "shell", "getconf", "PAGE_SIZE", timeout=20).stdout.strip()
    api = prop(options.serial, "ro.build.version.sdk")
    abi = prop(options.serial, "ro.product.cpu.abi")
    actual_avd = prop(options.serial, "ro.boot.qemu.avd_name") or "unknown"
    if page != str(expected_page):
        print(f"page-size mismatch: expected={expected_page} actual={page}", file=sys.stderr)
        return 2
    if api != "35" or abi != "x86_64":
        print(f"device mismatch: expected API 35/x86_64 actual API {api}/{abi}", file=sys.stderr)
        return 2
    if not options.no_install:
        for apk in (APP, TEST):
            result = adb(options.serial, "install", "-r", "-t", str(apk), timeout=180)
            if result.returncode or "Success" not in result.stdout:
                print(result.stdout + result.stderr, file=sys.stderr)
                return 2
    if options.clear_data:
        adb(options.serial, "shell", "pm", "clear", "com.pocketrealm", timeout=30)
    # Keep the runtime screenshot about the runtime: the app's unrelated API
    # 33 notification prompt otherwise sits above the attached X surface.
    adb(options.serial, "shell", "pm", "grant", "com.pocketrealm",
        "android.permission.POST_NOTIFICATIONS", timeout=20)

    adb(options.serial, "shell", "am", "force-stop", "com.pocketrealm", timeout=20)
    adb(options.serial, "logcat", "-c", timeout=20)
    instrument = adb(
        options.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
        "-e", "lane", options.lane,
        "com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner", timeout=600,
    )
    output = instrument.stdout + instrument.stderr
    diagnostics = adb(
        options.serial, "shell", "run-as", "com.pocketrealm", "cat",
        "no_backup/wine/last-session.json", timeout=20,
    ).stdout
    logcat = adb(options.serial, "logcat", "-d", timeout=60).stdout
    passed = instrument.returncode == 0 and "OK (1 test)" in output and \
        "CLIENT_RUNTIME_ACCEPTANCE clean=true window=true focus=true audioOff=true keyboard=true mouse=true" in logcat and \
        "CLIENT_RUNTIME_FORCE_STOP state=FORCE_STOPPED forced=true" in logcat

    evidence_dir = ROOT / "tests/avd" / avd / "evidence"
    evidence_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%SZ")
    verdict = "PASS" if passed else "FAIL"
    evidence = evidence_dir / f"clientRuntime-o06-lifecycle-{stamp}.{verdict}.log"
    evidence.write_text(
        "\n".join([
            "O06 CLIENT RUNTIME QUALIFICATION",
            f"utc={datetime.now(timezone.utc).isoformat()}",
            f"lane={options.lane}", f"avd_record={avd}", f"avd_actual={actual_avd}",
            f"serial={options.serial}", f"api={api}", f"abi={abi}",
            "variant=clientRuntime", f"page_size={page}",
            f"app_sha256={sha256(APP)}", f"test_sha256={sha256(TEST)}", f"verdict={verdict}",
            "", "=== instrumentation ===", output,
            "", "=== final persisted diagnostics ===", diagnostics,
            "", "=== relevant logcat ===",
            "\n".join(line for line in logcat.splitlines()
                      if "ClientRuntime" in line or "wine_spike" in line or "O06Acceptance" in line),
        ]), encoding="utf-8",
    )

    screenshot = adb(
        options.serial, "exec-out", "run-as", "com.pocketrealm", "cat",
        "cache/client-runtime-proof.png", timeout=30, binary=True,
    )
    if screenshot.returncode == 0 and screenshot.stdout.startswith(b"\x89PNG"):
        (evidence_dir / f"clientRuntime-o06-surface-{stamp}.png").write_bytes(screenshot.stdout)

    print(f"{verdict}: {evidence.relative_to(ROOT)}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
