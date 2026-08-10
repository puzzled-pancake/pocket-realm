#!/usr/bin/env python3
"""Generate loopback realm configs, seed the SQLite DBs, and run the native
lifecycle test against the built libpocketrealm.so on a connected device.

This is the O04 self-test driver: it produces a self-contained realm run-root
(configs + seeded DBs + data dir), pushes it + the stripped libpocketrealm.so +
pocket_lifecycle_test to the emulator, runs the test, and reports PASS/FAIL.

Honesty contract (agent.md): the test exercises the real C ABI against real
seeded SQLite databases. It does NOT fake the client-data health conditions:
those report BLOCKED_ON_CLIENT_DATA. The script exits non-zero if the test
fails or any prerequisite is missing.

Usage:
    python3 tools/run_realm_test.py --abi x86_64|arm64-v8a [--serial <device>]

Inputs:
    native/.build-x86_64/ or native/.build-arm64/pocket-runtime-build/
        {libpocketrealm.so,pocket_lifecycle_test}
    native/cmangos/sql/base/*.sql + native/classic-db/Full_DB/*.sql.gz (via seed_realm_db.py)
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native"
ABIS = {
    # The native builder deliberately names the ARM root `.build-arm64`,
    # while the NDK/ELF toolchain triple remains `aarch64-linux-android`.
    # Keep those identities separate so a live ARM probe never looks under a
    # non-existent `.build-aarch64` directory.
    "arm64-v8a": {"build": "arm64", "deps": "arm64", "triple": "aarch64"},
    "x86_64": {"build": "x86_64", "deps": "x86_64", "triple": "x86_64"},
}


def run(cmd, **kw):
    print("  $", " ".join(str(c) for c in cmd[:8]) + (" ..." if len(cmd) > 8 else ""))
    return subprocess.run([str(c) for c in cmd], **kw)


def adb_root(serial: str | None):
    adb = os.environ.get("ADB", "adb")
    base = [adb]
    if serial:
        base += ["-s", serial]
    # The device push/run happens under /data/local/tmp (shell-writable).
    return base


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--abi", required=True, choices=list(ABIS))
    ap.add_argument("--serial", default=None, help="adb device serial (default: auto)")
    ap.add_argument("--keep", action="store_true",
                    help="keep the staged run-root on device for inspection")
    args = ap.parse_args()

    lane = ABIS[args.abi]
    build_suffix = lane["build"]
    deps_suffix = lane["deps"]
    triple = lane["triple"]
    build_dir = NATIVE / f".build-{build_suffix}"
    rt_build = build_dir / "pocket-runtime-build"
    lib = rt_build / "libpocketrealm.so"
    test_bin = rt_build / "pocket_lifecycle_test"
    if not lib.is_file() or not test_bin.is_file():
        print(f"ERROR: runtime artifacts missing under {rt_build}\n"
              f"  build first: python3 scripts/build_native.py --abi {args.abi} "
              f"--runtime --runtime-tests cmangos", file=sys.stderr)
        return 1

    # 1. Stage a run-root locally: configs + seeded DBs + empty data dir.
    stage = NATIVE / f".o4-stage-{build_suffix}"
    if stage.exists():
        shutil.rmtree(stage)
    (stage / "db").mkdir(parents=True)
    (stage / "content").mkdir(parents=True)  # empty DataDir -> client-data gate
    (stage / "content" / "dbc").mkdir()       # exists-but-empty -> DBC gate fires cleanly
    (stage / "content" / "maps").mkdir()

    print("=== Seeding SQLite DBs ===")
    r = subprocess.run([sys.executable, str(ROOT / "tools" / "seed_realm_db.py"),
                        "--out", str(stage / "db")])
    if r.returncode != 0:
        print("ERROR: DB seeding failed", file=sys.stderr)
        return 1

    # The configs run ON THE DEVICE, so paths must be the device push root,
    # not the host staging path. Defined here so config generation uses them.
    adb = adb_root(args.serial)
    dev_root = "/data/local/tmp/pocket-o4"
    dev_db = f"{dev_root}/db"
    dev_content = f"{dev_root}/content"

    print("=== Generating loopback configs ===")
    db = stage / "db"
    content = stage / "content"
    # mangosd.conf — minimal keys. SQLite info strings are bare file paths.
    # Loopback bind (DECISIONS #9). Console/RA/SOAP off (no console control).
    # vmaps/mmaps off (client data absent; not needed for DB-layer health).
    mangosd_conf = f"""\
# Pocket Realm generated mangosd.conf (O04 loopback test). Minimal keys only.
RealmID = 1
DataDir = "{dev_content}/"
# SQLite: the info string is passed straight to sqlite3_open (a file path).
WorldDatabaseInfo = "{dev_db}/mangos.sqlite"
CharacterDatabaseInfo = "{dev_db}/characters.sqlite"
LoginDatabaseInfo = "{dev_db}/realmd.sqlite"
LogsDatabaseInfo = "{dev_db}/logs.sqlite"
LoginDatabaseConnections = 1
WorldDatabaseConnections = 1
CharacterDatabaseConnections = 1
LogsDatabaseConnections = 1
WorldServerPort = 8085
BindIP = "127.0.0.1"
Network.Threads = 1
Console.Enable = 0
Ra.Enable = 0
SOAP.Enabled = 0
vmap.enableLOS = 0
vmap.enableHeight = 0
vmap.enableIndoorCheck = 0
mmap.enabled = 0
MaxCoreStuckTime = 0
"""
    realmd_conf = f"""\
# Pocket Realm generated realmd.conf (O04 loopback test). Minimal keys only.
LoginDatabaseInfo = "{dev_db}/realmd.sqlite"
RealmServerPort = 3724
BindIP = "127.0.0.1"
RealmsStateUpdateDelay = 20
ListenerThreads = 1
"""
    (stage / "mangosd.conf").write_text(mangosd_conf)
    (stage / "realmd.conf").write_text(realmd_conf)
    print(f"  configs written to {stage}")

    # 2. Push to device.
    print(f"=== Pushing to device ({dev_root}) ===")
    run(adb + ["shell", f"rm -rf {dev_root}"])
    run(adb + ["shell", f"mkdir -p {dev_root}/content/dbc {dev_root}/content/maps {dev_root}/db"])

    # Strip the .so for push (481MB unstripped -> ~manageable).
    llvm_strip = (NATIVE / ".deps").glob(f"prefix-{deps_suffix}/bin/llvm-strip")
    # Use the NDK strip instead.
    ndk_strip = str(Path(os.environ["ANDROID_SDK_ROOT"]) / "ndk-link" / "toolchains" /
                    "llvm" / "prebuilt" / "windows-x86_64" / "bin" / "llvm-strip.exe")
    stripped_lib = stage / "libpocketrealm.stripped.so"
    shutil.copy(lib, stripped_lib)
    subprocess.run([ndk_strip, "--strip-debug", str(stripped_lib)])
    stripped_test = stage / "pocket_lifecycle_test.stripped"
    shutil.copy(test_bin, stripped_test)
    subprocess.run([ndk_strip, str(stripped_test)])

    run(adb + ["push", str(stripped_lib), f"{dev_root}/libpocketrealm.so"])
    run(adb + ["push", str(stripped_test), f"{dev_root}/pocket_lifecycle_test"])
    # adb push from Windows does not preserve the executable bit; set it.
    run(adb + ["shell", f"chmod 755 {dev_root}/pocket_lifecycle_test"])

    # The test binary + .so link libc++_shared (the NDK STL), which isn't on the
    # emulator's default lib path. Push it next to the .so so LD_LIBRARY_PATH
    # resolves it. (mirrors smoke_native.py's libc++_shared push.)
    ndk_lib = (Path(os.environ["ANDROID_SDK_ROOT"]) / "ndk-link" / "toolchains" /
               "llvm" / "prebuilt" / "windows-x86_64" / "sysroot" / "usr" / "lib" /
               f"{triple}-linux-android" / "libc++_shared.so")
    if ndk_lib.is_file():
        run(adb + ["push", str(ndk_lib), f"{dev_root}/libc++_shared.so"])
    else:
        print(f"WARN: libc++_shared.so not found at {ndk_lib}", file=sys.stderr)
    run(adb + ["push", str(stage / "mangosd.conf"), f"{dev_root}/mangosd.conf"])
    run(adb + ["push", str(stage / "realmd.conf"), f"{dev_root}/realmd.conf"])
    for dbf in ("mangos.sqlite", "characters.sqlite", "realmd.sqlite", "logs.sqlite"):
        run(adb + ["push", str(db / dbf), f"{dev_root}/db/{dbf}"])

    # 3. Run the test on device. LD_LIBRARY_PATH so the test binary finds the .so.
    print("=== Running pocket_lifecycle_test on device ===")
    env = "LD_LIBRARY_PATH=" + dev_root
    r = run(adb + ["shell", env, f"{dev_root}/pocket_lifecycle_test",
                   f"{dev_root}/mangosd.conf", f"{dev_root}/realmd.conf",
                   f"{dev_root}/content/", f"{dev_root}/db/"])
    rc = r.returncode
    print(f"\n=== native test exit code: {rc} ===")
    if rc == 0:
        print("NATIVE LIFECYCLE TEST: PASS")
    else:
        print("NATIVE LIFECYCLE TEST: FAIL", file=sys.stderr)

    if not args.keep:
        run(adb + ["shell", f"rm -rf {dev_root}"])
    return rc


if __name__ == "__main__":
    sys.exit(main())
