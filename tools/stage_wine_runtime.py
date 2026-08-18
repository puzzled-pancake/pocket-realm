#!/usr/bin/env python3
"""Stage the runtime closure (glibc rootfs + Wine ELFs + PE modules) into the
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
  python3 tools/stage_wine_runtime.py --no-pe      # skip PE assets (faster; loader-only lanes)
  python3 tools/stage_wine_runtime.py --check      # report what would be staged, don't write
"""
from __future__ import annotations

import argparse
import hashlib
import json
import lzma
import os
import shutil
import struct
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
GLIBC_ROOTFS = ROOT / "runtime" / "glibc-rootfs-x86_64"
WINE_EXTRACT = ROOT / "native" / ".providers-extracted" / "wine-kron4ek-11-14-vanilla-wow64"
WINE_ROOT_NAME = "wine-11.14-amd64-wow64"
OUTPUT = ROOT / "native" / ".build-x86_64" / "wine-staging"
WINE_16K_NTDLL = (
    ROOT / "native" / ".build-x86_64" / "wine-ntdll-16k-multiarch" /
    "dlls" / "ntdll" / "ntdll.so"
)
WINE_16K_NTDLL_PE = (
    ROOT / "native" / ".build-x86_64" / "wine-ntdll-16k-multiarch" /
    "dlls" / "ntdll" / "x86_64-windows" / "ntdll.dll"
)
WINE_16K_WIN32U_PE = (
    ROOT / "native" / ".build-x86_64" / "wine-ntdll-16k-multiarch" /
    "dlls" / "win32u" / "x86_64-windows" / "win32u.dll"
)
GLADIO_CLIENT = ROOT / "native" / ".build-x86_64" / "gladio-client" / "libGL.so.1"

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
    # Transitive DT_NEEDED closure of freetype + fontconfig. These must be
    # staged beside their parents: merely listing the two dlopen targets in
    # the lockfile does not make dlopen succeed.
    "zlib-glibc": ["libz.so.1"],
    "libbz2-glibc": ["libbz2.so.1.0"],
    "libpng-glibc": ["libpng16.so.16"],
    "brotli-glibc": ["libbrotlidec.so.1", "libbrotlicommon.so.1"],
    "libexpat-glibc": ["libexpat.so.1"],
}
TERMUX_LIB_PREFIX = "data/data/com.termux/files/usr/glibc/lib"
ANDROID_MAX_PAGE_SIZE = 0x4000


sha256_of = common.sha256_file
def validate_elf_page_compatibility(data: bytes, label: str) -> None:
    """Fail closed unless every ELF PT_LOAD works on 4 KB and 16 KB kernels.

    Android's 16 KB loader requires each load segment to be aligned to at
    least 16 KB and for the file offset and virtual address to be congruent at
    that page size. A 16 KB-aligned ELF remains valid on the 4 KB lane.
    """
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise RuntimeError(f"expected ELF runtime artifact: {label}")
    if data[4] != 2 or data[5] != 1:
        raise RuntimeError(f"expected little-endian ELF64 runtime artifact: {label}")

    try:
        phoff = struct.unpack_from("<Q", data, 32)[0]
        phentsize = struct.unpack_from("<H", data, 54)[0]
        phnum = struct.unpack_from("<H", data, 56)[0]
    except struct.error as exc:
        raise RuntimeError(f"truncated ELF header: {label}") from exc

    load_count = 0
    issues: list[str] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + 56 > len(data):
            raise RuntimeError(f"truncated ELF program headers: {label}")
        p_type = struct.unpack_from("<I", data, offset)[0]
        if p_type != 1:  # PT_LOAD
            continue
        load_count += 1
        p_offset = struct.unpack_from("<Q", data, offset + 8)[0]
        p_vaddr = struct.unpack_from("<Q", data, offset + 16)[0]
        p_align = struct.unpack_from("<Q", data, offset + 48)[0]
        if p_align < ANDROID_MAX_PAGE_SIZE:
            issues.append(f"PT_LOAD[{index}] align=0x{p_align:x}")
        if p_offset % ANDROID_MAX_PAGE_SIZE != p_vaddr % ANDROID_MAX_PAGE_SIZE:
            issues.append(
                f"PT_LOAD[{index}] offset/vaddr not 16K-congruent "
                f"(0x{p_offset:x}/0x{p_vaddr:x})"
            )
    if load_count == 0:
        raise RuntimeError(f"ELF contains no PT_LOAD segment: {label}")
    if issues:
        raise RuntimeError(f"16 KB-incompatible ELF {label}: {'; '.join(issues)}")


def write_runtime_elf(path: Path, data: bytes, label: str) -> None:
    validate_elf_page_compatibility(data, label)
    path.write_bytes(data)


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

    AGP only packages files whose name ends in '.so' (the final extension must
    be .so). Glibc/X11 SONAMEs like 'libc.so.6' or 'libdl.so.2' do NOT end in
    '.so' (they end in .6/.2/etc.), so AGP silently drops them. We rename ALL
    glibc/X11 libs to 'lib<soname>.so' (e.g. libc.so.6 -> liblibc.so.6.so).

    The glibc loader resolves DT_NEEDED by FILENAME via --library-path. Since we
    renamed the files, the loader can't find 'libc.so.6' in nativeLibraryDir.
    The fix: the symlink tree in filesDir provides 'libc.so.6' as a symlink ->
    'nativeLibraryDir/liblibc.so.6.so', and --library-path points at the symlink
    tree's lib/ dir (not nativeLibraryDir directly). The loader follows symlinks.
    """
    if soname == "ld-linux-x86-64.so.2":
        return "libld_linux_x86_64.so"
    # All other glibc/X11 libs: lib<soname>.so (preserve the SONAME in the name).
    return "lib" + soname + ".so"


def patch_rtld_access_for_android(path: Path) -> None:
    """Replace the pinned loader's raw access(2) wrapper with faccessat(2).

    Android's untrusted_app seccomp profile traps x86_64 syscall 21 even though
    faccessat is allowed.  termux-pacman's glibc patch set handles public libc
    calls, but rtld contains a private static access wrapper used before any
    LD_PRELOAD object can run.  The replacement preserves the two-argument
    access ABI, returns the raw negative errno on failure (rtld only tests zero
    vs non-zero), and is deliberately signature-locked to the pinned glibc
    2.43 artifact so an upstream byte change fails closed.
    """
    data = bytearray(path.read_bytes())
    signature = bytes.fromhex(
        "f30f1efa"          # endbr64
        "b815000000"        # mov $SYS_access,%eax
        "0f05"              # syscall
        "483d00f0ffff"      # cmp $-4096,%rax
        "7705c3"            # ja error; ret
    )
    offset = data.find(signature)
    if offset < 0 or data.find(signature, offset + 1) >= 0:
        raise RuntimeError("pinned rtld access wrapper signature missing or ambiguous")
    replacement = bytes.fromhex(
        "f30f1efa"          # endbr64
        "89f2"              # mode: esi -> edx
        "4889fe"            # pathname: rdi -> rsi
        "bf9cffffff"        # dirfd: AT_FDCWD -> edi
        "b80d010000"        # SYS_faccessat (269) -> eax
        "0f05"              # syscall
        "c3"                # return (zero or negative errno)
    )
    data[offset:offset + len(replacement)] = replacement
    path.write_bytes(data)
    print(f"  patch  rtld access(2)->faccessat(2) at file offset 0x{offset:x}")


def patch_libc_legacy_stat_for_android(path: Path) -> None:
    """Translate glibc's versioned stat/lstat entry points to newfstatat.

    Wine links the compatibility symbols __xstat64 and __lxstat64 directly.
    Those private glibc entry points retain the legacy x86_64 stat(2) and
    lstat(2) syscalls even though termux-pacman's public stat wrappers use the
    Android-compatible *at API. Android's untrusted_app seccomp policy traps
    those legacy calls before an LD_PRELOAD interposer can help.

    Each 32-byte replacement keeps the version check and the existing glibc
    errno blocks immediately following it. Only the successful syscall setup
    changes: newfstatat(AT_FDCWD, path, buf, flags).
    """
    data = bytearray(path.read_bytes())
    for syscall_nr, at_flags, label in ((4, 0, "stat"), (6, 0x100, "lstat")):
        signature = (
            bytes.fromhex(
                "f30f1efa"              # endbr64
                "83ff01"                # supported ABI version <= 1
                "772f"                  # invalid version -> existing EINVAL block
                "4889f7"                # path -> rdi (legacy syscall ABI)
                "b8"                    # mov syscall number -> eax
            )
            + syscall_nr.to_bytes(4, "little")
            + bytes.fromhex(
                "4889d6"                # result buffer -> rsi
                "0f05"
                "483d00f0ffff"
                "7702"
                "c3"
                "90"
            )
        )
        offset = data.find(signature)
        if offset < 0 or data.find(signature, offset + 1) >= 0:
            raise RuntimeError(f"pinned glibc __{label}stat64 signature missing or ambiguous")
        replacement = bytes.fromhex(
            "f30f1efa"
            "83ff01"
            "772f"
            "41ba" + at_flags.to_bytes(4, "little").hex() +  # fourth arg: flags
            "bf9cffffff"                                   # AT_FDCWD
            "b806010000"                                   # SYS_newfstatat (262)
            "0f05"
            "85c0"
            "7801"                                         # negative -> errno block
            "c3"
        )
        if len(replacement) != len(signature):
            raise AssertionError("glibc legacy stat patch must preserve the function prefix size")
        data[offset:offset + len(signature)] = replacement
        print(f"  patch  libc {label}(2)->newfstatat(2) at file offset 0x{offset:x}")
    path.write_bytes(data)


def patch_wine_interp_to_inherited_fd(path: Path) -> None:
    """Point Wine's final ELF loader at an inherited APK rtld file handle.

    wine-preloader maps the final loader itself, then opens its PT_INTERP path.
    Android has no writable /lib64 namespace and the APK loader path is too
    long for the fixed ELF field. The direct launcher reserves fd 100 for the
    immutable APK loader; /proc/self/fd/100 fits in the original field and is
    resolved by the kernel without copying executable bytes to writable data.
    """
    data = bytearray(path.read_bytes())
    original = b"/lib64/ld-linux-x86-64.so.2\0"
    replacement = b"/proc/self/fd/100\0"
    offset = data.find(original)
    if offset < 0 or data.find(original, offset + 1) >= 0:
        raise RuntimeError("pinned Wine PT_INTERP signature missing or ambiguous")
    data[offset:offset + len(original)] = replacement.ljust(len(original), b"\0")
    path.write_bytes(data)
    print(f"  patch  Wine PT_INTERP -> /proc/self/fd/100 at file offset 0x{offset:x}")


def patch_wine_preloader_open_for_android(path: Path) -> None:
    """Replace wine-preloader's raw open(2) wrapper with openat(2).

    The static preloader runs before glibc and therefore cannot benefit from
    the LD_PRELOAD compatibility shim. Android's untrusted_app seccomp policy
    traps the legacy x86_64 open syscall, while openat is permitted. Preserve
    wld_open(path, flags, mode)'s observable contract: a descriptor on success
    and exactly -1 for every kernel error. Matching the complete pinned Wine
    11.14 function makes an upstream change fail closed.
    """
    data = bytearray(path.read_bytes())
    signature = bytes.fromhex(
        "48c7c002000000"    # mov $SYS_open,%rax
        "4989ca"            # generic wrapper fourth-argument shuffle
        "0f05"              # syscall
        "488d8800100000"    # kernel error-range test
        "48c7c2ffffffff"
        "4881f900100000"
        "480f42c2"
        "c3"
    )
    offset = data.find(signature)
    if offset < 0 or data.find(signature, offset + 1) >= 0:
        raise RuntimeError("pinned Wine preloader wld_open signature missing or ambiguous")
    replacement = bytes.fromhex(
        "4989d2"            # mode: rdx -> r10
        "4889f2"            # flags: rsi -> rdx
        "4889fe"            # pathname: rdi -> rsi
        "bf9cffffff"        # dirfd: AT_FDCWD -> edi
        "b801010000"        # SYS_openat (257) -> eax
        "0f05"              # syscall
        "4885c0"            # test return value
        "7907"              # non-negative: skip the -1 assignment
        "48c7c0ffffffff"    # any kernel error -> -1
        "c3"
        "90909090"          # retain the original function extent
    )
    if len(replacement) != len(signature):
        raise AssertionError("Wine preloader patch must preserve the function size")
    data[offset:offset + len(signature)] = replacement
    path.write_bytes(data)
    print(f"  patch  wine-preloader open(2)->openat(2) at file offset 0x{offset:x}")


def patch_wine_datadir_to_app_alias(path: Path, with_nls_suffix: bool = False) -> None:
    """Relocate the provider's build-machine DATADIR to an app-private alias.

    Kron4ek's prebuilt NTDLL embeds /home/runner/.../share/wine and uses it via
    glibc-private pathname calls, which an LD_PRELOAD wrapper cannot reliably
    interpose. The owner-user AVD resolves /data/data/com.pocketrealm to the
    package data directory; WineSpikeRunner creates the final `wine` symlink to
    the verified cache before launch. This path is spike-only (the production
    provider contract must supply a user-aware DATADIR), app-private, and never
    turns data into executable bytes.
    """
    data = bytearray(path.read_bytes())
    root = b"/home/runner/build_wine/wine-11.14-amd64/share/wine"
    original = root + (b"/nls\0" if with_nls_suffix else b"\0")
    replacement = b"/data/data/com.pocketrealm/wine" + (b"/nls\0" if with_nls_suffix else b"\0")
    offset = data.find(original)
    if offset < 0 or data.find(original, offset + 1) >= 0:
        suffix = "/nls" if with_nls_suffix else ""
        raise RuntimeError(f"pinned Wine DATADIR{suffix} signature missing or ambiguous in {path.name}")
    data[offset:offset + len(original)] = replacement.ljust(len(original), b"\0")
    path.write_bytes(data)
    suffix = "/nls" if with_nls_suffix else ""
    print(f"  patch  Wine DATADIR{suffix} -> /data/data/com.pocketrealm/wine{suffix} at file offset 0x{offset:x}")


def stage_glibc_closure(jni_dir: Path) -> dict[str, str]:
    """Extract + rename the glibc/gcc-libs/X11/font runtime .so closure."""
    staged: dict[str, str] = {}  # soname -> renamed filename

    # glibc package.
    glibc_pkg = find_pkg("glibc-2.43")
    files = extract_sonames_from_pkg(glibc_pkg, GLIBC_RUNTIME_SONAMES)
    for soname, data in sorted(files.items()):
        renamed = rename_for_jnilib(soname)
        write_runtime_elf(jni_dir / renamed, data, soname)
        staged[soname] = renamed
        print(f"  glibc  {soname:28} -> {renamed:36} ({len(data):>8} B)")

    patch_rtld_access_for_android(jni_dir / rename_for_jnilib("ld-linux-x86-64.so.2"))
    patch_libc_legacy_stat_for_android(jni_dir / rename_for_jnilib("libc.so.6"))

    # gcc-libs package.
    gcc_pkg = find_pkg("gcc-libs-glibc")
    files = extract_sonames_from_pkg(gcc_pkg, GCC_LIBS_SONAMES)
    for soname, data in sorted(files.items()):
        renamed = rename_for_jnilib(soname)
        write_runtime_elf(jni_dir / renamed, data, soname)
        staged[soname] = renamed
        print(f"  gcc    {soname:28} -> {renamed:36} ({len(data):>8} B)")

    # X11/font packages.
    for pkg_prefix, sonames in sorted(X11_FONT_LIBS.items()):
        pkg = find_pkg(pkg_prefix)
        files = extract_sonames_from_pkg(pkg, sonames)
        for soname, data in sorted(files.items()):
            renamed = rename_for_jnilib(soname)
            write_runtime_elf(jni_dir / renamed, data, soname)
            staged[soname] = renamed
            print(f"  x11    {soname:28} -> {renamed:36} ({len(data):>8} B)")

    # WineD3D dlopens libGL.so.1. Winlator's Gladio release asset is AArch64
    # because its x86_64 Wine runs through Box64; Pocket Realm runs Wine
    # natively on x86_64, so package the source-built x86_64 Gladio client.
    if not GLADIO_CLIENT.is_file():
        raise FileNotFoundError(
            f"x86_64 Gladio client missing: {GLADIO_CLIENT}\n"
            "  Run: python tools/build_gladio_client.py"
        )
    soname = "libGL.so.1"
    data = GLADIO_CLIENT.read_bytes()
    renamed = rename_for_jnilib(soname)
    write_runtime_elf(jni_dir / renamed, data, "Gladio x86_64 libGL.so.1")
    staged[soname] = renamed
    print(f"  gladio {soname:28} -> {renamed:36} ({len(data):>8} B)")

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
            write_runtime_elf(out, data, f"Wine bin/{name}")
            if name == "wineserver":
                patch_wine_interp_to_inherited_fd(out)
                patch_wine_datadir_to_app_alias(out, with_nls_suffix=True)
            print(f"  wine   bin/{name:16}  -> {renamed:36} ({len(data):>8} B)")
        staged[f"bin/{name}"] = renamed

    # Wine's bin/ launcher is only the first stage.  For every command other
    # than --help/--version, ntdll re-execs the installed architecture loader
    # (and, on x86_64, its static preloader) from lib/wine/x86_64-unix/.  The
    # bootstrap probe used to miss this because --version exits before the
    # re-exec.  Package both second-stage executables as immutable APK-managed
    # files; proot_run.c exposes their expected logical names with bind mounts.
    unix_dir = wine_root / "lib" / "wine" / "x86_64-unix"
    for name, renamed in (
        ("wine", "libwine_loader.so"),
        ("wine-preloader", "libwine_loader_preloader.so"),
    ):
        src = unix_dir / name
        if not src.is_file():
            raise FileNotFoundError(f"required Wine second-stage loader missing: {src}")
        write_runtime_elf(jni_dir / renamed, src.read_bytes(), f"Wine {name}")
        if name == "wine":
            patch_wine_interp_to_inherited_fd(jni_dir / renamed)
        elif name == "wine-preloader":
            patch_wine_preloader_open_for_android(jni_dir / renamed)
        staged[f"lib/wine/x86_64-unix/{name}"] = renamed
        print(f"  wine   lib/wine/x86_64-unix/{name:14} -> {renamed:36} ({src.stat().st_size:>8} B)")

    # x86_64-unix ELF modules (ntdll.so, winex11.so, etc.).
    so_files = sorted(unix_dir.glob("*.so"))
    # Filter out the static .a archives (they're in the same dir but end .a).
    so_files = [f for f in so_files if f.suffix == ".so"]
    for src in so_files:
        renamed = "libwine_unix_" + src.name  # ntdll.so -> libwine_unix_ntdll.so
        out = jni_dir / renamed
        runtime_src = WINE_16K_NTDLL if src.name == "ntdll.so" else src
        if src.name == "ntdll.so" and not runtime_src.is_file():
            raise FileNotFoundError(
                f"16 KB-aware Wine ntdll missing: {runtime_src}\n"
                "  Run: python tools/build_wine_16k_ntdll.py"
            )
        write_runtime_elf(out, runtime_src.read_bytes(), f"Wine {src.name}")
        if src.name == "ntdll.so":
            patch_wine_datadir_to_app_alias(out)
            print(
                "  wine   ntdll.so: source-matched 16 KB host-page patch "
                f"({sha256_of(runtime_src)[:16]}...)"
            )
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

    paired_dispatcher_modules = {
        "ntdll.dll": WINE_16K_NTDLL_PE,
        "win32u.dll": WINE_16K_WIN32U_PE,
    }
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
            source = pe
            if arch_label == "x86_64-windows" and pe.name in paired_dispatcher_modules:
                paired_source = paired_dispatcher_modules[pe.name]
                if not paired_source.is_file():
                    raise FileNotFoundError(
                        f"16 KB-aware Wine PE module missing: {paired_source}\n"
                        "  Run: python tools/build_wine_16k_ntdll.py"
                    )
                source = paired_source
                print(
                    f"  pe     x86_64-windows/{pe.name}: paired 16 KB "
                    f"dispatcher patch ({sha256_of(source)[:16]}...)"
                )
            shutil.copy2(source, dst)
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


def stage_wine_data(wine_root: Path, assets_dir: Path) -> dict:
    """Stage Wine's architecture-independent runtime data with hashes.

    ntdll reads the NLS tables before it can start wineserver, and wineboot
    subsequently consumes wine.inf/fonts.  These are regular data files, not
    Android-executed ELFs, so they belong in the verified filesDir cache.  The
    logical paths are linked under wine-tree/share/wine; proot_run.c bind-maps
    that directory onto Wine's compile-time DATADIR location.
    """
    source_dir = wine_root / "share" / "wine"
    if not source_dir.is_dir():
        raise FileNotFoundError(f"required Wine data directory missing: {source_dir}")

    entries = []
    for source in sorted(p for p in source_dir.rglob("*") if p.is_file()):
        rel = source.relative_to(source_dir).as_posix()
        asset_rel = f"wine-data/{rel}"
        destination = assets_dir / asset_rel
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        entries.append({
            "asset_path": asset_rel,
            "sha256": sha256_of(destination),
            "logical_path": f"share/wine/{rel}",
            "role": "Wine architecture-independent runtime data",
        })

    manifest = {
        "description": "Hash-verified Wine DATADIR files (NLS, wine.inf, fonts, metadata).",
        "wine_provider": "wine-kron4ek-11-14-vanilla-wow64",
        "total_files": len(entries),
        "entries": entries,
    }
    manifest_path = assets_dir / "wine-data-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    total_size = sum((assets_dir / entry["asset_path"]).stat().st_size for entry in entries)
    print(f"  data   manifest: {manifest_path.relative_to(ROOT)} "
          f"({len(entries)} files, {total_size / 1048576:.1f} MiB)")
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
            "role": "self-test (32-bit Win32 PE: window + input + audio probe)",
        }],
    }
    guest_manifest_path.write_text(json.dumps(guest_manifest, indent=2) + "\n", encoding="utf-8")
    print(f"  guest  pocket_selftest.exe -> {dst.relative_to(ROOT)} ({h[:16]}...)")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--no-pe", action="store_true",
                    help="skip PE assets (faster; for lanes that don't need the PE cache)")
    ap.add_argument("--check", action="store_true",
                    help="report what would be staged without writing files")
    args = ap.parse_args()

    wine_root = WINE_EXTRACT / WINE_ROOT_NAME
    if not wine_root.is_dir():
        print(f"ERROR: Wine not extracted at {wine_root}", file=sys.stderr)
        print(f"  Run: python tools/fetch_provider.py wine-kron4ek-11-14-vanilla-wow64",
              file=sys.stderr)
        return 2

    print("=== Wine runtime staging ===")
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
        data_manifest = stage_wine_data(wine_root, assets_dir)
        stage_selftest_pe(assets_dir)
    else:
        print(f"\n--- (--no-pe: skipping PE assets) ---")
        pe_manifest = None
        data_manifest = None

    # Write a staging manifest (for the on-device code to build the symlink tree).
    staging_manifest = {
        "description": "Wine staging manifest: maps logical names to APK-managed files.",
        "staged_at_utc": __import__("datetime").datetime.now(
            __import__("datetime").timezone.utc).isoformat(),
        "glibc_rootfs_commit": "c9d4de54797bd6652f797217bf9e4c4050f02798",
        "gcc_libs_recipe_commit": "845d0e313f53535de28abba0fb42b07e960cc031",
        "wine_provider": "wine-kron4ek-11-14-vanilla-wow64",
        "glibc_soname_to_jnilib": glibc_staged,
        "wine_logical_to_jnilib": wine_staged,
        "has_pe_assets": pe_manifest is not None,
        "pe_module_count": pe_manifest["total_modules"] if pe_manifest else 0,
        "has_wine_data_assets": data_manifest is not None,
        "wine_data_file_count": data_manifest["total_files"] if data_manifest else 0,
    }

    # Record proot fallback artifacts if they were built (built separately by
    # tools/build_proot.py; the proot path is selected only if the direct loader path fails).
    # These are APK-managed (+x, immutable) so PROOT_LOADER can point at the
    # libproot_loader.so here instead of proot extracting one to writable storage.
    proot_stage = ROOT / "native" / ".build-x86_64" / "proot-stage"
    if proot_stage.is_dir():
        import hashlib as _hashlib
        proot_artifacts = {}
        for name in ("libproot.so", "libproot_loader.so",
                     "libproot_loader32.so", "libtalloc.so"):
            f = proot_stage / name
            if f.is_file():
                proot_artifacts[name] = {
                    "sha256": _hashlib.sha256(f.read_bytes()).hexdigest(),
                    "size": f.stat().st_size,
                    "source": ("proot" if name.startswith("libproot") else "talloc"),
                }
        if proot_artifacts:
            staging_manifest["proot_artifacts"] = proot_artifacts
            staging_manifest["proot_loader_jnilib"] = "libproot_loader.so"
            staging_manifest["proot_loader32_jnilib"] = "libproot_loader32.so"
    manifest_path = OUTPUT / "staging-manifest.json"
    manifest_path.write_text(json.dumps(staging_manifest, indent=2) + "\n", encoding="utf-8")

    # Also copy the staging manifest into assets/ so the on-device code can read
    # it via AssetManager (it maps logical names -> APK-managed jniLib filenames).
    assets_manifest = assets_dir / "staging-manifest.json"
    shutil.copy2(manifest_path, assets_manifest)
    print(f"  manifest  (assets copy): {assets_manifest.relative_to(ROOT)}")

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
