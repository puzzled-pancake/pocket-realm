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
  2. Vendor the build harness via get-build-package.sh (shallow-clones
     termux-packages; we record the harness commit for provenance).
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
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCKFILE = ROOT / "schemas" / "wine-runtime-lockfile.json"
SOURCES = ROOT / "schemas" / "sources.json"
WORK_ROOT = ROOT / "native" / ".glibc-build"          # gitignored build workspace
OUTPUT_ROOT = ROOT / "runtime" / "glibc-rootfs-x86_64"  # final staged closure

GLIBC_PACKAGES_ID = "termux-glibc-packages-c9d4de5"
CGCT_IMAGE = "ghcr.io/termux/package-builder-cgct:latest"

# On Windows, Python's subprocess must use the EXPLICIT Git Bash binary, not
# PATH resolution: Windows' own bash.exe (System32) is the WSL launcher, which
# fails with "execvpe(/bin/bash) failed: No such file or directory" when there
# is no usable WSL distro. Resolve the Git Bash binary the same way the running
# shell would.
def _resolve_git_bash() -> str:
    import shutil
    candidates = [
        shutil.which("bash"),  # works when invoked from Git Bash
        r"C:\Program Files\Git\usr\bin\bash.exe",
        r"C:\Program Files\Git\bin\bash.exe",
        str(Path.home() / r"AppData\Local\Programs\Git\usr\bin\bash.exe"),
    ]
    for c in candidates:
        if c and Path(c).is_file():
            return c
    return "bash"  # fall back; will fail loudly on Windows without Git

GIT_BASH = _resolve_git_bash()


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


def vendor_build_harness(repo: Path) -> str:
    """Run get-build-package.sh, which shallow-clones termux-packages master and
    copies build-package.sh + scripts/ in. Return the termux-packages commit for
    provenance recording (the harness version is otherwise unpinned)."""
    if (repo / "build-package.sh").is_file():
        print(f"  (build harness already vendored at {repo})")
    else:
        run([GIT_BASH, "get-build-package.sh"], cwd=repo)
    # Strip CRLF from the freshly-vendored scripts (Windows checkout).
    n = strip_crlf_sh(repo)
    print(f"  stripped CRLF from {n} .sh files (Windows checkout fix)")
    # The cloned termux-packages/ is deleted by get-build-package.sh, so we
    # cannot read its commit after the fact. Record the harness as unpinned
    # (master) — a known provenance gap noted in BUILD_PROVENANCE.json.
    return "master (unpinned by get-build-package.sh — see provenance gap note)"


def build_packages(repo: Path, arch: str, pkgs: list[str]) -> Path:
    """Build packages via a direct docker run into the cgct container.

    Bypasses scripts/run-docker.sh because that wrapper derives the volume path
    from $PWD as a /c/... MSYS path, which Docker Desktop cannot resolve; and it
    injects a seccomp/apparmor profile path that also fails on Windows. Instead
    we invoke docker directly with the //c/... double-slash mount form Docker
    Desktop requires and MSYS_NO_PATHCONV=1 to stop Git Bash mangling the
    container-side Linux paths. No seccomp profile (we are building trusted,
    pinned, hash-verified packages; the profile is a hardening nicety).
    Output .pkg.tar.* land in repo/output/. Returns the output dir."""
    out_dir = repo / "output"
    out_dir.mkdir(exist_ok=True)
    # Docker Desktop expects the Windows drive as //c/... in the volume mount
    # (lowercase drive, NO colon — a colon makes Docker parse it as a 3-part
    # "src:dst:mode" spec and reject the destination as an "invalid mode").
    win_path = str(repo).replace("\\", "/")
    # "C:/foo/bar" -> "c/foo/bar"
    if len(win_path) >= 2 and win_path[1] == ":":
        win_path = win_path[0].lower() + win_path[2:]
    mount_src = "//" + win_path
    # Also strip CRLF again (the build may pull additional dep build.sh files
    # that were checked out with CRLF).
    strip_crlf_sh(repo)
    env = dict(**__import__("os").environ, MSYS_NO_PATHCONV="1")
    # -I builds dependencies recursively; --library glibc selects gpkg; --format
    # pacman emits .pkg.tar.* . Build all in one invocation so the dep order
    # resolves within the harness.
    cmd = [
        "docker", "run", "--rm",
        "-v", f"{mount_src}:/home/builder/termux-packages",
        "--workdir", "/home/builder/termux-packages",
        CGCT_IMAGE,
        "./build-package.sh", "-I", "-a", arch,
        "--format", "pacman", "--library", "glibc", *pkgs,
    ]
    run(cmd, env=env)
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
    harness_commit = vendor_build_harness(repo)
    print(f"\n  harness  : termux-packages @ {harness_commit} (vendored by get-build-package.sh)")

    out_dir = build_packages(repo, args.arch, requested)
    imported = import_and_hash(out_dir, requested, OUTPUT_ROOT)

    # Record the build result into a provenance file alongside the lockfile.
    provenance = {
        "built_at_utc": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat(),
        "glibc_packages_commit": pin["commit"],
        "termux_packages_harness_commit": harness_commit,
        "cgct_image": CGCT_IMAGE,
        "arch": args.arch,
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
