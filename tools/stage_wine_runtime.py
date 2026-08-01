#!/usr/bin/env python3
"""Stage the O06 Phase-0 closure (glibc rootfs + Wine ELFs + PE modules) into the
APK packaging layout for the Phase-1 feasibility spike.

Outputs two trees under native/.build-x86_64/wine-staging/:
  jniLibs/   — ELFs renamed to lib*.so (AGP extracts lib/<abi>/lib*.so into
               nativeLibraryDir under useLegacyPackaging=true). Every executable
               target lands here as an APK-managed, immutable, +x file.
  assets/    — Wine-owned PE modules (the hash-verified guest-code cache source)
               + manifest.json (canonical asset path + SHA-256 + logical Wine path).

The on-device spike builds a symlink-only logical Wine tree in filesDir pointing
at nativeLibraryDir (restoring the names Wine expects), and materializes the PE
cache from assets with SHA-256 verification. No ELF regular file ever lives in
filesDir; PE files are guest code, never passed to Android execve().

Naming convention (why rename):
  AGP only extracts lib<name>.so from lib/<abi>/. Wine's binaries don't match:
    bin/wine                 (bare name)        -> libwine_preloader.so
    bin/wineserver           (bare name)        -> libwineserver.so
    lib/wine/x86_64-unix/X.so (no lib prefix)   -> libwine_unix_X.so
    glibc ld-linux-x86-64.so.2                  -> libld_linux_x86_64.so
    glibc libc.so.6                             -> libglibc_libc.so.6.so
  The loader resolves by SONAME via --library-path, NOT by filename, so renaming
  is safe. The symlink tree restores the logical names Wine expects.

Usage:
  python3 tools/stage_wine_runtime.py              # full stage
  python3 tools/stage_wine_runtime.py --no-pe      # skip PE assets (faster; S-1/S-2 only)
  python3 tools/stage_wine_runtime.py --check      # report what would be staged, don't write
"""
from __future__ import annotations

import argparse
import hashlib
import json
import lzma
import os
import shutil
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GLIBC_ROOTFS = ROOT / "runtime" / "glibc-rootfs-x86_64"
WINE_EXTRACT = ROOT / "native" / ".providers-extracted" / "wine-kron4ek-11-14-vanilla-wow64"
WINE_ROOT_NAME = "wine-11.14-amd64-wow64"
OUTPUT = ROOT / "native" / ".build-x86_64" / "wine-staging"

# The glibc/X11/font runtime libs to stage (the DT_NEEDED closure).
# Each entry: (package_archive, [sonames to extract from lib/])
# These are the files whose SONAMEs appear in Wine's DT_NEEDED.
GLIBC_RUNTIME_SONAMES = [
    "ld-linux-x86-64.so.2",
    "libc.so.6",
    "libpthread.so.0",
    "libdl.so.2",
    "libm.so.6",
    "librt.so.1",
    "libresolv.so.2",
]
# gcc-libs provides libgcc_s.so.1 (CORE: ntdll.so DT_NEEDED).
GCC_LIBS_SONAMES = ["libgcc_s.so.1"]
# X11/font closure: each package's primary runtime .so.
X11_FONT_LIBS = {
    "libx11-glibc": ["libX11.so.6"],
    "libxcb-glibc": ["libxcb.so.1"],
    "libxau-glibc": ["libXau.so.6"],
    "libxdmcp-glibc": ["libXdmcp.so.6"],
    "libxext-glibc": ["libXext.so.6"],
    "freetype-glibc": ["libfreetype.so.6"],
    "fontconfig-glibc": ["libfontconfig.so.1"],
}
TERMUX_LIB_PREFIX = "data/data/com.termux/files/usr/glibc/lib"


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def find_pkg(name_prefix: str) -> Path:
    """Find a package archive in the rootfs by filename prefix."""
    matches = list(GLIBC_ROOTFS.glob(f"{name_prefix}*.pkg.tar.*"))
    matches = [m for m in matches if not m.name.endswith(".sha256")]
    if not matches:
        raise FileNotFoundError(f"No package matching {name_prefix}* in {GLIBC_ROOTFS}")
    if len(matches) > 1:
        # Prefer the non-static, non-dev, non-utils, non-dbg variant.
        preferred = [m for m in matches if not any(s in m.name for s in
                      ("-static-", "-dev-", "-utils-", "-dbg-"))]
        if len(preferred) == 1:
            return preferred[0]
    return matches[0]


def extract_sonames_from_pkg(pkg_path: Path, sonames: list[str]) -> dict[str, bytes]:
    """Extract specific SONAME files from a .pkg.tar.xz. Returns {soname: bytes}.
    Handles the common glibc/gcc/X11 pattern: libFOO.so.N -> libFOO.so.N.M.P (symlink
    to a real versioned file). The wanted SONAME is often a symlink; we resolve it
    to the real file's bytes."""
    # First pass: collect symlink targets for the wanted SONAMEs + identify the
    # real versioned files they point to.
    wanted_symlinks: dict[str, str] = {}   # archive_path -> soname (for symlinks)
    wanted_regulars: dict[str, str] = {}   # archive_path -> soname (for regular files)
    link_targets: dict[str, str] = {}      # archive_path -> linkname (symlink target)
    with lzma.open(pkg_path, "rb") as raw:
        with tarfile.open(fileobj=raw, mode="r|") as tf:
            for member in tf:
                base = os.path.basename(member.name)
                if base not in sonames:
                    continue
                if member.issym():
                    wanted_symlinks[member.name] = base
                    link_targets[member.name] = member.linkname
                elif member.isfile():
                    wanted_regulars[member.name] = base
    # Build the set of real files we need to read: the wanted regulars PLUS the
    # symlink targets (which are real versioned files like libX11.so.6.4.0).
    # The symlink target is a basename relative to the same directory.
    needed_real: dict[str, str] = {}  # archive_path -> soname it satisfies
    for arc, soname in wanted_regulars.items():
        needed_real[arc] = soname
    # For symlinks: resolve the target path (same dir as the symlink).
    symlink_to_soname: dict[str, str] = {}  # resolved_target_basename -> soname
    for arc, soname in wanted_symlinks.items():
        tgt = link_targets[arc]
        tgt_base = os.path.basename(tgt)
        symlink_to_soname[tgt_base] = soname
    # Second pass: read real files (wanted regulars + symlink targets).
    result: dict[str, bytes] = {}
    with lzma.open(pkg_path, "rb") as raw:
        with tarfile.open(fileobj=raw, mode="r|") as tf:
            for member in tf:
                if not member.isfile():
                    continue
                base = os.path.basename(member.name)
                arc = member.name
                if arc in needed_real:
                    f = tf.extractfile(member)
                    if f is not None:
                        result[needed_real[arc]] = f.read()
                elif base in symlink_to_soname:
                    f = tf.extractfile(member)
                    if f is not None:
                        result[symlink_to_soname[base]] = f.read()
    return result


def rename_for_jnilib(soname: str) -> str:
    """Rename a SONAME to lib<name>.so for AGP extraction.
    The loader resolves by SONAME via --library-path, not filename, so any
    lib*-prefixed name works. We preserve the original soname in the middle for
    debuggability: libX11.so.6 -> libso_libX11.so.6.so."""
    # ld-linux-x86-64.so.2 already starts with 'ld', not 'lib'.
    if soname == "ld-linux-x86-64.so.2":
        return "libld_linux_x86_64.so"
    # Strip leading 'lib', then re-add with a marker.
    if soname.startswith("lib"):
        return "libso_" + soname[3:] + ".so" if not soname.endswith(".so") else "libso_" + soname[3:]
    return "libso_" + soname + ".so"


def stage_glibc_closure(jni_dir: Path) -> dict[str, str]:
    """Extract + rename the glibc/gcc-libs/X11/font runtime .so closure."""
    staged: dict[str, str] = {}  # soname -> renamed filename

    # glibc package.
    glibc_pkg = find_pkg("glibc-2.43")
    files = extract_sonames_from_pkg(glibc_pkg, GLIBC_RUNTIME_SONAMES)
    for soname, data in sorted(files.items()):
        renamed = rename_for_jnilib(soname)
        (jni_dir / renamed).write_bytes(data)
        staged[soname] = renamed
        print(f"  glibc  {soname:28} -> {renamed:36} ({len(data):>8} B)")

    # gcc-libs package.
    gcc_pkg = find_pkg("gcc-libs-glibc")
    files = extract_sonames_from_pkg(gcc_pkg, GCC_LIBS_SONAMES)
    for soname, data in sorted(files.items()):
        renamed = rename_for_jnilib(soname)
        (jni_dir / renamed).write_bytes(data)
        staged[soname] = renamed
        print(f"  gcc    {soname:28} -> {renamed:36} ({len(data):>8} B)")

    # X11/font packages.
    for pkg_prefix, sonames in sorted(X11_FONT_LIBS.items()):
        pkg = find_pkg(pkg_prefix)
        files = extract_sonames_from_pkg(pkg, sonames)
        for soname, data in sorted(files.items()):
            renamed = rename_for_jnilib(soname)
            (jni_dir / renamed).write_bytes(data)
            staged[soname] = renamed
            print(f"  x11    {soname:28} -> {renamed:36} ({len(data):>8} B)")

    return staged


def stage_wine_elFs(wine_root: Path, jni_dir: Path) -> dict[str, str]:
    """Stage Wine's ELFs (bin/ stubs + wineserver + x86_64-unix modules).
    Returns {logical_name: renamed_filename}."""
    staged: dict[str, str] = {}

    # bin/ launcher stubs (13424-byte argv[0]-dispatch binaries) + wineserver.
    bin_dir = wine_root / "bin"
    # The launcher stubs that Wine needs at runtime (drop dev tools).
    runtime_bins = ["wine", "wineboot", "wineconsole", "winecfg", "wineserver"]
    for name in runtime_bins:
        src = bin_dir / name
        if not src.is_file():
            print(f"  WARN: {src} not found (skipping)", file=sys.stderr)
            continue
        data = src.read_bytes()
        if name == "wineserver":
            renamed = "libwineserver.so"
        else:
            # All launcher stubs are the same binary; rename to libwine_preloader.so.
            # The symlink tree handles argv[0] dispatch via the logical name.
            renamed = "libwine_preloader.so"
        out = jni_dir / renamed
        # Don't re-write the preloader stub if already written (wine==wineboot==...).
        if not out.exists():
            out.write_bytes(data)
            print(f"  wine   bin/{name:16}  -> {renamed:36} ({len(data):>8} B)")
        staged[f"bin/{name}"] = renamed

    # x86_64-unix ELF modules (ntdll.so, winex11.so, etc.).
    unix_dir = wine_root / "lib" / "wine" / "x86_64-unix"
    so_files = sorted(unix_dir.glob("*.so"))
    # Filter out the static .a archives (they're in the same dir but end .a).
    so_files = [f for f in so_files if f.suffix == ".so"]
    for src in so_files:
        renamed = "libwine_unix_" + src.name  # ntdll.so -> libwine_unix_ntdll.so
        (jni_dir / renamed).write_bytes(src.read_bytes())
        staged[f"lib/wine/x86_64-unix/{src.name}"] = renamed
    print(f"  wine   lib/wine/x86_64-unix/*.so: {len(so_files)} modules staged")

    return staged


def stage_pe_modules(wine_root: Path, assets_dir: Path) -> dict:
    """Stage Wine-owned PE modules as APK assets + generate manifest.json.
    Returns the manifest entries list."""
    pe_dirs = [
        ("x86_64-windows", wine_root / "lib" / "wine" / "x86_64-windows"),
        ("i386-windows", wine_root / "lib" / "wine" / "i386-windows"),
    ]
    manifest_entries = []
    pe_assets_dir = assets_dir / "wine-pe"
    pe_assets_dir.mkdir(parents=True, exist_ok=True)

    total = 0
    for arch_label, pe_dir in pe_dirs:
        if not pe_dir.is_dir():
            continue
        # Copy PE files (skip .a dev archives).
        pe_files = sorted(f for f in pe_dir.iterdir()
                         if f.is_file() and f.suffix in (".dll", ".exe", ".sys",
                         ".cpl", ".ocx", ".drv", ".acm", ".tlb", ".ds", ".ax",
                         ".msstyles", ".com", ".dll16", ".exe16", ".drv16",
                         ".vxd", ".mod16"))
        out_arch_dir = pe_assets_dir / arch_label
        out_arch_dir.mkdir(exist_ok=True)
        for pe in pe_files:
            asset_rel = f"wine-pe/{arch_label}/{pe.name}"
            logical_path = f"lib/wine/{arch_label}/{pe.name}"
            dst = assets_dir / asset_rel
            shutil.copy2(pe, dst)
            h = sha256_of(dst)
            manifest_entries.append({
                "asset_path": asset_rel,
                "sha256": h,
                "logical_path": logical_path,
                "arch": arch_label,
            })
            total += 1
        print(f"  pe     {arch_label}: {len(pe_files)} modules")

    # Write manifest.
    manifest = {
        "description": "Wine-owned builtin PE modules as canonical APK assets.",
        "wine_provider": "wine-kron4ek-11-14-vanilla-wow64",
        "total_modules": total,
        "entries": sorted(manifest_entries, key=lambda e: e["asset_path"]),
    }
    manifest_path = assets_dir / "wine-pe-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"  pe     manifest: {manifest_path.relative_to(ROOT)} ({total} modules)")
    return manifest


def stage_selftest_pe(assets_dir: Path) -> None:
    """Stage the pocket_selftest.exe as an authorized guest PE asset."""
    src = ROOT / "runtime" / "wine-x86_64-wow64" / "selftest" / "pocket_selftest.exe"
    if not src.is_file():
        print(f"  WARN: self-test PE not found at {src} (run build_selftest_pe.py)", file=sys.stderr)
        return
    dst = assets_dir / "guest-pe" / "pocket_selftest.exe"
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    h = sha256_of(dst)
    # Append to a small guest-PE manifest.
    guest_manifest_path = assets_dir / "guest-pe-manifest.json"
    guest_manifest = {
        "description": "Authorized guest PE files (user-provided; distinct from Wine-owned PE modules).",
        "entries": [{
            "asset_path": "guest-pe/pocket_selftest.exe",
            "sha256": h,
            "logical_path": "pocket_selftest.exe",
            "arch": "i386",
            "role": "O06 self-test (32-bit Win32 PE: window + input + audio probe)",
        }],
    }
    guest_manifest_path.write_text(json.dumps(guest_manifest, indent=2) + "\n", encoding="utf-8")
    print(f"  guest  pocket_selftest.exe -> {dst.relative_to(ROOT)} ({h[:16]}...)")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--no-pe", action="store_true",
                    help="skip PE assets (faster; for S-1/S-2 which don't need PE cache)")
    ap.add_argument("--check", action="store_true",
                    help="report what would be staged without writing files")
    args = ap.parse_args()

    wine_root = WINE_EXTRACT / WINE_ROOT_NAME
    if not wine_root.is_dir():
        print(f"ERROR: Wine not extracted at {wine_root}", file=sys.stderr)
        print(f"  Run: python tools/fetch_provider.py wine-kron4ek-11-14-vanilla-wow64",
              file=sys.stderr)
        return 2

    print("=== O06 Phase-1 Wine runtime staging ===")
    print(f"  wine root : {wine_root.relative_to(ROOT)}")
    print(f"  glibc     : {GLIBC_ROOTFS.relative_to(ROOT)}")
    print(f"  output    : {OUTPUT.relative_to(ROOT)}")

    if args.check:
        # Just report counts without writing.
        unix_dir = wine_root / "lib" / "wine" / "x86_64-unix"
        so_count = len([f for f in unix_dir.glob("*.so") if f.suffix == ".so"])
        pe_64 = len(list((wine_root / "lib" / "wine" / "x86_64-windows").glob("*")))
        pe_32 = len(list((wine_root / "lib" / "wine" / "i386-windows").glob("*")))
        print(f"\n--check (no writes):")
        print(f"  glibc closure: {len(GLIBC_RUNTIME_SONAMES) + len(GCC_LIBS_SONAMES)} SONAMEs")
        print(f"  X11/font libs: {sum(len(v) for v in X11_FONT_LIBS.values())} SONAMEs")
        print(f"  Wine unix .so: {so_count} modules")
        print(f"  Wine PE 64-bit: {pe_64} files")
        print(f"  Wine PE 32-bit: {pe_32} files")
        return 0

    # Clean + create output dirs.
    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    jni_dir = OUTPUT / "jniLibs"
    assets_dir = OUTPUT / "assets"
    jni_dir.mkdir(parents=True)
    assets_dir.mkdir(parents=True)

    print(f"\n--- staging jniLibs (ELFs renamed to lib*.so) ---")
    glibc_staged = stage_glibc_closure(jni_dir)
    wine_staged = stage_wine_elFs(wine_root, jni_dir)

    if not args.no_pe:
        print(f"\n--- staging assets (PE modules + manifest) ---")
        pe_manifest = stage_pe_modules(wine_root, assets_dir)
        stage_selftest_pe(assets_dir)
    else:
        print(f"\n--- (--no-pe: skipping PE assets) ---")
        pe_manifest = None

    # Write a staging manifest (for the on-device code to build the symlink tree).
    staging_manifest = {
        "description": "O06 Phase-1 staging manifest: maps logical names to APK-managed files.",
        "staged_at_utc": __import__("datetime").datetime.now(
            __import__("datetime").timezone.utc).isoformat(),
        "glibc_rootfs_commit": "c9d4de54797bd6652f797217bf9e4c4050f02798",
        "gcc_libs_recipe_commit": "845d0e313f53535de28abba0fb42b07e960cc031",
        "wine_provider": "wine-kron4ek-11-14-vanilla-wow64",
        "glibc_soname_to_jnilib": glibc_staged,
        "wine_logical_to_jnilib": wine_staged,
        "has_pe_assets": pe_manifest is not None,
        "pe_module_count": pe_manifest["total_modules"] if pe_manifest else 0,
    }
    manifest_path = OUTPUT / "staging-manifest.json"
    manifest_path.write_text(json.dumps(staging_manifest, indent=2) + "\n", encoding="utf-8")

    # Summary.
    jni_files = list(jni_dir.glob("*.so"))
    total_jni_size = sum(f.stat().st_size for f in jni_files)
    print(f"\n=== staging complete ===")
    print(f"  jniLibs   : {len(jni_files)} files ({total_jni_size / 1048576:.1f} MiB)")
    print(f"  manifest  : {manifest_path.relative_to(ROOT)}")
    if pe_manifest:
        asset_files = sum(1 for _ in assets_dir.rglob("*") if _.is_file())
        print(f"  assets    : {asset_files} files (PE modules + manifest)")
    print(f"  output dir: {OUTPUT.relative_to(ROOT)}")
    print(f"\n  Next: the Gradle stageNativeLibs task picks up jniLibs/; assets/ is")
    print(f"  wired as an Android assets source dir. The on-device spike reads")
    print(f"  staging-manifest.json to build the symlink tree.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
