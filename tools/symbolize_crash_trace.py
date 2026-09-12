#!/usr/bin/env python3
"""Resolve module!RVA frames from %LOCALAPPDATA%\\PocketRealm\\crash-trace.txt
against the pocket_world_runtime PDB (offline dbghelp symbolization).

Usage: python tools/symbolize_crash_trace.py [trace-path]
"""
import ctypes
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DLL = REPO / "native" / ".build-win-x86_64" / "pocket-runtime-build" / "pocket_world_runtime.dll"
PDB_DIR = str(DLL.parent)
DEFAULT_TRACE = Path(os.environ["LOCALAPPDATA"]) / "PocketRealm" / "crash-trace.txt"

PROCESS_CALLBACKS_ENABLED = 0x80000000
SYMOPT_UNDNAME = 0x2
SYMOPT_LOAD_LINES = 0x10
LOAD_LIBRARY_AS_DATAFILE = 0x2
LOAD_LIBRARY_AS_IMAGE_RESOURCE = 0x20


def main() -> int:
    trace = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_TRACE
    lines = trace.read_text(encoding="utf-8", errors="replace").splitlines()
    frames = [l for l in lines if l.strip().startswith("#")]

    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    dbghelp = ctypes.WinDLL("dbghelp")

    dbghelp.SymSetOptions(SYMOPT_UNDNAME | SYMOPT_LOAD_LINES)

    class SYMBOL_INFO(ctypes.Structure):
        _fields_ = [
            ("SizeOfStruct", ctypes.c_ulong),
            ("TypeIndex", ctypes.c_ulong),
            ("Reserved", ctypes.c_ulonglong * 2),
            ("Index", ctypes.c_ulong),
            ("Size", ctypes.c_ulong),
            ("ModBase", ctypes.c_ulonglong),
            ("Flags", ctypes.c_ulonglong),
            ("Value", ctypes.c_ulonglong),
            ("Address", ctypes.c_ulonglong),
            ("Register", ctypes.c_ulong),
            ("Scope", ctypes.c_ulong),
            ("Tag", ctypes.c_ulong),
            ("NameLen", ctypes.c_ulong),
            ("MaxNameLen", ctypes.c_ulong),
            ("Name", ctypes.c_char * 1),
        ]

    name_offset = SYMBOL_INFO.Name.offset
    buffer_size = ctypes.sizeof(SYMBOL_INFO) + 255
    handle = k32.GetCurrentProcess()
    hlib = k32.LoadLibraryExW(str(DLL), None,
                              LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_IMAGE_RESOURCE)
    if not hlib:
        print(f"cannot load DLL as data file: {DLL}", file=sys.stderr)
        return 1
    dbghelp.SymLoadModuleExW.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                         ctypes.c_wchar_p, ctypes.c_wchar_p,
                                         ctypes.c_ulonglong, ctypes.c_ulong,
                                         ctypes.c_void_p, ctypes.c_ulong]
    dbghelp.SymInitializeW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_bool]
    dbghelp.SymFromAddrW.argtypes = [ctypes.c_void_p, ctypes.c_ulonglong,
                                     ctypes.POINTER(ctypes.c_ulonglong),
                                     ctypes.c_void_p]
    if not dbghelp.SymInitializeW(handle, PDB_DIR, False):
        print("SymInitializeW failed", file=sys.stderr)
        return 1
    base = ctypes.c_ulonglong(0x180000000)  # standard x64 PE preferred base
    loaded = dbghelp.SymLoadModuleExW(handle, None, str(DLL), None, base, 0, None, 0)
    if not loaded:
        print("SymLoadModuleExW failed", file=sys.stderr)
        return 1

    frame_re = re.compile(r"^(#\d+)\s+(\S+?)!([0-9A-Fa-f]+)")
    for line in lines:
        match = frame_re.match(line.strip())
        if not match:
            print(line)
            continue
        frame, module, rva_text = match.groups()
        rva = int(rva_text, 16)
        if "pocket_world_runtime" not in module:
            print(f"  {frame} {module}!0x{rva:x}")
            continue
        info_buffer = ctypes.create_string_buffer(buffer_size)
        info = ctypes.cast(info_buffer, ctypes.POINTER(SYMBOL_INFO)).contents
        info.SizeOfStruct = ctypes.sizeof(SYMBOL_INFO)
        info.MaxNameLen = 255
        displacement = ctypes.c_ulonglong(0)
        ok = dbghelp.SymFromAddrW(handle, ctypes.c_ulonglong(base.value + rva),
                                  ctypes.byref(displacement), ctypes.byref(info))
        if ok:
            raw = ctypes.string_at(ctypes.addressof(info_buffer) + name_offset, 510)
            name = raw.decode("utf-16-le", "replace").split("\x00", 1)[0]
            print(f"  {frame} {name} + 0x{displacement.value:x}")
        else:
            print(f"  {frame} {module}!0x{rva:x} <no-symbol>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
