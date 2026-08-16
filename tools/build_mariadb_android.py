#!/usr/bin/env python3
"""Source-build the pinned x86_64 MariaDB Android/glibc provider for O08.

The build deliberately reuses the exact Termux-glibc repository, Termux build
harness, CGCT image, and 16 KB linker policy already qualified by O06. MariaDB
runs natively on the x86_64 CPU in the APK's private glibc namespace; Android's
Bionic process is only the supervisor/launcher.

Generated archives stay under native/.glibc-build and native/.build-x86_64.
They are reproducible build inputs, not source-controlled binaries.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import build_glibc_closure as closure


ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
BUILD_ROOT = ROOT / "native" / ".build-x86_64" / "mariadb"
PROVENANCE = BUILD_ROOT / "BUILD_PROVENANCE.json"
PACKAGE_VERSION = "11.5.2"
SOURCE_URL = (
    "https://archive.mariadb.org/"
    f"mariadb-{PACKAGE_VERSION}/source/mariadb-{PACKAGE_VERSION}.tar.gz"
)
SOURCE_SHA256 = "e25fac00aeb34610faf62182836a14e3310c0ca5d882e9109f63bd8dfdc3542d"
LIBSTDCXX_NAME = "libstdc++.so.6.0.35"
LIBSTDCXX_SHA256 = "5a8695ed68f47c5bd087ee374a1ea0ed542b9c4dfc9eb3e870668ba8f6889bfa"
GCC_SOURCE_SHA256 = "50efb4d94c3397aff3b0d61a5abd748b4dd31d9d3f2ab7be05b171d36a510f79"


sha256 = common.sha256_file
def validate_recipe(repo: Path) -> Path:
    recipe = repo / "gpkg" / "mariadb" / "build.sh"
    text = recipe.read_text(encoding="utf-8")
    anchors = (
        f'TERMUX_PKG_VERSION="{PACKAGE_VERSION}"',
        f"TERMUX_PKG_SHA256={SOURCE_SHA256}",
        "-DWITH_EMBEDDED_SERVER=ON",
        "-DWITH_SYSTEMD=no",
        "-DWITH_UNIT_TESTS=OFF",
    )
    missing = [anchor for anchor in anchors if anchor not in text]
    if missing:
        raise RuntimeError(f"pinned MariaDB recipe drift: {missing}")
    # The recipe's NetCologne mirror no longer carries this historical release
    # (HTTP 404). Use MariaDB's official archive for the byte-identical tarball;
    # the recipe's immutable SHA-256 remains the authority.
    old_url = (
        "TERMUX_PKG_SRCURL=https://mirror.netcologne.de/mariadb/"
        "mariadb-${TERMUX_PKG_VERSION}/source/mariadb-${TERMUX_PKG_VERSION}.tar.gz"
    )
    new_url = (
        "TERMUX_PKG_SRCURL=https://archive.mariadb.org/"
        "mariadb-${TERMUX_PKG_VERSION}/source/mariadb-${TERMUX_PKG_VERSION}.tar.gz"
    )
    if old_url in text:
        text = text.replace(old_url, new_url)
    elif new_url not in text:
        raise RuntimeError("MariaDB source URL recipe shape drift")
    # The pinned builder now supplies CMake 4.4, which removed implicit policy
    # compatibility below 3.5. MariaDB 11.5.2's top-level minimum predates that
    # removal; select the documented compatibility floor without changing any
    # MariaDB source or feature option.
    compatibility_args = (
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        # MariaDB's K&R-style signal() probe is rejected by GCC 16 even though
        # glibc's handler return type is unambiguously void.
        "-DSIGNAL_RETURN_TYPE_IS_VOID=1",
        # MariaDB runs target-native generators during Ninja. CMake applies
        # this only to build-tree executables and removes it during install;
        # staging additionally rejects any leaked CGCT path.
        "-DCMAKE_BUILD_RPATH=/data/data/com.termux/cgct/x86_64/lib",
    )
    if any(argument not in text for argument in compatibility_args):
        marker = 'TERMUX_PKG_EXTRA_CONFIGURE_ARGS="\n'
        if marker not in text:
            raise RuntimeError("MariaDB configure-args recipe shape drift")
        additions = "\n".join(argument for argument in compatibility_args if argument not in text)
        text = text.replace(marker, marker + additions + "\n", 1)
    # Remove the abandoned LD_LIBRARY_PATH experiment if this idempotent
    # overlay is rerun in an existing generated worktree. It contaminated
    # builder tools with target glibc; the CMake build RPATH above is narrower.
    text = text.replace(
        '\tmkdir -p "$TERMUX_PKG_BUILDDIR/.generator-runtime"\n'
        '\tln -sf /data/data/com.termux/cgct/x86_64/lib/libstdc++.so.6 '
        '"$TERMUX_PKG_BUILDDIR/.generator-runtime/libstdc++.so.6"\n'
        '\texport LD_LIBRARY_PATH="$TERMUX_PKG_BUILDDIR/.generator-runtime'
        '${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"\n',
        "",
    )
    text = text.replace(
        '\texport LD_LIBRARY_PATH="/data/data/com.termux/cgct/x86_64/lib:'
        '$TERMUX_PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"\n',
        "",
    )
    # MariaDB 11.5.2 selects GCC's short-lived `evex512` function target for
    # __GNUC__ >= 14. GCC 16 removed that target spelling while retaining the
    # explicit AVX-512 feature targets that follow it. Apply the minimal
    # forward-compiler compatibility change to the unpacked, hash-verified
    # source; no CRC implementation or runtime dispatch logic is changed.
    post_get_source = """

termux_step_post_get_source() {
	local crc_source="$TERMUX_PKG_SRCDIR/mysys/crc32/crc32c_x86.cc"
	local old_target='pclmul,evex512,avx512f,avx512dq,avx512bw,avx512vl,vpclmulqdq'
	local new_target='pclmul,avx512f,avx512dq,avx512bw,avx512vl,vpclmulqdq'
	grep -Fq "$old_target" "$crc_source" || {
		echo "MariaDB CRC target overlay anchor drift" >&2
		return 1
	}
	sed -i "s/$old_target/$new_target/" "$crc_source"
}
"""
    if "termux_step_post_get_source()" not in text:
        text += post_get_source
    elif "MariaDB CRC target overlay anchor drift" not in text:
        raise RuntimeError("MariaDB post-get-source recipe shape drift")
    recipe.write_text(text, encoding="utf-8", newline="\n")
    return recipe


def find_archive(output: Path) -> Path:
    candidates = sorted(
        path for path in output.glob("mariadb-glibc-*-x86_64.pkg.tar.*")
        if not path.name.startswith("mariadb-glibc-static-")
    )
    if len(candidates) != 1:
        raise RuntimeError(f"expected one MariaDB archive, found: {candidates}")
    return candidates[0]


def extract_pinned_libstdcxx() -> Path:
    """Extract the compiler-matched C++ runtime from the pinned CGCT image."""
    BUILD_ROOT.mkdir(parents=True, exist_ok=True)
    target = BUILD_ROOT / LIBSTDCXX_NAME
    mount = closure._win_to_docker_mount(BUILD_ROOT)  # same mount policy as the build harness
    env = dict(**os.environ, MSYS_NO_PATHCONV="1")
    subprocess.run(
        [
            "docker", "run", "--rm", "-v", f"{mount}:/out", closure.CGCT_IMAGE,
            "cp", f"/data/data/com.termux/cgct/x86_64/lib/{LIBSTDCXX_NAME}",
            f"/out/{LIBSTDCXX_NAME}",
        ],
        check=True,
        env=env,
    )
    actual = sha256(target)
    if actual != LIBSTDCXX_SHA256:
        target.unlink(missing_ok=True)
        raise RuntimeError(f"pinned CGCT libstdc++ hash drift: {actual}")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--no-build", action="store_true")
    args = parser.parse_args()

    pin = closure.load_glibc_packages_pin()
    repo = closure.WORK_ROOT / "glibc-packages"
    print("=== O08 MariaDB x86_64 source build ===")
    print(f"  source : MariaDB {PACKAGE_VERSION} ({SOURCE_SHA256[:16]}...)")
    print(f"  recipe : termux-pacman/glibc-packages @ {pin['commit']}")
    print(f"  harness: termux/termux-packages @ {closure.TERMUX_PACKAGES_HARNESS_COMMIT}")
    print(f"  image  : {closure.CGCT_IMAGE}")
    if args.no_build:
        return 0 if closure.ensure_docker() else 1
    if not closure.ensure_docker():
        return 1

    closure.clone_glibc_packages(pin["commit"], repo)
    harness_commit = closure.vendor_build_harness(repo)
    closure.apply_16k_linker_overlay(repo)
    recipe = validate_recipe(repo)
    output = closure.build_packages(repo, "x86_64", ["mariadb"], force=args.force)
    archive = find_archive(output)

    BUILD_ROOT.mkdir(parents=True, exist_ok=True)
    staged = BUILD_ROOT / archive.name
    shutil.copy2(archive, staged)
    libstdcxx = extract_pinned_libstdcxx()
    record = {
        "schema": 1,
        "built_at_utc": datetime.now(timezone.utc).isoformat(),
        "arch": "x86_64",
        "elf_max_page_size": closure.ELF_MAX_PAGE_SIZE,
        "mariadb": {
            "version": PACKAGE_VERSION,
            "source_url": SOURCE_URL,
            "source_sha256": SOURCE_SHA256,
            "license": "GPL-2.0-only",
        },
        "glibc_packages_commit": pin["commit"],
        "recipe_sha256": sha256(recipe),
        "termux_packages_harness_commit": harness_commit,
        "builder_image": closure.CGCT_IMAGE,
        "toolchain_runtime": {
            "path": libstdcxx.relative_to(ROOT).as_posix(),
            "size": libstdcxx.stat().st_size,
            "sha256": sha256(libstdcxx),
            "soname": "libstdc++.so.6",
            "gcc_version": "16.1.0",
            "gcc_source_url": "https://ftp.gnu.org/gnu/gcc/gcc-16.1.0/gcc-16.1.0.tar.xz",
            "gcc_source_sha256": GCC_SOURCE_SHA256,
            "license": "GPL-3.0-or-later WITH GCC-exception-3.1",
        },
        "archive": {
            "path": staged.relative_to(ROOT).as_posix(),
            "size": staged.stat().st_size,
            "sha256": sha256(staged),
        },
    }
    PROVENANCE.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(record, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
