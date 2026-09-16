#!/usr/bin/env python3
"""Merge <longPathAware>true</longPathAware> into a PE's RT_MANIFEST.

mt.exe -outputresource proved unusable for this (silently
no-ops or fails with `general error c101008d ... Access is denied` on the
\\\\?\\-prefixed path it builds), so this helper does the same job through
kernel32's BeginUpdateResourceW/UpdateResourceW/EndUpdateResourceW via
ctypes, reading the current manifest bytes with pefile.

Idempotent: an exe that already carries the setting is left untouched.

Usage: python tools/win_manifest_longpath.py <exe>
Exit 0 = flag present (merged or already there); 1 = honest failure.
"""
from __future__ import annotations

import ctypes
from pathlib import Path
import sys

RT_MANIFEST = 24
SETTING = (
    '<longPathAware xmlns="http://schemas.microsoft.com/SMI/2016/'
    'WindowsSettings">true</longPathAware>'
)


def current_manifest(exe: Path) -> tuple[bytes, int]:
    """The RT_MANIFEST/#1 blob plus its language id. The PE handle is
    CLOSED before returning: BeginUpdateResourceW needs exclusive write
    access, and pefile's open read handle blocks it."""
    import pefile

    pe = pefile.PE(str(exe), fast_load=True)
    try:
        pe.parse_data_directories(
            directories=[pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_RESOURCE"]])
        for entry in pe.DIRECTORY_ENTRY_RESOURCE.entries:
            if entry.id != RT_MANIFEST:
                continue
            for sub in entry.directory.entries:
                if sub.id != 1:
                    continue
                for lang in sub.directory.entries:
                    data = pe.get_data(
                        lang.data.struct.OffsetToData, lang.data.struct.Size)
                    return data, lang.id
    finally:
        pe.close()
    raise RuntimeError(f"{exe.name}: no RT_MANIFEST/#1 resource found")


def merged(body: bytes) -> bytes | None:
    text = body.decode("utf-8-sig")
    if "longPathAware" in text:
        return None  # already applied
    # Merge INTO the existing windowsSettings element when one is present
    # (jpackage's launcher carries an asmv3 one with the DPI settings):
    # appending a second sibling element is non-canonical and a plausible
    # SxS poison on other manifest shapes.
    # The closers must be matched LONGEST-FIRST: "</windowsSettings>" is a
    # substring of "</asmv3:windowsSettings>" and a naive replace would
    # split the element and corrupt the manifest (SxS refuses to load it).
    if "</asmv3:windowsSettings>" in text:
        text = text.replace(
            "</asmv3:windowsSettings>",
            SETTING + "</asmv3:windowsSettings>", 1)
    elif "</windowsSettings>" in text:
        text = text.replace("</windowsSettings>", SETTING + "</windowsSettings>", 1)
    elif "</asmv3:application>" in text:
        text = text.replace(
            "</asmv3:application>",
            '<asmv3:windowsSettings xmlns="http://schemas.microsoft.com/SMI/2016/'
            "WindowsSettings\">" + SETTING + "</asmv3:windowsSettings></asmv3:application>",
            1)
    elif "</application>" in text:
        text = text.replace(
            "</application>",
            '<windowsSettings xmlns="http://schemas.microsoft.com/SMI/2016/'
            'WindowsSettings">' + SETTING + "</windowsSettings></application>", 1)
    elif "</assembly>" in text:
        text = text.replace(
            "</assembly>",
            '<application xmlns="urn:schemas-microsoft-com:asm.v3">'
            '<windowsSettings xmlns="http://schemas.microsoft.com/SMI/2016/'
            'WindowsSettings">' + SETTING + "</windowsSettings></application></assembly>",
            1)
    else:
        raise RuntimeError("unrecognized manifest shape; refusing to edit")
    return b"\xef\xbb\xbf" + text.encode("utf-8")


def update(exe: Path, payload: bytes, lang: int) -> None:
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.BeginUpdateResourceW.argtypes = [ctypes.c_wchar_p, ctypes.c_bool]
    kernel32.BeginUpdateResourceW.restype = ctypes.c_void_p
    kernel32.UpdateResourceW.argtypes = [
        ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint16,
        ctypes.c_void_p, ctypes.c_uint32,
    ]
    kernel32.UpdateResourceW.restype = ctypes.c_bool
    kernel32.EndUpdateResourceW.argtypes = [ctypes.c_void_p, ctypes.c_bool]
    kernel32.EndUpdateResourceW.restype = ctypes.c_bool

    handle = kernel32.BeginUpdateResourceW(str(exe), False)
    if not handle:
        raise OSError(ctypes.get_last_error(), "BeginUpdateResourceW")
    blob = ctypes.create_string_buffer(payload)
    # Integer-form type/name (MAKEINTRESOURCE): RT_MANIFEST=24, name id 1.
    # cbData is the exact payload length (no NUL rides along).
    ok = kernel32.UpdateResourceW(
        handle, ctypes.c_void_p(RT_MANIFEST), ctypes.c_void_p(1), lang,
        blob, len(payload),
    )
    if not ok:
        # UpdateResource failure: the documented discard path. A second
        # End after a FAILED End-commit has undefined handle state, so the
        # discard call happens only here.
        kernel32.EndUpdateResourceW(handle, True)
        raise OSError(ctypes.get_last_error(), "UpdateResourceW")
    if not kernel32.EndUpdateResourceW(handle, False):
        raise OSError(ctypes.get_last_error(), "EndUpdateResourceW")


def main() -> int:
    exe = Path(sys.argv[1] if len(sys.argv) > 1 else "")
    if not exe.is_file():
        print(f"usage: {sys.argv[0]} <exe>", file=sys.stderr)
        return 1
    try:
        body, lang = current_manifest(exe)
        payload = merged(body)
        if payload is None:
            print(f"{exe.name}: already longPathAware")
            return 0
        # jpackage creates the launcher read-only; the resource update
        # needs write access. The attribute is restored after the merge.
        was_readonly = not (exe.stat().st_mode & 0o200)
        if was_readonly:
            exe.chmod(exe.stat().st_mode | 0o200)
        try:
            for attempt in range(5):
                try:
                    update(exe, payload, lang)
                    break
                except OSError as failure:
                    if attempt == 4:
                        raise
                    print(f"retry {attempt + 1} after: {failure}")
                    import time
                    time.sleep(1.0)
        finally:
            if was_readonly:
                exe.chmod(exe.stat().st_mode & ~0o200)
        verify, _ = current_manifest(exe)
        if "longPathAware" not in verify.decode("utf-8-sig"):
            raise RuntimeError("resource update did not stick")
        print(f"{exe.name}: longPathAware merged (manifest lang={lang})")
        return 0
    except Exception as failure:  # noqa: BLE001 - the CLI's honest verdict
        print(f"{exe.name}: longPathAware NOT applied: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
