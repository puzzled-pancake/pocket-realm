#!/usr/bin/env python3
"""Source-build the x86_64 glibc-side runtime closure for the O06 Wine runtime,
by driving the REAL Termux build system (not a re-implementation) against the
pinned commits, then importing + hashing the output packages.

Why this drives the real harness: the gpkg/*/build.sh scripts override
termux_step_* functions and require the full termux-packages build system
( TERMUX_PKG_SRCDIR, CC, termux_step_* driver, CGCT, proot ). They cannot run
on MSYS2 (the glibc recipe aborts if the host libc is not glibc), and
re-implementing the harness standalone is infeasible (CGCT + the Android patch
plumbing). So we run it in the official cgct Docker container.

Procedure (mirrors termux-pacman/glibc-packages CI):
  1. Clone termux-pacman/glibc-packages at the pinned commit (sources.json).
  2. Vendor the build harness from an exact termux/termux-packages commit.
  3. Build each lockfile package for x86_64 via run-docker.sh + build-package.sh
     in the ghcr.io/termux/package-builder-cgct container.
  4. Import the output .pkg.tar.* into runtime/glibc-rootfs-x86_64/, extract the
     loader + libs, and hash every file into the lockfile.

This is the production/distribution build. For the SPIKE it may be substituted by
a hash-verified prebuilt closure (same pattern as the Wine provider), but this
script is the authoritative source-reproduction recipe.

Prerequisites:
  - Docker daemon running (Docker Desktop on Windows).
  - Network access (first run pulls the cgct image; --no-build dry-runs the plan).

Usage:
  python3 tools/build_glibc_closure.py                 # full build
  python3 tools/build_glibc_closure.py --arch x86_64   # default
  python3 tools/build_glibc_closure.py --no-build      # print the plan, don't run
  python3 tools/build_glibc_closure.py --packages glibc,libx11   # subset
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCKFILE = ROOT / "schemas" / "wine-runtime-lockfile.json"
SOURCES = ROOT / "schemas" / "sources.json"
WORK_ROOT = ROOT / "native" / ".glibc-build"          # gitignored build workspace
OUTPUT_ROOT = ROOT / "runtime" / "glibc-rootfs-x86_64"  # final staged closure

GLIBC_PACKAGES_ID = "termux-glibc-packages-c9d4de5"
CGCT_IMAGE = (
    "ghcr.io/termux/package-builder-cgct@"
    "sha256:69ffa5cfe02ca569e7d03d1c99e3c9a0f79390ad6bf11a3629d048c29c6ccb61"
)
TERMUX_PACKAGES_HARNESS_COMMIT = "d9188dcf2517ec5f2ed82e021b3cd14c402c74ec"
GCC_LIBS_RECIPE_COMMIT = "845d0e313f53535de28abba0fb42b07e960cc031"
GLIBC_OVERLAY_DIR = ROOT / "native" / "wine-spike" / "glibc"
LOCAL_BOOTSTRAP_PACKAGES = {
    "gcc-libs", "libx11", "libxcb", "libxau", "libxdmcp", "libxext", "freetype",
    "fontconfig", "zlib", "libbz2", "libpng", "brotli", "libexpat",
    "xorg-util-macros", "xorgproto", "xtrans", "xcb-proto",
}
ELF_MAX_PAGE_SIZE = "0x4000"
BUILD_ORDER = (
    "glibc", "gcc-libs", "xorg-util-macros", "xorgproto", "xtrans",
    "xcb-proto", "libxau", "libxdmcp", "libxcb", "libx11", "libxext",
    "zlib", "libbz2", "libpng", "brotli", "freetype", "libexpat",
    "fontconfig",
)

def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_glibc_packages_pin() -> dict:
    data = json.loads(SOURCES.read_text(encoding="utf-8"))
    for s in data["sources"]:
        if s.get("id") == GLIBC_PACKAGES_ID:
            return s
    raise RuntimeError(f"{GLIBC_PACKAGES_ID} not in sources.json")


def load_lockfile_packages() -> list[dict]:
    return json.loads(LOCKFILE.read_text(encoding="utf-8"))["packages"]


def run(cmd: list[str], cwd: Path | None = None, check: bool = True,
        env: dict | None = None) -> subprocess.CompletedProcess:
    print(f"  $ {' '.join(cmd)}" + (f"   (cwd={cwd})" if cwd else ""))
    return subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=check, env=env)


def remove_tree(path: Path) -> None:
    """Remove a disposable checkout, including Git's read-only pack files."""
    def make_writable_and_retry(func, failed_path, _exc):
        os.chmod(failed_path, stat.S_IWRITE)
        func(failed_path)

    shutil.rmtree(path, onexc=make_writable_and_retry)


def _win_to_docker_mount(path: Path) -> str:
    """Convert a Windows path to the //c/... double-slash Docker Desktop mount
    form (lowercase drive, NO colon — a colon makes Docker parse the volume as a
    3-part "src:dst:mode" spec and reject the destination as "invalid mode")."""
    win_path = str(path).replace("\\", "/")
    if len(win_path) >= 2 and win_path[1] == ":":
        win_path = win_path[0].lower() + win_path[2:]
    return "//" + win_path


def ensure_docker() -> bool:
    try:
        r = subprocess.run(["docker", "version", "--format", "{{.Server.Version}}"],
                           capture_output=True, text=True, timeout=30)
        if r.returncode == 0 and r.stdout.strip():
            print(f"Docker daemon: {r.stdout.strip()}")
            return True
    except (subprocess.SubprocessError, FileNotFoundError):
        pass
    print("ERROR: Docker daemon not reachable. Start Docker Desktop first.", file=sys.stderr)
    return False


def clone_glibc_packages(commit: str, dest: Path) -> None:
    """Clone termux-pacman/glibc-packages at the pinned commit (full clone, then
    checkout — the commit may be recent enough that --depth 1 misses it)."""
    if (dest / ".git").is_dir():
        print(f"  (already cloned at {dest})")
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    run(["git", "clone", "https://github.com/termux-pacman/glibc-packages.git", str(dest)])
    run(["git", "-C", str(dest), "checkout", commit])


def apply_gcc_libs_recipe_pin(repo: Path) -> None:
    """Use the recorded GCC 16.1 recipe commit for this package only."""
    run(["git", "-C", str(repo), "checkout", GCC_LIBS_RECIPE_COMMIT, "--",
         "gpkg/gcc-libs"])
    recipe = repo / "gpkg" / "gcc-libs" / "build.sh"
    text = recipe.read_text(encoding="utf-8")
    old = "termux_step_make() {\n\tmake\n}"
    new = "termux_step_make() {\n\tmake -j8 all-target-libgcc\n}"
    if text.count(old) != 1:
        raise RuntimeError("pinned gcc-libs serial-make anchor missing or ambiguous")
    text = text.replace(old, new)
    build_dep_old = 'TERMUX_PKG_BUILD_DEPENDS="doxygen-glibc"'
    build_dep_new = '# TERMUX_PKG_BUILD_DEPENDS omitted: Doxygen is not needed for runtime libraries'
    if text.count(build_dep_old) != 1:
        raise RuntimeError("pinned gcc-libs Doxygen dependency anchor missing or ambiguous")
    text = text.replace(build_dep_old, build_dep_new)
    prereq_anchor = "termux_step_pre_configure() {\n"
    prereq_insert = (
        "termux_step_pre_configure() {\n"
        "\t(cd \"$TERMUX_PKG_SRCDIR\" && ./contrib/download_prerequisites)\n"
    )
    if text.count(prereq_anchor) != 1:
        raise RuntimeError("pinned gcc-libs pre-configure anchor missing or ambiguous")
    text = text.replace(prereq_anchor, prereq_insert)
    external_prereqs = (
        "\t\t--with-gmp=$TERMUX_PREFIX \\\n"
        "\t\t--with-mpfr=$TERMUX_PREFIX \\\n"
        "\t\t--with-mpc=$TERMUX_PREFIX \\\n"
    )
    if text.count(external_prereqs) != 1:
        raise RuntimeError("pinned gcc-libs external-prerequisite anchors missing or ambiguous")
    text = text.replace(external_prereqs, "")
    languages_old = "\t\t--enable-languages=c,c++,fortran \\\n"
    languages_new = "\t\t--enable-languages=c \\\n"
    if text.count(languages_old) != 1:
        raise RuntimeError("pinned gcc-libs language anchor missing or ambiguous")
    text = text.replace(languages_old, languages_new)
    linker_old = "\t\tLD_FOR_TARGET=$TERMUX_PREFIX/bin/ld || (cat config.log && exit 1)"
    linker_new = (
        "\t\tLD_FOR_TARGET=$LD \\\n"
        "\t\tLDFLAGS_FOR_TARGET=\"$LDFLAGS\" || (cat config.log && exit 1)"
    )
    if text.count(linker_old) != 1:
        raise RuntimeError("pinned gcc-libs target-linker anchor missing or ambiguous")
    text = text.replace(linker_old, linker_new)
    install_anchor = "termux_step_make_install() {"
    if text.count(install_anchor) != 1 or not text.rstrip().endswith("}"):
        raise RuntimeError("pinned gcc-libs install function missing or ambiguous")
    install_start = text.index(install_anchor)
    text = text[:install_start] + (
        "termux_step_make_install() {\n"
        "\tmake -C $TERMUX_HOST_PLATFORM/libgcc install-shared\n"
        "}\n"
    )
    recipe.write_text(text, encoding="utf-8", newline="\n")
    print(f"  pinned gcc-libs recipe at {GCC_LIBS_RECIPE_COMMIT}")
    print("  narrowed gcc-libs to the C/libgcc target with bounded parallelism")
    print("  removed gcc-libs' documentation-only Doxygen build dependency")
    print("  selected GCC's checksum-verified in-tree GMP/MPFR/MPC prerequisites")


def apply_android_syscall_overlays(repo: Path) -> None:
    """Apply the project-owned additions to the pinned Termux glibc recipe.

    Android's x86_64 app seccomp policy blocks poll(2).  Termux's glibc recipe
    already removes blocked syscall numbers from arch-syscall.h and supplies
    compatible implementations through fakesyscall.json.  Add poll to that
    same mechanism so glibc's private NSS/resolver calls use ppoll(2), not just
    public calls that an LD_PRELOAD shim can interpose.

    Every textual edit is signature-locked.  This deliberately fails when the
    pinned upstream recipe changes instead of silently producing an unpatched
    libc.
    """
    recipe = repo / "gpkg" / "glibc"
    source = GLIBC_OVERLAY_DIR / "fake_poll.c"
    if not source.is_file():
        raise RuntimeError(f"missing glibc overlay: {source}")
    shutil.copy2(source, recipe / "fake_poll.c")

    config_path = recipe / "fakesyscall.json"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    key = "fake_poll((struct pollfd *)a0, a1, a2)"
    existing = config.get(key)
    if existing not in (None, ["poll"]):
        raise RuntimeError(f"unexpected existing poll fakesyscall: {existing!r}")
    config[key] = ["poll"]
    config_path.write_text(json.dumps(config, indent="\t") + "\n", encoding="utf-8", newline="\n")

    base_path = recipe / "fakesyscall-base.h"
    base = base_path.read_text(encoding="utf-8")
    include = '// fake_poll\n#include "fake_poll.c"'
    if include not in base:
        anchor = '// fake_epoll_pwait2\n#include "fake_epoll_pwait2.c"'
        if base.count(anchor) != 1:
            raise RuntimeError("pinned fakesyscall-base.h include anchor missing or ambiguous")
        base = base.replace(anchor, anchor + "\n\n" + include)
        base_path.write_text(base, encoding="utf-8", newline="\n")

    build_path = recipe / "build.sh"
    build = build_path.read_text(encoding="utf-8")
    old = "fake_epoll_pwait2.c,setfs{u,g}id.c"
    new = "fake_epoll_pwait2.c,fake_poll.c,setfs{u,g}id.c"
    if new not in build:
        if build.count(old) != 1:
            raise RuntimeError("pinned glibc build.sh copy-list anchor missing or ambiguous")
        build = build.replace(old, new)
    # The selected Wine build uses new-WoW64 and therefore needs no i386 Unix
    # userspace. Do not spend time producing the recipe's glibc32 subpackage or
    # accidentally import it into the x86_64-only closure.
    multilib_old = "TERMUX_PKG_BUILD_MULTILIB=true"
    multilib_new = "TERMUX_PKG_BUILD_MULTILIB=false"
    if multilib_new not in build:
        if build.count(multilib_old) != 1:
            raise RuntimeError("pinned glibc multilib anchor missing or ambiguous")
        build = build.replace(multilib_old, multilib_new)
    build_path.write_text(build, encoding="utf-8", newline="\n")

    print("  applied Android poll(2)->ppoll(2) and x86_64-only glibc recipe overlays")

    # These two upstream recipes depend on target bash only for packaged CLI
    # utilities/scripts. O06 imports the shared libraries exclusively. Keeping
    # that utility-only dependency would make a libbz2/libpng closure rebuild
    # recursively build Bash and (when the mirror lacks gcc-libs 16.1) GCC.
    # Narrow the source recipe to the library dependency surface we distribute.
    dep_edits = {
        "libbz2": (
            'TERMUX_PKG_DEPENDS="glibc, bash-glibc"',
            'TERMUX_PKG_DEPENDS="glibc"',
        ),
        "libpng": (
            'TERMUX_PKG_DEPENDS="zlib-glibc, bash-glibc"',
            'TERMUX_PKG_DEPENDS="zlib-glibc"',
        ),
    }
    for package, (old_dep, new_dep) in dep_edits.items():
        path = repo / "gpkg" / package / "build.sh"
        text = path.read_text(encoding="utf-8")
        if new_dep not in text:
            if text.count(old_dep) != 1:
                raise RuntimeError(f"pinned {package} dependency anchor missing or ambiguous")
            path.write_text(text.replace(old_dep, new_dep), encoding="utf-8", newline="\n")
    print("  applied library-only dependency overlays for libbz2/libpng")

    # xcb-proto is platform-independent build input. The pinned recipe names
    # python${TERMUX_PYTHON_VERSION}, but -s intentionally does not install the
    # target Python package; use the CGCT image's host Python for generation.
    xcb_proto_path = repo / "gpkg" / "xcb-proto" / "build.sh"
    xcb_proto = xcb_proto_path.read_text(encoding="utf-8")
    python_old = "PYTHON=python${TERMUX_PYTHON_VERSION}"
    python_new = "PYTHON=python3"
    if python_new not in xcb_proto:
        if xcb_proto.count(python_old) != 1:
            raise RuntimeError("pinned xcb-proto Python anchor missing or ambiguous")
        xcb_proto_path.write_text(xcb_proto.replace(python_old, python_new),
                                  encoding="utf-8", newline="\n")
    print("  selected host Python for the build-only xcb-proto generator")


def strip_crlf_sh(repo: Path) -> int:
    """Strip CRLF line endings from every .sh file in the repo. Required on
    Windows: git checkout with core.autocrlf=true converts LF->CRLF, and the
    Linux bash inside the cgct container chokes on the trailing \\r
    ("$'\\r': command not found"). Python walker avoids the Git Bash glob
    expansion that breaks find -name '*.sh'."""
    import os
    fixed = 0
    for root, dirs, files in os.walk(repo):
        if ".git" in dirs:
            dirs.remove(".git")
        for fn in files:
            if fn.endswith(".sh"):
                p = Path(root) / fn
                data = p.read_bytes()
                if b"\r\n" in data:
                    p.write_bytes(data.replace(b"\r\n", b"\n"))
                    fixed += 1
    return fixed


def apply_16k_linker_overlay(repo: Path) -> None:
    """Link every glibc-side ELF for both 4 KB and 16 KB kernels.

    The CGCT GNU toolchain defaults x86_64 PT_LOAD segments to 4 KB. Android's
    16 KB kernel rejects those ELFs before the dynamic loader can start. Patch
    the harness's single GNU-toolchain initialization point so glibc and every
    DT_NEEDED closure package use congruent 16 KB segments.
    """
    path = repo / "scripts" / "build" / "toolchain" / "termux_setup_toolchain_gnu.sh"
    text = path.read_text(encoding="utf-8")
    old = 'export LDFLAGS=""'
    new = f'export LDFLAGS="-Wl,-z,max-page-size={ELF_MAX_PAGE_SIZE}"'
    if new not in text:
        if text.count(old) != 1:
            raise RuntimeError("GNU toolchain LDFLAGS anchor missing or ambiguous")
        path.write_text(text.replace(old, new), encoding="utf-8", newline="\n")
    print(f"  applied dual-page-size ELF linker policy (max-page-size={ELF_MAX_PAGE_SIZE})")


def vendor_build_harness(repo: Path) -> str:
    """Vendor the Termux build harness at an exact, reviewable commit."""
    marker = repo / ".termux-packages-harness-commit"
    current = marker.read_text(encoding="ascii").strip() if marker.is_file() else ""
    required = ("build-package.sh", "clean.sh", "packages", "x11-packages",
                "root-packages", "scripts", "ndk-patches")
    if current == TERMUX_PACKAGES_HARNESS_COMMIT and all((repo / p).exists() for p in required):
        print(f"  (build harness already pinned at {TERMUX_PACKAGES_HARNESS_COMMIT})")
    else:
        checkout = repo / ".termux-packages-pinned"
        if checkout.exists():
            remove_tree(checkout)
        checkout.mkdir()
        run(["git", "-C", str(checkout), "init"])
        run(["git", "-C", str(checkout), "remote", "add", "origin",
             "https://github.com/termux/termux-packages.git"])
        run(["git", "-C", str(checkout), "fetch", "--depth", "1", "origin",
             TERMUX_PACKAGES_HARNESS_COMMIT])
        run(["git", "-C", str(checkout), "checkout", "--detach", "FETCH_HEAD"])
        for name in required:
            target = repo / name
            if target.exists():
                if target.is_dir():
                    remove_tree(target)
                else:
                    target.unlink()
            source = checkout / name
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copy2(source, target)
        remove_tree(checkout)
        marker.write_text(TERMUX_PACKAGES_HARNESS_COMMIT + "\n", encoding="ascii")
        print(f"  vendored Termux build harness at {TERMUX_PACKAGES_HARNESS_COMMIT}")
    n = strip_crlf_sh(repo)
    print(f"  stripped CRLF from {n} .sh files (Windows checkout fix)")
    return TERMUX_PACKAGES_HARNESS_COMMIT


def _package_output_exists(out_dir: Path, pkg_name: str, arch: str) -> bool:
    """True if a .pkg.tar.* for this package already exists in output/. We use
    this Python-level skip rather than the harness's /data/data/.built-packages
    stamp mechanism because that stamp dir lives in the container's ephemeral fs
    (lost on --rm), and the harness's own skip only fires for downloaded deps
    or with -f/-F flags. A simple output-file existence check is robust and
    does not depend on container-internal state. (The version embedded in the
    filename comes from the pinned build.sh, so it matches the lockfile.)

    Filename gotcha: the harness adds a '-glibc' suffix to TERMUX_PKG_NAME for
    glibc-library packages, so most archives are '<name>-glibc-<ver>-...'. But
    the glibc/glibc32 provider packages themselves do NOT get the suffix
    (they are already 'glibc'), so their archives are 'glibc-<ver>-...' /
    'glibc32-<ver>-...'. We check both prefix forms."""
    suffixes = (f"-{arch}.pkg.tar.xz", f"-{arch}.pkg.tar.zst",
                "-any.pkg.tar.xz", "-any.pkg.tar.zst")
    # Candidate prefixes: with and without the -glibc suffix.
    prefixes = (f"{pkg_name}-glibc-", f"{pkg_name}-")
    for prefix in prefixes:
        for p in out_dir.glob(f"{prefix}*"):
            if any(p.name.endswith(s) for s in suffixes):
                return True
    return False


def _build_one_package(repo: Path, arch: str, pkg: str, out_dir: Path,
                       install_deps: bool) -> bool:
    """Build a single package in its own --rm container. Returns True on success.

    Per-package isolation means a failure at gcc-libs does NOT throw away the
    60-min glibc build (its .pkg.tar.* is already committed to the mounted
    output/ dir, and the next run skips it via _package_output_exists). This is
    the key resilience property: each package's success is durable.

    .termux-build is intentionally NOT mounted: an earlier attempt bind-mounted
    it to the Windows NTFS host and that BROKE glibc's elf/ld.so link step
    (undefined reference to __lll_lock_wait_private) — glibc's static-archive
    assembly (rtld-libc.a) is sensitive to NTFS mmap/temp-file semantics. The
    container's own overlay fs builds glibc correctly. The cost is that deps
    re-download per container; the benefit is correctness, and -I downloads
    prebuilt deps (fast) rather than rebuilding them."""
    mount_repo = _win_to_docker_mount(repo)
    env = dict(**os.environ, MSYS_NO_PATHCONV="1")
    # -I downloads prebuilt deps instead of building them (we only source-build
    # the 9 lockfile packages themselves); -a selects arch; --format pacman
    # emits .pkg.tar.*; --library glibc selects gpkg/. One package per container.
    flag = "-I" if install_deps else "-s"
    cmd = [
        "docker", "run", "--rm",
        "-v", f"{mount_repo}:/home/builder/termux-packages",
        "--workdir", "/home/builder/termux-packages",
        CGCT_IMAGE,
    ]
    # Import all already source-built packages into the ephemeral prefix before
    # dependency resolution. This matters for gcc-libs-glibc 16.1, which the
    # current mirror metadata references but no longer serves. With -I the
    # harness still downloads every dependency not present locally; with -s it
    # uses the complete preseed and skips resolution as before.
    bootstrap = r'''
set -e
mkdir -p /data/data/.built-packages
for archive in output/*.pkg.tar.xz; do
  [ -f "$archive" ] || continue
  pkginfo=$(tar -xOf "$archive" .PKGINFO 2>/dev/null || true)
  pkgname=$(printf '%s\n' "$pkginfo" | sed -n 's/^pkgname = //p' | head -n 1)
  pkgver=$(printf '%s\n' "$pkginfo" | sed -n 's/^pkgver = //p' | head -n 1)
  # Pacman archive names encode the default package revision as "-0", while
  # Termux's dependency version for a recipe with no explicit revision omits
  # it (e.g. gcc-libs 16.1.0). Match the harness's version stamp.
  case "$pkgver" in *-0) pkgver=${pkgver%-0};; esac
  tar -xJf "$archive" --anchored --exclude=.{BUILDINFO,PKGINFO,MTREE,INSTALL} \
    --force-local --no-overwrite-dir -C /
  if [ -n "$pkgname" ] && [ -n "$pkgver" ]; then
    printf '%s\n' "$pkgver" > "/data/data/.built-packages/$pkgname"
  fi
done
exec ./build-package.sh "$3" -a "$1" --format pacman --library glibc "$2"
'''.strip()
    cmd += ["bash", "-lc", bootstrap, "closure-build", arch, pkg, flag]
    # Retry loop ONLY for transient network failures (HTTP 429 from the gcc git
    # clone at sourceware.org, mirror hiccups, connection resets). A real build
    # failure aborts at once — retrying a deterministic failure reproduces it.
    transient_markers = ("HTTP 429", "RPC failed", "Could not resolve host",
                         "Connection timed out", "Connection refused",
                         "SSL_ERROR", "curl: (28", "curl: (22",
                         "fatal: the remote end",
                         "Temporary failure in name resolution",
                         "Failed to connect")
    max_attempts = 4
    for attempt in range(1, max_attempts + 1):
        print(f"  attempt {attempt}/{max_attempts}...")
        log_file = repo / "output" / f".{pkg}.build.log"
        log_file.parent.mkdir(exist_ok=True)
        with log_file.open("wb") as lf:
            r = subprocess.run(cmd, env=env, stdout=lf, stderr=subprocess.STDOUT)
        if r.returncode == 0:
            print(f"  OK")
            return True
        # Read the tail to classify the failure.
        try:
            tail = log_file.read_text(encoding="utf-8", errors="replace")[-3000:]
        except OSError:
            tail = ""
        is_transient = any(m in tail for m in transient_markers)
        if is_transient:
            print(f"  transient network failure; last lines:")
            for line in tail.splitlines()[-6:]:
                print(f"    {line[:140]}")
            if attempt < max_attempts:
                backoff = 30 * attempt
                print(f"  retrying in {backoff}s...")
                time.sleep(backoff)
                continue
        # Deterministic failure OR out of retries: print the tail and stop.
        print(f"  FAILED (rc={r.returncode}); last lines:")
        for line in tail.splitlines()[-15:]:
            print(f"    {line[:140]}")
        return False
    return False


def build_packages(repo: Path, arch: str, pkgs: list[str], force: bool = False) -> Path:
    """Build each lockfile package in its own container, skipping any whose
    output archive already exists. Per-package isolation makes each success
    durable: a later failure cannot throw away an earlier package's build.

    See _build_one_package for the NTFS/.termux-build rationale and the
    transient-retry policy. Output .pkg.tar.* land in repo/output/."""
    out_dir = repo / "output"
    out_dir.mkdir(exist_ok=True)
    strip_crlf_sh(repo)
    if force:
        prefixes = tuple(p for pkg in pkgs for p in (f"{pkg}-glibc-", f"{pkg}-"))
        for artifact in out_dir.glob("*.pkg.tar.*"):
            if artifact.name.startswith(prefixes):
                print(f"  force rebuild: removing generated cache artifact {artifact.name}")
                artifact.unlink()
    failed: list[str] = []
    for i, pkg in enumerate(pkgs, 1):
        if _package_output_exists(out_dir, pkg, arch):
            existing = [p.name for p in out_dir.glob(f"{pkg}-glibc-*") if p.suffix in (".xz", ".zst") or "pkg.tar" in p.name]
            print(f"[{i}/{len(pkgs)}] {pkg}: already built -> {existing}")
            continue
        print(f"\n[{i}/{len(pkgs)}] building {pkg} ...")
        install_deps = pkg not in LOCAL_BOOTSTRAP_PACKAGES
        if not _build_one_package(repo, arch, pkg, out_dir, install_deps=install_deps):
            failed.append(pkg)
    if failed:
        raise RuntimeError(f"build failed for packages: {failed}")
    return out_dir


def import_and_hash(out_dir: Path, pkgs: list[str], dest: Path) -> dict:
    """Copy the built .pkg.tar.* into the project and record per-file hashes.
    Does NOT extract here (extraction + closure verification happens on-device
    via the manifest during the spike). Returns a manifest of the imported files."""
    dest.mkdir(parents=True, exist_ok=True)
    imported = {}
    for p in sorted(out_dir.glob("*.pkg.tar.*")):
        target = dest / p.name
        shutil.copy2(p, target)
        imported[p.name] = {
            "sha256": sha256_of(target),
            "size_bytes": target.stat().st_size,
        }
    return imported


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--arch", default="x86_64", choices=["x86_64", "aarch64"])
    ap.add_argument("--packages", help="comma-separated subset (default: all lockfile packages)")
    ap.add_argument("--no-build", action="store_true",
                    help="print the plan + verify prerequisites without building")
    ap.add_argument("--force", action="store_true",
                    help="rebuild requested packages even when generated archives exist")
    args = ap.parse_args()

    pin = load_glibc_packages_pin()
    lock_pkgs = load_lockfile_packages()
    requested = args.packages.split(",") if args.packages else [p["name"] for p in lock_pkgs]
    # Validate requested names against the lockfile.
    lock_names = {p["name"] for p in lock_pkgs}
    unknown = [p for p in requested if p not in lock_names]
    if unknown:
        print(f"ERROR: packages not in lockfile: {unknown}", file=sys.stderr)
        return 2
    order = {name: index for index, name in enumerate(BUILD_ORDER)}
    requested.sort(key=lambda name: order.get(name, len(order)))

    print("=== O06 glibc closure source-build plan ===")
    print(f"  repo pin : termux-pacman/glibc-packages @ {pin['commit']}")
    print(f"  glibc    : {pin['glibc_upstream']['version']} from {pin['glibc_upstream']['source_url']}")
    print(f"  image    : {CGCT_IMAGE}")
    print(f"  arch     : {args.arch}")
    print(f"  packages : {requested}")
    print(f"  work dir : {WORK_ROOT}")
    print(f"  output   : {OUTPUT_ROOT}")

    if args.no_build:
        print("\n--no-build: plan only. Prerequisites:")
        ok = ensure_docker()
        print(f"  Docker daemon: {'OK' if ok else 'MISSING'}")
        return 0 if ok else 1

    if not ensure_docker():
        return 1

    repo = WORK_ROOT / "glibc-packages"
    clone_glibc_packages(pin["commit"], repo)
    apply_gcc_libs_recipe_pin(repo)
    apply_android_syscall_overlays(repo)
    harness_commit = vendor_build_harness(repo)
    apply_16k_linker_overlay(repo)
    print(f"\n  harness  : termux-packages @ {harness_commit} (vendored by get-build-package.sh)")

    out_dir = build_packages(repo, args.arch, requested, force=args.force)
    imported = import_and_hash(out_dir, requested, OUTPUT_ROOT)

    # Record the build result into a provenance file alongside the lockfile.
    provenance = {
        "built_at_utc": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat(),
        "glibc_packages_commit": pin["commit"],
        "termux_packages_harness_commit": harness_commit,
        "cgct_image": CGCT_IMAGE,
        "arch": args.arch,
        "elf_max_page_size": ELF_MAX_PAGE_SIZE,
        "packages_requested": requested,
        "imported_packages": imported,
    }
    prov_path = OUTPUT_ROOT / "BUILD_PROVENANCE.json"
    prov_path.write_text(json.dumps(provenance, indent=2), encoding="utf-8")
    print(f"\n=== Build complete ===")
    print(f"  imported {len(imported)} package archives -> {OUTPUT_ROOT}")
    print(f"  provenance -> {prov_path}")
    print(f"  Next: run check_wine_dtneeded.py to re-verify the closure, then the spike.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
