#!/usr/bin/env python3
"""O06 Phase-1 S-5(b): build termux/proot + libtalloc for Android x86_64 (Bionic).

WHY: The S-1 on-device diagnostic (sigsys_diag.c) PROVED the direct glibc-loader
invocation is killed by Android's untrusted_app seccomp filter on syscall 21
(access), si_code=1 (SYS_SECCOMP). The S-5(a) Bionic trampoline hit the identical
trap. No GLIBC_TUNABLES suppresses the loader's access() probing. The proot
fallback (S-5b) intercepts syscalls via ptrace and translates access(2) to
faccessat(2) — the standard Wine-on-Android solution.

This is the FALLBACK path, built only because the spike selected it. The exact
failure forcing the fallback is recorded in the S-5 correction commit:
  si_signo=31, si_code=1 (SYS_SECCOMP), syscall=21 (access), arch=AUDIT_ARCH_X86_64

WHAT THIS BUILDS:
  - libtalloc.so  (single C file from samba talloc-2.4.2; cross-compiled for Bionic)
  - libproot.so   (proot PIE, NDK/Bionic, loader embedded via objcopy)

proot runs in the Android/Bionic namespace (compiled with the NDK against Bionic,
so it does NOT call access(2) itself — Bionic uses faccessat). It ptrace-traces
the glibc-namespace children (Wine/wineserver) and translates their blocked
syscalls. The traced children still use the APK-managed glibc loader as their
effective loader (proot does not replace the loader, it intercepts syscalls).

This builder REIMPLEMENTS the proot src/GNUmakefile in Python because Windows
has no `make`. The build is deterministic and produces identical results to the
makefile. The loader-embedding OBJIFY step uses llvm-objcopy directly.

Pinned sources (see schemas/sources.json):
  proot:  termux/proot@a89b3732ec6ae1db674510f0843b2f3db54d0a2f (v5.1.107.89, GPL-2.0+)
  talloc: samba talloc-2.4.2 (sha256 85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6, LGPL-3.0)

Usage:
  python3 tools/build_proot.py --abi x86_64
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native"
PROVIDERS = NATIVE / ".providers-extracted"
PROOT_SRC = PROVIDERS / "proot-termux-a89b3732"
TALLOC_SRC = PROVIDERS / "talloc-2.4.2"

SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
NDK = SDK / "ndk-link"
TOOLCHAIN = NDK / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64"
BIN = TOOLCHAIN / "bin"
SYSROOT = TOOLCHAIN / "sysroot"

ABIS = {"arm64-v8a": ("aarch64", 30), "x86_64": ("x86_64", 30)}

# The object list, verbatim from src/GNUmakefile OBJECTS (excluding loader-wrapped
# which is added conditionally, and loader-info which is HAS_POKEDATA_WORKAROUND).
PROOT_OBJECTS = [
    "cli/cli.o", "cli/proot.o", "cli/note.o",
    "execve/enter.o", "execve/exit.o", "execve/shebang.o", "execve/elf.o",
    "execve/ldso.o", "execve/auxv.o", "execve/aoxp.o",
    "path/binding.o", "path/glue.o", "path/canon.o", "path/f2fs-bug.o",
    "path/path.o", "path/proc.o", "path/temp.o",
    "syscall/seccomp.o", "syscall/syscall.o", "syscall/chain.o",
    "syscall/enter.o", "syscall/exit.o", "syscall/sysnum.o",
    "syscall/socket.o", "syscall/heap.o", "syscall/rlimit.o",
    "syscall/pipe_shadow.o",
    "tracee/tracee.o", "tracee/mem.o", "tracee/reg.o", "tracee/event.o",
    "tracee/seccomp.o", "tracee/statx.o",
    "ptrace/ptrace.o", "ptrace/user.o", "ptrace/wait.o",
    "extension/extension.o", "extension/ashmem_memfd/ashmem_memfd.o",
    "extension/kompat/kompat.o",
    "extension/fake_id0/chown.o", "extension/fake_id0/chroot.o",
    "extension/fake_id0/getsockopt.o", "extension/fake_id0/sendmsg.o",
    "extension/fake_id0/socket.o", "extension/fake_id0/open.o",
    "extension/fake_id0/unlink.o", "extension/fake_id0/rename.o",
    "extension/fake_id0/chmod.o", "extension/fake_id0/utimensat.o",
    "extension/fake_id0/access.o", "extension/fake_id0/exec.o",
    "extension/fake_id0/link.o", "extension/fake_id0/symlink.o",
    "extension/fake_id0/mk.o", "extension/fake_id0/stat.o",
    "extension/fake_id0/helper_functions.o", "extension/fake_id0/fake_id0.o",
    "extension/hidden_files/hidden_files.o",
    "extension/mountinfo/mountinfo.o",
    "extension/port_switch/port_switch.o",
    "extension/sysvipc/sysvipc.o", "extension/sysvipc/sysvipc_msg.o",
    "extension/sysvipc/sysvipc_sem.o", "extension/sysvipc/sysvipc_shm.o",
    "extension/link2symlink/link2symlink.o",
    "extension/fix_symlink_size/fix_symlink_size.o",
    # loader/loader-wrapped.o added by build_loader step
]


def _tool(base: Path) -> str:
    """Resolve an NDK tool to the Windows-executable form (.cmd/.exe).
    Prefers .exe so Python's CreateProcess can launch it directly (the .cmd
    wrappers require shell=True on native Windows Python)."""
    if base.is_file():
        return str(base)
    for ext in (".exe", ".cmd", ".bat"):
        cand = Path(str(base) + ext)
        if cand.is_file():
            return str(cand)
    return str(base)


def _ndk_clang(triple: str, api: int) -> tuple[str, str]:
    """Return (clang_exe_path, target_flag). Uses clang.exe directly with
    --target= rather than the per-target .cmd wrappers."""
    clang = BIN / "clang.exe"
    if not clang.is_file():
        raise SystemExit(f"ERROR: NDK clang.exe not found at {clang}")
    return str(clang), f"--target={triple}-linux-android{api}"


def run(cmd, **kw):
    cmd = [str(c) for c in cmd]
    print("  $", " ".join(cmd[:7]), "..." if len(cmd) > 7 else "")
    return subprocess.run(cmd, check=True, **kw)


def run_out(cmd, **kw) -> str:
    cmd = [str(c) for c in cmd]
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    return r.stdout + r.stderr


def build_talloc(triple: str, api: int, out_dir: Path) -> Path:
    """Build libtalloc.so (single C file) for Bionic.

    talloc.c includes lib/replace/replace.h, which is samba's portability layer.
    It normally expects a waf-generated config.h; we synthesize a minimal one
    declaring the features Bionic provides (stdbool, stdint, real bool, va_copy,
    snprintf, asprintf). This is the same approach pkg-config-driven minimal
    builds use."""
    cc, target = _ndk_clang(triple, api)
    talloc_c = TALLOC_SRC / "talloc.c"
    talloc_h = TALLOC_SRC / "talloc.h"
    replace_h = TALLOC_SRC / "lib" / "replace" / "replace.h"
    if not talloc_c.is_file():
        raise SystemExit(f"ERROR: talloc.c not found at {talloc_c}")
    if not replace_h.is_file():
        raise SystemExit(f"ERROR: replace.h not found at {replace_h}")
    out_dir.mkdir(parents=True, exist_ok=True)

    # Minimal config.h for replace.h on Bionic. replace.h is samba's portability
    # layer; it probes for many features via autoconf. On Bionic we declare what
    # the platform provides so replace.h does not fall back to (conflicting)
    # local typedefs.
    config_h = out_dir / "config.h"
    config_h.write_text(
        '/* Minimal config.h for talloc on Android Bionic (generated by build_proot.py). */\n'
        '#ifndef _CONFIG_H\n#define _CONFIG_H\n'
        '#define HAVE_STDBOOL_H 1\n'
        '#define HAVE_STDINT_H 1\n'
        '#define HAVE_INTTYPES_H 1\n'
        '#define HAVE_STDDEF_H 1\n'
        '#define HAVE_BOOL 1\n'
        '#define HAVE_INTPTR_T 1\n'
        '#define HAVE_UINTPTR_T 1\n'
        '#define HAVE_PTRDIFF_T 1\n'
        '#define HAVE_VA_COPY 1\n'
        '#define HAVE_STRDUP 1\n'
        '#define HAVE_SETJMP_H 1\n'
        '#define HAVE_SNPRINTF 1\n'
        '#define HAVE_VSNPRINTF 1\n'
        '#define HAVE_C99_VSNPRINTF 1\n'
        '#define HAVE_ASPRINTF 1\n'
        '#define HAVE_VASPRINTF 1\n'
        '#define HAVE_STRNLEN 1\n'
        '#define HAVE_STRERROR_R 1\n'
        '#define HAVE_USLEEP 1\n'
        '#define HAVE_MALLOC 1\n'
        '#define HAVE_CALLOC 1\n'
        '#define HAVE_REALLOC 1\n'
        '#define HAVE_MMAP 1\n'
        '#define HAVE_MUNMAP 1\n'
        '#define HAVE_FDATASYNC 1\n'
        '#define HAVE_MEMMOVE 1\n'
        '#define __STDC_WANT_LIB_EXT1__ 1\n'
        '/* talloc version (from wscript VERSION 2.4.2). */\n'
        '#define TALLOC_BUILD_VERSION_MAJOR 2\n'
        '#define TALLOC_BUILD_VERSION_MINOR 4\n'
        '#define TALLOC_BUILD_VERSION_RELEASE 2\n'
        '#endif /* _CONFIG_H */\n')

    obj = out_dir / "talloc.o"
    so = out_dir / "libtalloc.so"
    print(f"== building libtalloc.so ({triple}) ==")
    run([cc, target, "-O2", "-fPIC", "-D_GNU_SOURCE",
         "-include", "string.h", "-include", "dlfcn.h", "-include", "limits.h",
         f"-I{out_dir}", f"-I{TALLOC_SRC}", f"-I{TALLOC_SRC / 'lib' / 'replace'}",
         "-Wno-unused-function", "-Wno-deprecated-declarations",
         "-Wno-format-truncation", "-Wno-unused-but-set-variable",
         "-c", talloc_c, "-o", obj])
    run([cc, target, "-shared", "-Wl,-soname,libtalloc.so",
         "-Wl,-z,max-page-size=0x4000", obj, "-o", so])
    shutil.copy(talloc_h, out_dir / "talloc.h")
    print(f"  -> {so} ({so.stat().st_size} bytes)")
    return so


def _define_from_arch_h(cc: str, target: str, src_dir: Path, name: str,
                        m32: str = "") -> str:
    """Replicate $(define_from_arch.h): preprocess arch.h and grep a macro."""
    arch_h = src_dir / "arch.h"
    out = run_out([cc, target, m32, "-E", "-dM", "-DNO_LIBC_HEADER", arch_h])
    for line in out.splitlines():
        m = re.match(rf"^#define\s+{name}\s+(.+)$", line.strip())
        if m:
            return m.group(1).strip()
    return ""


def build_loader(cc: str, target: str, strip: str, objcopy: str, objdump: str,
                 src_dir: Path, build_dir: Path, m32: str,
                 cppflags: list, cflags: list) -> Path:
    """Build the in-tracee loader and return the stripped loader .exe path."""
    loader_arch_cflags = _define_from_arch_h(cc, target, src_dir, "LOADER_ARCH_CFLAGS", m32)
    loader_address = _define_from_arch_h(cc, target, src_dir, "LOADER_ADDRESS", m32)
    print(f"  loader: m32='{m32}' arch_cflags='{loader_arch_cflags}' addr='{loader_address}'")

    loader_cflags = ["-fPIC", "-ffreestanding"]
    if loader_arch_cflags:
        loader_cflags += loader_arch_cflags.split()
    loader_ldflags = ["-static", "-nostdlib",
                      f"-Wl,--build-id=none,-Ttext={loader_address},--rosegment,-z,noexecstack"]

    suffix = "-m32" if m32 else ""
    loader_exe = build_dir / f"loader{suffix}.exe"
    loader_src = build_dir / f"loader/loader{suffix}"
    (build_dir / "loader").mkdir(parents=True, exist_ok=True)
    loader_o = build_dir / f"loader/loader{suffix}.o"
    asm_o = build_dir / f"loader/assembly{suffix}.o"
    m32_arg = [m32] if m32 else []
    run([cc, target] + m32_arg + cppflags + cflags + loader_cflags +
        ["-MD", "-c", src_dir / "loader/loader.c", "-o", loader_o])
    run([cc, target] + m32_arg + cppflags + cflags + loader_cflags +
        ["-MD", "-c", src_dir / "loader/assembly.S", "-o", asm_o])
    run([cc, target] + m32_arg + ["-o", loader_src, loader_o, asm_o] + loader_ldflags)
    shutil.copy(loader_src, loader_exe)
    run([strip, loader_exe])
    return loader_exe


def objify(objcopy: str, objdump: str, binary_path: Path, ref_obj: Path,
           out_obj: Path):
    """Replicate the OBJIFY make rule: embed binary_path into out_obj using the
    ELF format/architecture read from ref_obj via objdump.

    objcopy names the generated symbols after the INPUT filename
    (_binary_<name>_start/_end), so we must pass a bare filename (not a full
    path) as the input — otherwise the symbols become _binary_<dir>_<...>. We
    chdir to the binary's directory and pass just the basename."""
    info = run_out([objdump, "-f", ref_obj])
    fmt = ""
    arch = ""
    for line in info.splitlines():
        m = re.search(r"file format\s+(\S+)", line)
        if m:
            fmt = m.group(1)
        am = re.search(r"architecture:\s+(\S+)", line)
        if am:
            arch = am.group(1).rstrip(",").strip()
    if not fmt or not arch:
        raise SystemExit(f"ERROR: could not parse objdump -f output:\n{info}")
    print(f"  objify: format={fmt} arch={arch} binary={binary_path.name}")
    # Run objcopy with cwd = binary's dir, passing the bare filename, so the
    # generated symbols are _binary_<basename-without-path>_start. The proot
    # source expects _binary_loader_exe_start / _binary_loader_m32_exe_start.
    out_abs = out_obj.resolve()
    bin_dir = binary_path.parent
    bin_name = binary_path.name
    cmd = [objcopy, "--input-target=binary", f"--output-target={fmt}",
           f"--binary-architecture={arch}", bin_name, str(out_abs)]
    print("  $", " ".join(str(c) for c in cmd[:8]), "(cwd=%s)" % bin_dir)
    r = subprocess.run(cmd, cwd=str(bin_dir))
    if r.returncode != 0:
        raise SystemExit(f"ERROR: objcopy failed (rc={r.returncode})")


def build_proot(triple: str, api: int, talloc_dir: Path, out_dir: Path) -> Path:
    """Build proot PIE + embed loader. Returns the libproot.so path."""
    cc, target = _ndk_clang(triple, api)
    strip = _tool(BIN / "llvm-strip")
    objcopy = _tool(BIN / "llvm-objcopy")
    objdump = _tool(BIN / "llvm-objdump")
    for tool in (strip, objcopy, objdump):
        if not Path(tool).is_file():
            raise SystemExit(f"ERROR: NDK tool not found: {tool}")
    if not PROOT_SRC.is_dir():
        raise SystemExit(f"ERROR: proot source not found at {PROOT_SRC}")
    out_dir.mkdir(parents=True, exist_ok=True)
    src_dir = PROOT_SRC / "src"
    build_dir = out_dir / "build"
    if build_dir.exists():
        shutil.rmtree(build_dir)
    build_dir.mkdir(parents=True)

    cppflags = ["-D_FILE_OFFSET_BITS=64", "-D_GNU_SOURCE",
                f"-I{build_dir}", f"-I{src_dir}",
                "-DARG_MAX=131072", '-DVERSION="5.1.107.89"', f"-I{talloc_dir}"]
    cflags = ["-Wall", "-Wextra", "-O2", "-fPIC",
              "-Wno-unused-parameter", "-Wno-unused-variable",
              # proot's Android-specific extension (ashmem_memfd.c) has a couple
              # of missing <string.h> includes; the termux build tolerates these.
              "-Wno-error=implicit-function-declaration",
              "-Wno-implicit-function-declaration"]
    ldflags = [f"-L{talloc_dir}", "-ltalloc",
               "-Wl,-z,noexecstack", "-Wl,-z,max-page-size=0x4000"]

    (build_dir / "build.h").write_text(
        '/* auto-generated by build_proot.py */\n'
        '#ifndef BUILD_H\n#define BUILD_H\n'
        '#define HAVE_PROCESS_VM\n'
        '#define HAVE_SECCOMP_FILTER\n'
        '#endif /* BUILD_H */\n')

    print(f"== building proot PIE ({triple}) ==")
    obj_paths = []
    for obj_rel in PROOT_OBJECTS:
        c_src = src_dir / obj_rel.replace(".o", ".c")
        o_dst = build_dir / obj_rel
        o_dst.parent.mkdir(parents=True, exist_ok=True)
        run([cc, target] + cppflags + cflags + ["-MD", "-c", c_src, "-o", o_dst])
        obj_paths.append(o_dst)

    cli_o = build_dir / "cli/cli.o"
    loader_exe = build_loader(cc, target, strip, objcopy, objdump, src_dir,
                              build_dir, "", cppflags, cflags)
    loader_wrapped = build_dir / "loader/loader-wrapped.o"
    objify(objcopy, objdump, loader_exe, cli_o, loader_wrapped)
    obj_paths.append(loader_wrapped)

    has_pokedata = _define_from_arch_h(cc, target, src_dir, "HAS_POKEDATA_WORKAROUND")
    has_loader32 = _define_from_arch_h(cc, target, src_dir, "HAS_LOADER_32BIT")
    if has_pokedata:
        print("  HAS_POKEDATA_WORKAROUND: building loader-info.o")
        readelf = _tool(BIN / "llvm-readelf")
        loader_info_c = build_dir / "loader/loader-info.c"
        loader_info_c.parent.mkdir(parents=True, exist_ok=True)
        awk_script = src_dir / "loader/loader-info.awk"
        loader_src = build_dir / "loader/loader"
        r = subprocess.run([readelf, "-s", loader_src], capture_output=True, text=True)
        awk = subprocess.run(["awk", "-f", awk_script], input=r.stdout,
                             capture_output=True, text=True)
        loader_info_c.write_text(awk.stdout)
        info_o = build_dir / "loader/loader-info.o"
        run([cc, target] + cppflags + cflags + ["-MD", "-c", loader_info_c, "-o", info_o])
        obj_paths.append(info_o)
    if has_loader32:
        print("  HAS_LOADER_32BIT: building 32-bit loader too")
        loader32_exe = build_loader(cc, target, strip, objcopy, objdump, src_dir,
                                    build_dir, "-m32", cppflags, cflags)
        loader32_wrapped = build_dir / "loader/loader-m32-wrapped.o"
        objify(objcopy, objdump, loader32_exe, cli_o, loader32_wrapped)
        obj_paths.append(loader32_wrapped)

    proot = build_dir / "proot"
    print(f"== linking proot ({len(obj_paths)} objects) ==")
    run([cc, target, "-o", proot] + obj_paths + ldflags)

    dest = out_dir / "libproot.so"
    shutil.copy(proot, dest)
    print(f"  -> {dest} ({dest.stat().st_size} bytes)")

    # Also stage the in-tracee helper loader as libproot_loader.so. proot's
    # default (embedded-loader) build extracts this loader to PROOT_TMP_DIR at
    # runtime and execves it — that requires writable + executable storage,
    # which the Android app domain forbids (noexec on filesDir/cacheDir). By
    # setting PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so at launch time,
    # proot uses this APK-managed (+x, immutable) copy directly and skips the
    # extraction. This is the freestanding loader ELF (same bytes as the
    # objcopy-embedded loader), renamed lib*.so so AGP extracts it with +x.
    loader_dest = out_dir / "libproot_loader.so"
    shutil.copy(loader_exe, loader_dest)
    print(f"  -> {loader_dest} ({loader_dest.stat().st_size} bytes) [PROOT_LOADER]")

    # If the 32-bit loader was built, stage it too (PROOT_LOADER_32). Needed
    # only if Wine spawns 32-bit guest processes (i386-windows PE via thunks).
    if has_loader32:
        loader32_dest = out_dir / "libproot_loader32.so"
        shutil.copy(loader32_exe, loader32_dest)
        print(f"  -> {loader32_dest} ({loader32_dest.stat().st_size} bytes) [PROOT_LOADER_32]")

    return dest


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--abi", required=True, choices=list(ABIS))
    ap.add_argument("--check-only", action="store_true")
    args = ap.parse_args()
    triple, api = ABIS[args.abi]
    build_dir = NATIVE / f".build-{triple}" / "proot-build"
    stage_dir = NATIVE / f".build-{triple}" / "proot-stage"

    if args.check_only:
        ok = PROOT_SRC.is_dir() and TALLOC_SRC.is_dir()
        print(f"proot src : {PROOT_SRC} {'OK' if PROOT_SRC.is_dir() else 'MISSING'}")
        print(f"talloc src: {TALLOC_SRC} {'OK' if TALLOC_SRC.is_dir() else 'MISSING'}")
        return 0 if ok else 1
    if not PROOT_SRC.is_dir():
        print(f"ERROR: proot source missing at {PROOT_SRC}", file=sys.stderr)
        return 2
    if not TALLOC_SRC.is_dir():
        print(f"ERROR: talloc source missing at {TALLOC_SRC}", file=sys.stderr)
        return 2

    talloc_so = build_talloc(triple, api, build_dir)
    proot_so = build_proot(triple, api, build_dir, stage_dir)
    shutil.copy(talloc_so, stage_dir / "libtalloc.so")
    # libproot_loader.so (+ libproot_loader32.so if built) are staged inside
    # build_proot directly (they live in the build dir alongside libproot.so).
    print(f"\n== artifacts staged at {stage_dir} ==")
    for f in sorted(stage_dir.iterdir()):
        print(f"  {f.name} ({f.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
