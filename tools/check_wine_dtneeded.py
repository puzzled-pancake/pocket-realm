#!/usr/bin/env python3
"""Derive the actual runtime dependency closure of the pinned Kron4ek Wine
archive and cross-check it against the lockfile.

Two dep layers are measured, not assumed:

  1. HARD DT_NEEDED — readelf-equivalent walk of the dynamic section of every
     ELF under bin/ and lib/wine/x86_64-unix/. These MUST resolve at load time.
  2. RUNTIME dlopen set — Wine resolves optional libs (freetype, fontconfig,
     libGL, vulkan, libXrandr, ...) via WINE_CHECK_SONAME at first use. The
     set is the union of SONAMEs Wine's build probes; we check the subset the
     O06 spike needs (freetype, fontconfig) is present in the lockfile, and
     report the rest as "optional/absent-ok" unless --strict.

The mandatory closure for a windowed X11/GDI PE (wineboot + a window under
winex11.drv) is the hard DT_NEEDED set + the runtime set Wine cannot proceed
without (libX11 — winex11.drv NtTerminateProcess'es if absent; freetype +
fontconfig for glyph rendering).

Cross-checks every mandatory SONAME against schemas/wine-runtime-lockfile.json.
The spike does NOT proceed with an unmet mandatory dep.

Usage:
  python3 tools/check_wine_dtneeded.py                  # report + cross-check
  python3 tools/check_wine_dtneeded.py --strict         # fail on any optional dep absent too
  python3 tools/check_wine_dtneeded.py --json           # machine-readable
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXTRACT_ROOT = ROOT / "native" / ".providers-extracted"
LOCKFILE = ROOT / "schemas" / "wine-runtime-lockfile.json"
SOURCES = ROOT / "schemas" / "sources.json"

WINE_ID = "wine-kron4ek-11-14-vanilla-wow64"

# SONAMEs Wine resolves at runtime via WINE_CHECK_SONAME (dlopen), not via
# DT_NEEDED. Only the ones needed for the O06 spike (wineboot + windowed X11/GDI
# PE, audio off) are MANDATORY here; the rest are OPTIONAL/absent-ok.
RUNTIME_MANDATORY = {
    "libfreetype.so.6": "font rendering (gdi32); Kron4ek built --with-freetype",
    "libfontconfig.so.1": "font selection; Kron4ek built --with-fontconfig",
    "libz.so.1": "DT_NEEDED by the mandatory libfreetype runtime",
    "libbz2.so.1.0": "DT_NEEDED by the mandatory libfreetype runtime",
    "libpng16.so.16": "DT_NEEDED by the mandatory libfreetype runtime",
    "libbrotlidec.so.1": "DT_NEEDED by the mandatory libfreetype runtime",
    "libbrotlicommon.so.1": "DT_NEEDED by libbrotlidec",
    "libexpat.so.1": "DT_NEEDED by the mandatory libfontconfig runtime",
}
RUNTIME_OPTIONAL = {
    "libXrandr.so.2", "libXrender.so.1", "libXcomposite.so.1", "libXinerama.so.1",
    "libXi.so.6", "libXfixes.so.3", "libXcursor.so.1", "libXxf86vm.so.1",
    "libGL.so.1", "libEGL.so.1", "libvulkan.so.1", "libgnutls.so.30",
    "libpulse.so.0", "libasound.so.2",
}


def find_wine_root() -> Path | None:
    base = EXTRACT_ROOT / WINE_ID
    if not base.is_dir():
        return None
    # The tarball extracts a versioned subdir.
    subs = [p for p in base.iterdir() if p.is_dir()]
    return subs[0] if len(subs) == 1 else base


def parse_elf_dynamic(path: Path) -> tuple[list[str], str | None]:
    """Return (needed_sonames, interp). Pure-Python ELF parser (no readelf dep).

    Handles 64-bit LE ELF only (the Wine archive is x86-64). Returns ([], None)
    for non-ELF files.
    """
    try:
        with path.open("rb") as f:
            ident = f.read(64)
        if len(ident) < 64 or ident[:4] != b"\x7fELF":
            return [], None
        if ident[4] != 2:  # not ELF64
            return [], None
        if ident[5] != 1:  # not little-endian
            return [], None
        with path.open("rb") as f:
            data = f.read()
        # ELF64 header
        e_phoff = struct.unpack_from("<Q", data, 32)[0]
        e_phentsize = struct.unpack_from("<H", data, 54)[0]
        e_phnum = struct.unpack_from("<H", data, 56)[0]
        e_shoff = struct.unpack_from("<Q", data, 40)[0]
        e_shentsize = struct.unpack_from("<H", data, 58)[0]
        e_shnum = struct.unpack_from("<H", data, 60)[0]
        e_shstrndx = struct.unpack_from("<H", data, 62)[0]

        interp = None
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type = struct.unpack_from("<I", data, off)[0]
            if p_type == 3:  # PT_INTERP
                p_offset = struct.unpack_from("<Q", data, off + 8)[0]
                p_filesz = struct.unpack_from("<Q", data, off + 32)[0]
                interp = data[p_offset:p_offset + p_filesz - 1].decode("utf-8", "replace")

        if e_shoff == 0 or e_shnum == 0:
            return [], interp

        # Read section headers to find .dynamic and .dynstr.
        def sh(off):
            sh_name = struct.unpack_from("<I", data, off)[0]
            sh_type = struct.unpack_from("<I", data, off + 4)[0]
            sh_offset = struct.unpack_from("<Q", data, off + 24)[0]
            sh_size = struct.unpack_from("<Q", data, off + 32)[0]
            sh_entsize = struct.unpack_from("<Q", data, off + 56)[0]
            return sh_name, sh_type, sh_offset, sh_size, sh_entsize

        sections = [sh(e_shoff + i * e_shentsize) for i in range(e_shnum)]
        # .shstrtab for section names
        _, _, strtab_off, strtab_size, _ = sections[e_shstrndx]
        strtab = data[strtab_off:strtab_off + strtab_size]

        def secname(idx):
            end = strtab.index(b"\0", idx)
            return strtab[idx:end].decode("utf-8", "replace")

        dyn_off = dyn_size = dyn_entsize = dynstr_off = dynstr_size = None
        for nm_idx, sh_type, sh_off, sh_sz, sh_ent in sections:
            nm = secname(nm_idx)
            if nm == ".dynamic":
                dyn_off, dyn_size, dyn_entsize = sh_off, sh_sz, sh_ent
            elif nm == ".dynstr":
                dynstr_off, dynstr_size = sh_off, sh_sz

        if dyn_off is None or dynstr_off is None or dyn_entsize == 0:
            return [], interp
        dynstr = data[dynstr_off:dynstr_off + dynstr_size]

        def dynstr_at(off):
            end = dynstr.index(b"\0", off)
            return dynstr[off:end].decode("utf-8", "replace")

        needed = []
        n = dyn_size // dyn_entsize
        for i in range(n):
            doff = dyn_off + i * dyn_entsize
            d_tag = struct.unpack_from("<q", data, doff)[0]  # signed
            d_val = struct.unpack_from("<Q", data, doff + 8)[0]
            if d_tag == 1:  # DT_NEEDED
                needed.append(dynstr_at(d_val))
            elif d_tag == 0:  # DT_NULL
                break
        return needed, interp
    except (OSError, struct.error, ValueError):
        return [], None


def collect_hard_needed(wine_root: Path) -> dict[str, list[str]]:
    """Walk bin/ + lib/wine/x86_64-unix/; return {elf_path: [needed_sonames]}.
    Filters out Wine-internal *.so (ntdll.so etc.) and platform glibc from the
    per-file list but keeps them in a separate 'all' set for reporting."""
    out: dict[str, list[str]] = {}
    for sub in ("bin", "lib/wine/x86_64-unix"):
        d = wine_root / sub
        if not d.is_dir():
            continue
        for p in sorted(d.iterdir()):
            if not p.is_file():
                continue
            needed, _ = parse_elf_dynamic(p)
            if needed:
                out[str(p.relative_to(wine_root))] = needed
    return out


# Wine .so modules that are OPTIONAL drivers/backends. Their hard DT_NEEDED
# deps (gstreamer, pulse, alsa, gphoto, usb, wayland, xkbcommon, sane, capi,
# opencl, pcap, pcsclite, avcodec, etc.) are NOT required for the WineD3D-safe
# audio-off spike: Wine loads these lazily and degrades if the module or its
# deps are absent. Either omit the module from the APK or accept the load
# failure; either way the core (ntdll/winex11/loader) is unaffected.
OPTIONAL_WINE_MODULES = {
    "winegstreamer.so", "winedmo.so", "winealsa.so", "winepulse.so",
    "gphoto2.so", "winebus.so", "wineusb.so", "winewayland.so",
    "wpcap.so", "winscard.so", "sane.so", "capi2032.so", "opencl.so",
    "wineandroid.so",  # Android display driver (we use the X server path)
}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--strict", action="store_true",
                    help="fail on any optional runtime dep absent from the lockfile too")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args()

    wine_root = find_wine_root()
    if wine_root is None:
        msg = (f"Wine archive not extracted at {EXTRACT_ROOT / WINE_ID}. "
               f"Run: python tools/fetch_provider.py {WINE_ID}")
        if args.json:
            print(json.dumps({"ok": False, "error": msg}))
        else:
            print(msg, file=sys.stderr)
        return 2

    hard = collect_hard_needed(wine_root)

    # Split hard deps by whether their owning module is a core Wine module or
    # an optional driver/backend. A dep is MANDATORY if any CORE module needs
    # it; it is CONDITIONAL if only optional modules need it (Wine loads those
    # lazily and degrades if absent — fine for the WineD3D-safe audio-off spike).
    core_hard: set[str] = set()
    optional_module_hard: set[str] = set()
    for elf_path, needed in hard.items():
        mod_name = Path(elf_path).name
        bucket = optional_module_hard if mod_name in OPTIONAL_WINE_MODULES else core_hard
        bucket.update(needed)

    # Wine-internal modules (no version, endswith .so, under lib/wine): not
    # external deps — they are part of Wine's own closure.
    wine_internal = {s for s in (core_hard | optional_module_hard)
                     if s.endswith(".so") and "." not in s[:-3]}
    # Platform glibc supplied by the OS/loader closure (glibc package).
    # libresolv.so.2 is a glibc subsidiary (built by the glibc package), like
    # libc/libm/libdl/libpthread/librt and the loader itself.
    glibc_sonames = {"libc.so.6", "libdl.so.2", "libpthread.so.0",
                     "libm.so.6", "librt.so.1", "ld-linux-x86-64.so.2",
                     "libresolv.so.2"}
    external_core = core_hard - wine_internal - glibc_sonames
    external_optional_mod = optional_module_hard - wine_internal - glibc_sonames

    # Load lockfile to resolve what the closure provides.
    lock = json.loads(LOCKFILE.read_text(encoding="utf-8"))
    provided: set[str] = set()
    for pkg in lock["packages"]:
        provided.update(pkg.get("provides", []))

    # Classify external hard deps. Only CORE-module deps are mandatory.
    hard_met = {s for s in external_core if s in provided or s in glibc_sonames}
    hard_unmet = external_core - hard_met

    # Runtime (dlopen) set: check mandatory present; report optional.
    runtime_mandatory_met = {s for s in RUNTIME_MANDATORY if s in provided}
    runtime_mandatory_unmet = set(RUNTIME_MANDATORY) - runtime_mandatory_met
    runtime_optional_present = {s for s in RUNTIME_OPTIONAL if s in provided}
    runtime_optional_absent = RUNTIME_OPTIONAL - runtime_optional_present

    mandatory_unmet = hard_unmet | runtime_mandatory_unmet
    # Conditional (optional-module) deps are never mandatory unless --strict.
    ok = not mandatory_unmet
    if args.strict:
        ok = ok and not external_optional_mod and not runtime_optional_absent

    if args.json:
        print(json.dumps({
            "ok": ok,
            "wine_root": str(wine_root.relative_to(ROOT)),
            "elf_count": len(hard),
            "core_hard_external": sorted(external_core),
            "core_hard_unmet": sorted(hard_unmet),
            "optional_module_hard": sorted(external_optional_mod),
            "optional_wine_modules": sorted(OPTIONAL_WINE_MODULES),
            "runtime_mandatory": dict(RUNTIME_MANDATORY),
            "runtime_mandatory_unmet": sorted(runtime_mandatory_unmet),
            "runtime_optional_absent": sorted(runtime_optional_absent),
            "lockfile_provides": sorted(provided),
        }, indent=2))
        return 0 if ok else 1

    # Human-readable report.
    print(f"Wine root: {wine_root.relative_to(ROOT)}")
    print(f"ELFs scanned: {len(hard)} (bin/ + lib/wine/x86_64-unix/)")
    print(f"\nHard DT_NEEDED — CORE modules (mandatory, {len(external_core)}):")
    for s in sorted(external_core):
        status = "OK   " if s in hard_met else "UNMET"
        print(f"  {status} {s}")
    if hard_unmet:
        print(f"\n  UNMET core hard deps: {sorted(hard_unmet)}")

    print(f"\nHard DT_NEEDED — OPTIONAL feature modules ({len(external_optional_mod)}):")
    print("  (Wine loads these lazily; absent is fine for the WineD3D-safe audio-off spike.")
    print("   Either omit the module from the APK or accept the load failure.)")
    for s in sorted(external_optional_mod):
        present = "OK   " if s in provided else "absent"
        print(f"  {present} {s}")

    print(f"\nRuntime (dlopen) MANDATORY ({len(RUNTIME_MANDATORY)}):")
    for s in sorted(RUNTIME_MANDATORY):
        status = "OK   " if s in provided else "UNMET"
        why = RUNTIME_MANDATORY[s]
        print(f"  {status} {s:24} ({why})")

    print(f"\nRuntime (dlopen) OPTIONAL present in lockfile ({len(runtime_optional_present)}):")
    for s in sorted(runtime_optional_present):
        print(f"  {s}")
    print(f"Runtime OPTIONAL absent (fine for WineD3D-safe audio-off spike) ({len(runtime_optional_absent)}):")
    for s in sorted(runtime_optional_absent):
        print(f"  {s}")

    print(f"\nLockfile provides ({len(provided)}): {sorted(provided)}")
    if mandatory_unmet:
        print(f"\nFAIL: mandatory core deps unmet: {sorted(mandatory_unmet)}", file=sys.stderr)
        print("The spike cannot proceed with an unmet mandatory dep. "
              "Extend schemas/wine-runtime-lockfile.json after review.", file=sys.stderr)
        return 1
    if args.strict and (external_optional_mod or runtime_optional_absent):
        print(f"\n--strict: optional deps absent (modules: {sorted(external_optional_mod)}, "
              f"runtime: {sorted(runtime_optional_absent)})", file=sys.stderr)
        return 1
    print("\nOK: mandatory CORE closure satisfied. Optional/conditional deps may be absent for the spike.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
