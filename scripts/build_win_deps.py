#!/usr/bin/env python3
"""Stage the Pocket Realm Windows native dependencies.

Clones/updates vcpkg at a pinned commit under native/.deps/vcpkg, installs
the native/win-deps/vcpkg.json manifest for the x64-windows-static-md
triplet (static libs linked against the dynamic /MD CRT — the JNI DLLs
load inside the app JVM, which requires the same CRT family), and
compiles the repo-pinned SQLite 3.46.1 amalgamation with cl.exe into the
same prefix.

Everything lands in native/.deps/prefix-win-x86_64 (gitignored), laid out
CMake-consumable: include/ + lib/ + share/. The OpenSSL legacy provider is
part of the static libcrypto (realmd loads OSSL_PROVIDER "legacy" at
startup; the smoke step below verifies that it loads).
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VCPKG_DIR = ROOT / "native" / ".deps" / "vcpkg"
MANIFEST = ROOT / "native" / "win-deps" / "vcpkg.json"
PREFIX = ROOT / "native" / ".deps" / "prefix-win-x86_64"
SQLITE_SRC = ROOT / "native" / ".deps" / "src" / "sqlite" / "sqlite-amalgamation-3460100"
TRIPLET = "x64-windows-static-md"

# Pinned vcpkg commit. Bump deliberately, then rebuild the dependencies and
# regenerate the lockfile from scratch.
VCPKG_COMMIT = "784e1b71001e5dc405af24e860a5c4bc193ea6cf"

VSWHERE = Path(
    os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)")
) / "Microsoft Visual Studio" / "Installer" / "vswhere.exe"

# The pinned amalgamation's reported version; the smoke asserts it so a
# stale sqlite3.lib (or a wrong pin) fails here instead of shipping.
SQLITE_VERSION = "3.46.1"


def sha256_of(path: Path) -> str:
    import hashlib
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(cmd, **kwargs):
    print(f"+ {' '.join(str(c) for c in cmd)}", flush=True)
    return subprocess.run([str(c) for c in cmd], check=True, **kwargs)


def find_vcvars() -> Path:
    found = subprocess.run(
        [str(VSWHERE), "-latest", "-products", "*", "-property", "installationPath"],
        capture_output=True, text=True, check=True, timeout=30)
    install = (found.stdout or "").strip().splitlines()
    assert install, "vswhere found no Visual Studio installation"
    vcvars = Path(install[0]) / "VC" / "Auxiliary" / "Build" / "vcvars64.bat"
    assert vcvars.is_file(), f"vcvars64.bat missing under {install[0]}"
    return vcvars


def in_msvc_env(vcvars: Path, workdir: Path, body: str, log_name: str) -> int:
    """Run `body` (batch snippet) under vcvars64 in workdir; return exit code."""
    script = workdir / log_name
    # write_bytes: text mode would translate each \r\n to \r\r\n on Windows,
    # which cmd.exe tolerates for simple lines but breaks goto/label blocks.
    script.write_bytes(
        (
            "@echo off\r\n"
            f'call "{vcvars}" >nul 2>&1\r\n'
            f"cd /d {workdir}\r\n"
            f"{body}\r\n"
            "exit /b %ERRORLEVEL%\r\n"
        ).encode("ascii")
    )
    return subprocess.run(["cmd", "/c", str(script)]).returncode


def ensure_vcpkg() -> str:
    if not (VCPKG_DIR / ".git").is_dir():
        run(["git", "clone", "https://github.com/microsoft/vcpkg.git", VCPKG_DIR])
    current = subprocess.run(
        ["git", "-C", VCPKG_DIR, "rev-parse", "HEAD"],
        capture_output=True, text=True, check=True).stdout.strip()
    if VCPKG_COMMIT != "PINNED_AT_FIRST_BOOTSTRAP" and current != VCPKG_COMMIT:
        run(["git", "-C", VCPKG_DIR, "fetch", "--depth", "1", "origin", VCPKG_COMMIT])
        run(["git", "-C", VCPKG_DIR, "checkout", VCPKG_COMMIT])
        # Re-read: the earlier rev-parse describes the stale pre-checkout
        # state and would misreport the lane's provenance.
        current = subprocess.run(
            ["git", "-C", VCPKG_DIR, "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True).stdout.strip()
        assert current == VCPKG_COMMIT, f"vcpkg checkout landed on {current}, not the pin"
    if not (VCPKG_DIR / "vcpkg.exe").is_file():
        run([VCPKG_DIR / "bootstrap-vcpkg.bat", "-disableMetrics"])
    return current


def install_manifest() -> None:
    # NOTE: this vcpkg generation parses the single-dash legacy metrics flag
    # as a package name; manifest mode takes no package arguments.
    run([
        VCPKG_DIR / "vcpkg.exe", "install",
        "--triplet", TRIPLET,
    ], cwd=MANIFEST.parent)


def build_sqlite(vcvars: Path) -> None:
    stamp = PREFIX / "lib" / "sqlite3.lib.amalgamation-sha256"
    amalgamation = SQLITE_SRC / "sqlite3.c"
    if (PREFIX / "lib" / "sqlite3.lib").is_file():
        # A stale lib from an older amalgamation pin must not silently
        # persist: the stamp records the exact sqlite3.c the lib was
        # built from and is re-verified on every run.
        if stamp.is_file() and amalgamation.is_file() \
                and stamp.read_text(encoding="ascii").strip() == sha256_of(amalgamation):
            print("sqlite3.lib already staged (amalgamation sha verified)")
            return
        print("sqlite3.lib staged from a different amalgamation; rebuilding")
    assert SQLITE_SRC.is_dir(), (
        f"{SQLITE_SRC} missing — stage the pinned amalgamation first "
        "(tools/stage_sqlite_amalgamation.py)"
    )
    out = SQLITE_SRC / "build-win"
    out.mkdir(exist_ok=True)
    body = (
        f'cl /nologo /MD /O2 /Zi /W3 /utf-8 /c /I"{SQLITE_SRC}" '
        f'/Fo"{out}\\sqlite3.obj" "{SQLITE_SRC}\\sqlite3.c"\r\n'
        f'lib /NOLOGO /OUT:"{out}\\sqlite3.lib" "{out}\\sqlite3.obj"\r\n'
    )
    code = in_msvc_env(vcvars, out, body, "build_sqlite.bat")
    assert code == 0, f"sqlite3 cl build failed (exit {code})"
    (PREFIX / "include").mkdir(parents=True, exist_ok=True)
    (PREFIX / "lib").mkdir(parents=True, exist_ok=True)
    for name in ("sqlite3.h", "sqlite3ext.h"):
        target = PREFIX / "include" / name
        target.write_bytes((SQLITE_SRC / name).read_bytes())
    (PREFIX / "lib" / "sqlite3.lib").write_bytes((out / "sqlite3.lib").read_bytes())
    stamp.write_text(sha256_of(amalgamation) + "\n", encoding="ascii")
    print(f"sqlite3 staged into {PREFIX}")


def smoke(vcvars: Path) -> None:
    """Link a tiny program against OpenSSL+Boost+zlib+sqlite from the prefix."""
    work = PREFIX / "smoke"
    work.mkdir(exist_ok=True)
    main = work / "smoke.cpp"
    main.write_text(
        "#include <openssl/evp.h>\n"
        "#include <openssl/provider.h>\n"
        "#include <boost/filesystem.hpp>\n"
        "#include <zlib.h>\n"
        "#include <sqlite3.h>\n"
        "#include <cstdio>\n"
        "int main() {\n"
        "    if (OSSL_PROVIDER_load(nullptr, \"legacy\") == nullptr) { "
        "std::printf(\"legacy provider FAIL\\n\"); return 1; }\n"
        "    std::printf(\"openssl %s\\n\", OpenSSL_version(OPENSSL_VERSION));\n"
        "    std::printf(\"boost %s\\n\", BOOST_LIB_VERSION);\n"
        "    std::printf(\"zlib %s\\n\", zlibVersion());\n"
        "    std::printf(\"sqlite %s\\n\", sqlite3_libversion());\n"
        "    boost::filesystem::path p = boost::filesystem::current_path();\n"
        "    std::printf(\"cwd exists: %d\\n\", p.is_absolute() ? 1 : 0);\n"
        "    return 0;\n"
        "}\n",
        encoding="utf-8",
    )
    vcpkg = MANIFEST.parent / "vcpkg_installed" / TRIPLET
    lib_dir = vcpkg / "lib"

    def boost_lib(component: str) -> str:
        matches = sorted(lib_dir.glob(f"boost_{component}-*.lib"))
        assert matches, f"no boost_{component} lib under {lib_dir}"
        return matches[0].name

    # vcpkg names carry the toolset+version tags (boost_filesystem-vc145-
    # mt-x64-1_92.lib) and zlib is zs.lib in this generation; discover the
    # boost names instead of hardcoding them.
    libs = " ".join([
        "libcrypto.lib", "libssl.lib", "zs.lib",
        boost_lib("filesystem"), boost_lib("program_options"),
        boost_lib("serialization"), boost_lib("thread"),
        "sqlite3.lib",
    ])
    body = (
        f'cl /nologo /MD /EHsc /std:c++17 /utf-8 '
        f'/I"{vcpkg}\\include" /I"{PREFIX}\\include" '
        f'"{main}" /Fe:"{work}\\smoke.exe" '
        f'/link /LIBPATH:"{vcpkg}\\lib" /LIBPATH:"{PREFIX}\\lib" '
        f"{libs} advapi32.lib ws2_32.lib crypt32.lib user32.lib "
        f"> \"{work}\\smoke-build.log\" 2>&1\r\n"
        f'if exist "{work}\\smoke.exe" "{work}\\smoke.exe" '
        f"> \"{work}\\smoke-run.log\" 2>&1\r\n"
    )
    code = in_msvc_env(vcvars, work, body, "smoke.bat")
    assert code == 0, f"dependency smoke failed (exit {code})"
    run_log = (work / "smoke-run.log").read_text(encoding="utf-8", errors="replace")
    assert f"sqlite {SQLITE_VERSION}" in run_log, (
        f"staged sqlite3 is not the pinned {SQLITE_VERSION}: "
        f"{[line for line in run_log.splitlines() if 'sqlite' in line.lower()]} "
        "(rebuild the prefix with a fresh amalgamation)"
    )
    print("dependency smoke: OK (legacy provider loads, sqlite pin verified)")


def main() -> int:
    if os.name != "nt":
        print("windows-only lane", file=sys.stderr)
        return 2
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-smoke", action="store_true")
    args = parser.parse_args()
    commit = ensure_vcpkg()
    print(f"vcpkg at {commit}")
    install_manifest()
    vcvars = find_vcvars()
    build_sqlite(vcvars)
    if VCPKG_COMMIT == "PINNED_AT_FIRST_BOOTSTRAP":
        print(
            "ACTION: pin this run's vcpkg commit in scripts/build_win_deps.py "
            f"VCPKG_COMMIT = \"{commit}\""
        )
    if not args.skip_smoke:
        smoke(vcvars)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
