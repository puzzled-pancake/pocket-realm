#!/usr/bin/env python3
"""Stage the pinned Android Bionic ARM64EC Wine + FEXCore provider.

This deliberately does not package or execute the ordinary Linux FEX binary.
The provider follows Winlator Bionic's Android architecture: native Bionic
ARM64EC Wine loads the FEXCore Windows DLLs for x86/x86-64 code.  A single
uncompressed tar preserves the imagefs symlinks and modes and can be unpacked
by Android's system tar into app-private storage.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "native" / ".providers-extracted" / "fexcore-arm64ec"
APK = CACHE / "bionic-vanilla-v3.1.h.apk"
STAGE = (
    ROOT / "native" / ".build-arm64" / "wine-staging" / "assets" /
    "arm-translated" / "fexcore"
)
JNI_STAGE = ROOT / "native" / ".build-arm64" / "wine-staging" / "jniLibs"
GLADIO = ROOT / "native" / ".build-arm64-bionic" / "gladio-client" / "libGL.so.1"

APK_URL = (
    "https://github.com/StevenMXZ/Winlator-Ludashi/releases/download/"
    "v3.1.h/bionic-vanilla.apk"
)
APK_SHA256 = "eb7cc5ded4cbfddb3dac01847bf39cdbc9cd52cbad8cb54787aa84d79a4ce6d7"
FEXCORE_URL = (
    "https://raw.githubusercontent.com/StevenMXZ/Winlator-Contents/"
    "main/FEXCore/2608.wcp"
)
FEXCORE_SHA256 = "aa822c0343f73d213d06a3f72ce002afb41b51751c6bef3119313769ac697a8c"
FEXCORE_DLLS = {
    "libarm64ecfex.dll": "e32bf44902ee2858a57faa82e7d52fad8db4ef52189a43f5cea9e286bfe60066",
    "libwow64fex.dll": "60fcb3264dccf015481e2d50fe8dfd3b8a144ebd3edecaf0f278b4e3d1c7b20b",
}
GLADIO_SHA256 = "378e5bb98a818205da90c5642d8cb38da365c83604f2046293907caa8f0c9075"
SOURCE_DATE_EPOCH = "1788710400"

APK_ASSETS = {
    "assets/imagefs.tar.zst": (
        "imagefs.tar.zst",
        "7838756e6a05c91afff68f4bf12aa2780f815877753f8dd354e203e99b9caf8a",
    ),
    "assets/proton-9.0-arm64ec.tar.zst": (
        "proton-9.0-arm64ec.tar.zst",
        "e278d885f9aa3e19cc8df920d11bda19debb819603d13750740c82997469615b",
    ),
    "assets/proton-9.0-arm64ec_container_pattern.tzst": (
        "proton-9.0-arm64ec_container_pattern.tzst",
        "9826ac61405f641ca8326335208c3e1ed5cec107f4b0354f813825ec398b841b",
    ),
    "assets/dxwrapper/dxvk-2.3.1-arm64ec-gplasync.tzst": (
        "dxvk-2.3.1-arm64ec-gplasync.tzst",
        "2baea71a806ef5d9f45d1a458f09439f93b0d1f43b87d4350ede8219f3bd7a41",
    ),
    "assets/graphics_driver/adrenotools-turnip26.2.0.tzst": (
        "adrenotools-turnip26.2.0.tzst",
        "607338ec3e4ba06e659cbbb6ca4653265a5bf096bfb217050d18700d60b1083a",
    ),
    "assets/graphics_driver/wrapper.tzst": (
        "wrapper.tzst",
        "2005169d30ab7558b0c404ff8a3d781cddfde2f012531e097ccc95c423a706a5",
    ),
}

BUILDER_IMAGE = (
    "ghcr.io/termux/package-builder-cgct@"
    "sha256:69ffa5cfe02ca569e7d03d1c99e3c9a0f79390ad6bf11a3629d048c29c6ccb61"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_hash(path: Path, expected: str) -> None:
    actual = sha256(path)
    if actual != expected:
        raise RuntimeError(f"digest mismatch for {path}: {actual} != {expected}")


def download(url: str, destination: Path, expected: str) -> None:
    if destination.is_file() and sha256(destination) == expected:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(
        url, headers={"User-Agent": "PocketRealm-FEXCore-Stager/1"}
    )
    with urllib.request.urlopen(request) as response, partial.open("wb") as output:
        shutil.copyfileobj(response, output)
    require_hash(partial, expected)
    partial.replace(destination)


def docker_path(path: Path) -> str:
    value = str(path.resolve()).replace("\\", "/")
    if len(value) >= 2 and value[1] == ":":
        value = "//" + value[0].lower() + value[2:]
    return value


def extract_pinned_apk_assets() -> dict[str, Path]:
    output = CACHE / "apk-assets"
    output.mkdir(parents=True, exist_ok=True)
    resolved: dict[str, Path] = {}
    with zipfile.ZipFile(APK) as archive:
        names = set(archive.namelist())
        for member, (filename, expected) in APK_ASSETS.items():
            if member not in names:
                raise RuntimeError(f"pinned APK asset is missing: {member}")
            target = output / filename
            if not target.is_file() or sha256(target) != expected:
                temporary = target.with_suffix(target.suffix + ".part")
                with archive.open(member) as source, temporary.open("wb") as destination:
                    shutil.copyfileobj(source, destination)
                require_hash(temporary, expected)
                temporary.replace(target)
            resolved[filename] = target
    return resolved


def build_runtime_tar(assets: dict[str, Path], fexcore: Path) -> Path:
    require_hash(GLADIO, GLADIO_SHA256)
    STAGE.mkdir(parents=True, exist_ok=True)
    output = STAGE / "fexcore-runtime.tar.zst"
    relative = lambda path: "/work/" + path.relative_to(ROOT).as_posix()
    command = f"""
set -eu
rm -rf /tmp/pocket-fexcore
mkdir -p /tmp/pocket-fexcore/runtime/rootfs
tar -xf {relative(assets['imagefs.tar.zst'])} -C /tmp/pocket-fexcore/runtime/rootfs
mkdir -p /tmp/pocket-fexcore/runtime/rootfs/opt/proton-9.0-arm64ec
tar -xf {relative(assets['proton-9.0-arm64ec.tar.zst'])} -C /tmp/pocket-fexcore/runtime/rootfs/opt/proton-9.0-arm64ec
ln -s proton-9.0-arm64ec /tmp/pocket-fexcore/runtime/rootfs/opt/wine
mkdir -p /tmp/pocket-fexcore/runtime/rootfs/home/xuser
tar -xf {relative(assets['proton-9.0-arm64ec_container_pattern.tzst'])} -C /tmp/pocket-fexcore/runtime/rootfs/home/xuser
mkdir -p /tmp/pocket-fexcore/fexcore
tar -xf {relative(fexcore)} -C /tmp/pocket-fexcore/fexcore
install -m 0644 /tmp/pocket-fexcore/fexcore/system32/libarm64ecfex.dll /tmp/pocket-fexcore/runtime/rootfs/home/xuser/.wine/drive_c/windows/system32/libarm64ecfex.dll
install -m 0644 /tmp/pocket-fexcore/fexcore/system32/libwow64fex.dll /tmp/pocket-fexcore/runtime/rootfs/home/xuser/.wine/drive_c/windows/system32/libwow64fex.dll
install -m 0755 {relative(GLADIO)} /tmp/pocket-fexcore/runtime/rootfs/usr/lib/libGL.so.1.5.0
mkdir -p /tmp/pocket-fexcore/dxvk
tar -xf {relative(assets['dxvk-2.3.1-arm64ec-gplasync.tzst'])} -C /tmp/pocket-fexcore/dxvk
mkdir -p /tmp/pocket-fexcore/runtime/rootfs/opt/pocket-components/dxvk
cp -a /tmp/pocket-fexcore/dxvk/system32 /tmp/pocket-fexcore/dxvk/syswow64 /tmp/pocket-fexcore/runtime/rootfs/opt/pocket-components/dxvk/
tar -xf {relative(assets['wrapper.tzst'])} -C /tmp/pocket-fexcore/runtime/rootfs
tar -xf {relative(assets['adrenotools-turnip26.2.0.tzst'])} -C /tmp/pocket-fexcore
install -m 0755 /tmp/pocket-fexcore/vulkan.ad07xx.so /tmp/pocket-fexcore/runtime/rootfs/usr/lib/vulkan.ad07xx.so
printf '%s\n' 'winlator-bionic-v3.1.h-fexcore-2608-proton-9-arm64ec' > /tmp/pocket-fexcore/runtime/rootfs/.pocket-fexcore-runtime
tar --sort=name --mtime=@{SOURCE_DATE_EPOCH} --owner=0 --group=0 --numeric-owner -cf - -C /tmp/pocket-fexcore/runtime rootfs | zstd -19 -T0 -q -f -o {relative(output)}
"""
    subprocess.run([
        "docker", "run", "--rm", "--user", "0",
        "-v", f"{docker_path(ROOT)}:/work", BUILDER_IMAGE,
        "sh", "-lc", command,
    ], check=True)
    return output


def inspect_fexcore_component(fexcore: Path) -> dict[str, dict[str, object]]:
    records: dict[str, dict[str, object]] = {}
    with tempfile.TemporaryDirectory(prefix="pocket-fexcore-inspect-") as name:
        temporary = Path(name)
        subprocess.run(["tar", "-xf", str(fexcore), "-C", str(temporary)], check=True)
        for filename, expected in FEXCORE_DLLS.items():
            path = temporary / "system32" / filename
            require_hash(path, expected)
            data = path.read_bytes()
            pe_offset = int.from_bytes(data[0x3c:0x40], "little")
            if data[pe_offset:pe_offset + 4] != b"PE\0\0":
                raise RuntimeError(f"not a PE/COFF FEXCore component: {filename}")
            machine = int.from_bytes(data[pe_offset + 4:pe_offset + 6], "little")
            records[filename] = {
                "size": path.stat().st_size,
                "sha256": expected,
                "pe_machine": f"0x{machine:04x}",
            }
    return records


def stage_zstd_closure(imagefs: Path) -> list[dict[str, object]]:
    """Package a Bionic zstd executable and its private DT_NEEDED closure.

    Android permits executing APK native-library entries, while writable app
    storage is noexec.  Renaming every private dependency to an APK-valid .so
    and rewriting the executable's DT_NEEDED entries keeps the closure isolated
    from both the app and system namespaces.
    """
    members = {
        "usr/bin/zstd": "libpocket_zstd_exec.so",
        "usr/lib/libandroid-shmem.so": "libpocket_android_shmem.so",
        "usr/lib/libandroid-sysv-semaphore.so": "libpocket_android_sysv_semaphore.so",
        "usr/lib/libzstd.so.1.5.8": "libpocket_zstd.so",
        "usr/lib/libz.so.1": "libpocket_z.so",
        "usr/lib/liblzma.so.5.8.1": "libpocket_lzma.so",
    }
    JNI_STAGE.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="pocket-zstd-") as name:
        temporary = Path(name)
        subprocess.run(
            ["tar", "-xf", str(imagefs), "-C", str(temporary), *members],
            check=True,
        )
        for member, output_name in members.items():
            shutil.copy2(temporary / member, JNI_STAGE / output_name)

    replacements = {
        "libandroid-shmem.so": "libpocket_android_shmem.so",
        "libandroid-sysv-semaphore.so": "libpocket_android_sysv_semaphore.so",
        "libzstd.so.1": "libpocket_zstd.so",
        "libz.so.1": "libpocket_z.so",
        "liblzma.so.5": "libpocket_lzma.so",
    }
    replace_args = " ".join(
        f"--replace-needed {old} {new}" for old, new in replacements.items()
    )
    executable = JNI_STAGE / "libpocket_zstd_exec.so"
    subprocess.run([
        "docker", "run", "--rm", "--user", "0",
        "-v", f"{docker_path(ROOT)}:/work", BUILDER_IMAGE,
        "sh", "-lc",
        f"patchelf {replace_args} /work/{executable.relative_to(ROOT).as_posix()}",
    ], check=True)
    executable.chmod(0o755)

    artifacts: list[dict[str, object]] = []
    for output_name in members.values():
        path = JNI_STAGE / output_name
        header = path.read_bytes()[:20]
        if header[:4] != b"\x7fELF" or int.from_bytes(header[18:20], "little") != 183:
            raise RuntimeError(f"wrong ELF architecture in zstd closure: {path}")
        artifacts.append({
            "path": path.relative_to(ROOT).as_posix(),
            "size": path.stat().st_size,
            "sha256": sha256(path),
            "elf_machine": 183,
        })
    return artifacts


def main() -> int:
    download(APK_URL, APK, APK_SHA256)
    require_hash(APK, APK_SHA256)
    assets = extract_pinned_apk_assets()
    fexcore = CACHE / "fexcore-2608.wcp"
    download(FEXCORE_URL, fexcore, FEXCORE_SHA256)
    fexcore_dlls = inspect_fexcore_component(fexcore)
    runtime = build_runtime_tar(assets, fexcore)
    zstd_closure = stage_zstd_closure(assets["imagefs.tar.zst"])
    obsolete = STAGE / "fexcore-runtime.tar"
    if obsolete.is_file():
        obsolete.unlink()

    provenance = {
        "schema": 1,
        "provider": "fexcore-arm64ec",
        "status": "built-unqualified",
        "architecture": "native Bionic ARM64EC Wine + FEXCore Windows DLLs",
        "source": {
            "winlator_repository": "https://github.com/StevenMXZ/Winlator-Ludashi",
            "winlator_release": "v3.1.h",
            "apk_url": APK_URL,
            "apk_size": APK.stat().st_size,
            "apk_sha256": APK_SHA256,
            "fexcore_repository": "https://github.com/FEX-Emu/FEX",
            "fexcore_component_url": FEXCORE_URL,
            "fexcore_component_sha256": FEXCORE_SHA256,
            "fexcore_dlls": fexcore_dlls,
        },
        "components": {
            name: {"size": path.stat().st_size, "sha256": sha256(path)}
            for name, path in sorted(assets.items())
        },
        "gladio": {
            "abi": "arm64-bionic",
            "size": GLADIO.stat().st_size,
            "sha256": GLADIO_SHA256,
            "build_provenance": "native/.build-arm64-bionic/gladio-client/BUILD_PROVENANCE.json",
        },
        "runtime_tar": {
            "path": runtime.relative_to(ROOT).as_posix(),
            "size": runtime.stat().st_size,
            "sha256": sha256(runtime),
        },
        "apk_zstd_closure": zstd_closure,
        "renderer_matrix": {
            "opengl": "WoW -opengl -> Wine winex11/opengl32 -> Bionic Gladio -> Android GLES",
            "dxvk": "D3D9 -> ARM64EC DXVK -> Wine Vulkan -> Turnip/Vulkan",
        },
        "notes": [
            "The whole Winlator APK is not redistributed; only hash-pinned runtime components are staged.",
            "Ordinary Linux FEX is not packaged or executed on Android.",
            "Box64 remains an independent selectable runtime and fallback.",
        ],
    }
    (STAGE / "BUILD_PROVENANCE.json").write_text(
        json.dumps(provenance, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(provenance, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
