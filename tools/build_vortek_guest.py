#!/usr/bin/env python3
"""Build Pocket Realm's source-matched Vortek 2.1 guest with bounded map diagnostics."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
NATIVE = ROOT / "native"
CATALOG = json.loads(
    (ROOT / "schemas" / "vulkan-driver-catalog.json").read_text(encoding="utf-8")
)
VORTEK_DRIVER = next(
    driver for driver in CATALOG["drivers"]
    if driver["id"] == "system-vulkan-vortek-2.1"
)
VORTEK_LIBRARY = next(
    entry for entry in VORTEK_DRIVER["files"]
    if entry["role"] == "guest-vulkan-bridge-library"
)
CACHE = NATIVE / ".providers-extracted" / "vortek-guest-ab7329c"
PRISTINE = CACHE / "source"
BUILD_ROOT = NATIVE / ".build-arm64" / "vortek-guest"
ADAPTED = BUILD_ROOT / "source"
OUTPUT = BUILD_ROOT / "libvulkan_vortek.so"
PROVENANCE = BUILD_ROOT / "BUILD_PROVENANCE.json"

SOURCE_URL = f"{VORTEK_DRIVER['source']['upstream']}.git"
SOURCE_COMMIT = VORTEK_DRIVER["source"]["commit"]
SOURCE_DATE_EPOCH = "1776270454"
BUILDER_IMAGE = VORTEK_DRIVER["source"]["builder_image"]
BUILD_PACKAGES = (
    "gcc-13-aarch64-linux-gnu=13.3.0-6ubuntu2~24.04.1cross1",
    "binutils-aarch64-linux-gnu=2.42-4ubuntu2.10",
    "libc6-dev-arm64-cross=2.39-0ubuntu8cross1",
    "libx11-dev=2:1.8.7-1build1",
    "libvulkan-dev=1.3.275.0-1build1",
)
EXPECTED_SIZE = VORTEK_LIBRARY["size"]
EXPECTED_SHA256 = VORTEK_LIBRARY["sha256"]

OLD_SOCKET = '#define VORTEK_SERVER_PATH "/data/data/com.winlator/files/rootfs/tmp/.vortek/V0"'
NEW_SOCKET = '#define VORTEK_SERVER_PATH "/data/data/com.pocketrealm/files/rfs/tmp/.vortek/V0"'

OLD_HEADERS = """#include <sys/mman.h>
#include <pthread.h>
"""
NEW_HEADERS = """#include <sys/mman.h>
#include <pthread.h>
#include <errno.h>
#include <sys/stat.h>
"""

OLD_MUTEX = """static pthread_mutex_t vt_call_mutex = PTHREAD_MUTEX_INITIALIZER;

#define VT_CALL_LOCK() pthread_mutex_lock(&vt_call_mutex)
"""
NEW_MUTEX = """static pthread_mutex_t vt_call_mutex = PTHREAD_MUTEX_INITIALIZER;

#define VORTEK_MEMORY_DIAGNOSTIC_LIMIT 32
static unsigned int memoryDiagnosticCount = 0;

static bool reserveMemoryDiagnostic(unsigned int* sequence) {
    if (memoryDiagnosticCount >= VORTEK_MEMORY_DIAGNOSTIC_LIMIT) return false;
    *sequence = ++memoryDiagnosticCount;
    return true;
}

static void logMemoryMapDiagnostic(
        unsigned int sequence,
        const char* mode,
        int recvResult,
        int recvErrno,
        int numFds,
        int fd,
        int statResult,
        int statErrno,
        long long fdSize,
        VkDeviceSize offset,
        VkDeviceSize size,
        const void* placedAddress,
        const void* mappedAddress,
        int mmapErrno,
        VkResult result) {
    fprintf(
        stderr,
        "PocketVortekGuest: event=memory-map sequence=%u mode=%s recvmsg=%d "
        "recvmsgErrno=%d fds=%d fd=%d fstat=%d fstatErrno=%d fdSize=%lld "
        "offset=%llu size=%llu placed=%p mapped=%p mmapErrno=%d result=%d\\n",
        sequence,
        mode,
        recvResult,
        recvErrno,
        numFds,
        fd,
        statResult,
        statErrno,
        fdSize,
        (unsigned long long)offset,
        (unsigned long long)size,
        placedAddress,
        mappedAddress,
        mmapErrno,
        result);
}

#define VT_CALL_LOCK() pthread_mutex_lock(&vt_call_mutex)
"""

OLD_CLASSIC_MAP = """VkResult vt_call_vkMapMemory(VkDevice device, VkDeviceMemory memory, VkDeviceSize offset, VkDeviceSize size, VkMemoryMapFlags flags, void** ppData) {
    VT_CALL_LOCK();    
    VkObject* memoryObject = VkObject_fromHandle(memory);

    MappedMemory* mappedMemory = memoryObject->tag;
    if (mappedMemory->data) {
        VT_CALL_UNLOCK();
        return VK_SUCCESS;
    }
    
    VT_SERIALIZE_CMD(VkDeviceMemory, (VkDeviceMemory)&memoryObject->id);    
    VT_SEND_CHECKED(REQUEST_CODE_VK_MAP_MEMORY, VT_RETURN);
    
    int fd, result, numFds;
    recv_fds(serverFd, &fd, &numFds, &result, sizeof(VkResult));
    if (numFds == 1) {
        if (size == VK_WHOLE_SIZE) size = mappedMemory->allocationSize;
        mappedMemory->size = size;
        
        void* data = mmap(NULL, size, PROT_WRITE | PROT_READ, MAP_SHARED, fd, offset);
        if (data != MAP_FAILED) {
            CLOSEFD(fd);
            mappedMemory->data = data;
            *ppData = data;
        }
        else result = VK_ERROR_MEMORY_MAP_FAILED;
    }
    else result = VK_ERROR_MEMORY_MAP_FAILED;
    
    VT_CALL_UNLOCK();
    return (VkResult)result;
}
"""

NEW_CLASSIC_MAP = """VkResult vt_call_vkMapMemory(VkDevice device, VkDeviceMemory memory, VkDeviceSize offset, VkDeviceSize size, VkMemoryMapFlags flags, void** ppData) {
    VT_CALL_LOCK();
    VkObject* memoryObject = VkObject_fromHandle(memory);

    MappedMemory* mappedMemory = memoryObject->tag;
    if (mappedMemory->data) {
        VT_CALL_UNLOCK();
        return VK_SUCCESS;
    }

    VT_SERIALIZE_CMD(VkDeviceMemory, (VkDeviceMemory)&memoryObject->id);
    VT_SEND_CHECKED(REQUEST_CODE_VK_MAP_MEMORY, VT_RETURN);

    int fd = -1;
    int result = VK_ERROR_MEMORY_MAP_FAILED;
    int numFds = 0;
    errno = 0;
    int recvResult = recv_fds(serverFd, &fd, &numFds, &result, sizeof(VkResult));
    int recvErrno = recvResult >= 0 ? 0 : errno;
    if (size == VK_WHOLE_SIZE) size = mappedMemory->allocationSize;

    unsigned int sequence = 0;
    bool diagnostic = reserveMemoryDiagnostic(&sequence);
    struct stat fdStat = {0};
    int statResult = -1;
    int statErrno = 0;
    if (diagnostic && numFds == 1) {
        errno = 0;
        statResult = fstat(fd, &fdStat);
        statErrno = statResult == 0 ? 0 : errno;
    }

    void* data = MAP_FAILED;
    int mmapErrno = 0;
    if (numFds == 1) {
        errno = 0;
        data = mmap(NULL, size, PROT_WRITE | PROT_READ, MAP_SHARED, fd, offset);
        mmapErrno = data == MAP_FAILED ? errno : 0;
    }
    if (numFds != 1 || data == MAP_FAILED) result = VK_ERROR_MEMORY_MAP_FAILED;
    if (diagnostic) {
        logMemoryMapDiagnostic(
            sequence, "classic", recvResult, recvErrno, numFds, fd,
            statResult, statErrno, statResult == 0 ? (long long)fdStat.st_size : -1LL,
            offset, size, NULL, data, mmapErrno, (VkResult)result);
    }

    if (numFds == 1 && data != MAP_FAILED) {
        mappedMemory->size = size;
        CLOSEFD(fd);
        mappedMemory->data = data;
        *ppData = data;
    }

    VT_CALL_UNLOCK();
    return (VkResult)result;
}
"""

OLD_PLACED_MAP = """VkResult vt_call_vkMapMemory2KHR(VkDevice device, const VkMemoryMapInfoKHR* pMemoryMapInfo, void** ppData) {
    VT_CALL_LOCK();
    VkObject* memoryObject = VkObject_fromHandle(pMemoryMapInfo->memory);

    MappedMemory* mappedMemory = memoryObject->tag;
    if (mappedMemory->data) {
        VT_CALL_UNLOCK();
        return VK_SUCCESS;
    }

    VT_SERIALIZE_CMD(VkDeviceMemory, (VkDeviceMemory)&memoryObject->id);
    VT_SEND_CHECKED(REQUEST_CODE_VK_MAP_MEMORY, VT_RETURN);

    int fd, result, numFds;
    recv_fds(serverFd, &fd, &numFds, &result, sizeof(VkResult));
    if (numFds == 1) {
        mappedMemory->size = pMemoryMapInfo->size;
        if (mappedMemory->size == VK_WHOLE_SIZE) mappedMemory->size = mappedMemory->allocationSize;

        VkMemoryMapPlacedInfoEXT* placedInfo = findNextVkStructure(pMemoryMapInfo->pNext, VK_STRUCTURE_TYPE_MEMORY_MAP_PLACED_INFO_EXT);
        void* placedAddr = placedInfo ? placedInfo->pPlacedAddress : NULL;

        void* data = mmap(placedAddr, mappedMemory->size, PROT_WRITE | PROT_READ, MAP_SHARED | (placedAddr ? MAP_FIXED : 0), fd, pMemoryMapInfo->offset);
        if (data != MAP_FAILED) {
            CLOSEFD(fd);
            mappedMemory->data = data;

            if (!placedAddr) *ppData = data;
        }
        else result = VK_ERROR_MEMORY_MAP_FAILED;
    }
    else result = VK_ERROR_MEMORY_MAP_FAILED;

    VT_CALL_UNLOCK();
    return (VkResult)result;
}
"""

NEW_PLACED_MAP = """VkResult vt_call_vkMapMemory2KHR(VkDevice device, const VkMemoryMapInfoKHR* pMemoryMapInfo, void** ppData) {
    VT_CALL_LOCK();
    VkObject* memoryObject = VkObject_fromHandle(pMemoryMapInfo->memory);

    MappedMemory* mappedMemory = memoryObject->tag;
    if (mappedMemory->data) {
        VT_CALL_UNLOCK();
        return VK_SUCCESS;
    }

    VT_SERIALIZE_CMD(VkDeviceMemory, (VkDeviceMemory)&memoryObject->id);
    VT_SEND_CHECKED(REQUEST_CODE_VK_MAP_MEMORY, VT_RETURN);

    int fd = -1;
    int result = VK_ERROR_MEMORY_MAP_FAILED;
    int numFds = 0;
    errno = 0;
    int recvResult = recv_fds(serverFd, &fd, &numFds, &result, sizeof(VkResult));
    int recvErrno = recvResult >= 0 ? 0 : errno;

    VkDeviceSize mapSize = pMemoryMapInfo->size;
    if (mapSize == VK_WHOLE_SIZE) mapSize = mappedMemory->allocationSize;
    VkMemoryMapPlacedInfoEXT* placedInfo = findNextVkStructure(
        pMemoryMapInfo->pNext,
        VK_STRUCTURE_TYPE_MEMORY_MAP_PLACED_INFO_EXT);
    void* placedAddr = placedInfo ? placedInfo->pPlacedAddress : NULL;

    unsigned int sequence = 0;
    bool diagnostic = reserveMemoryDiagnostic(&sequence);
    struct stat fdStat = {0};
    int statResult = -1;
    int statErrno = 0;
    if (diagnostic && numFds == 1) {
        errno = 0;
        statResult = fstat(fd, &fdStat);
        statErrno = statResult == 0 ? 0 : errno;
    }

    void* data = MAP_FAILED;
    int mmapErrno = 0;
    if (numFds == 1) {
        errno = 0;
        data = mmap(
            placedAddr,
            mapSize,
            PROT_WRITE | PROT_READ,
            MAP_SHARED | (placedAddr ? MAP_FIXED : 0),
            fd,
            pMemoryMapInfo->offset);
        mmapErrno = data == MAP_FAILED ? errno : 0;
    }
    if (numFds != 1 || data == MAP_FAILED) result = VK_ERROR_MEMORY_MAP_FAILED;
    if (diagnostic) {
        logMemoryMapDiagnostic(
            sequence, placedAddr ? "placed" : "map2", recvResult, recvErrno,
            numFds, fd, statResult, statErrno,
            statResult == 0 ? (long long)fdStat.st_size : -1LL,
            pMemoryMapInfo->offset, mapSize, placedAddr, data, mmapErrno,
            (VkResult)result);
    }

    if (numFds == 1 && data != MAP_FAILED) {
        mappedMemory->size = mapSize;
        CLOSEFD(fd);
        mappedMemory->data = data;
        if (!placedAddr) *ppData = data;
    }

    VT_CALL_UNLOCK();
    return (VkResult)result;
}
"""


sha256 = common.sha256_file
def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Vortek source anchor {label!r} occurred {count} times")
    return text.replace(old, new)


def adapt_source(source: Path) -> None:
    header = source / "include" / "vortek.h"
    header_text = replace_once(header.read_text(), OLD_SOCKET, NEW_SOCKET, "socket")
    header.write_text(header_text, newline="\n")

    calls = source / "src" / "vulkan_calls.c"
    calls_text = calls.read_text()
    for old, new, label in (
        (OLD_HEADERS, NEW_HEADERS, "diagnostic headers"),
        (OLD_MUTEX, NEW_MUTEX, "diagnostic counter"),
        (OLD_CLASSIC_MAP, NEW_CLASSIC_MAP, "classic map"),
        (OLD_PLACED_MAP, NEW_PLACED_MAP, "placed map"),
    ):
        calls_text = replace_once(calls_text, old, new, label)
    calls.write_text(calls_text, newline="\n")


def ensure_pristine_source() -> None:
    if not PRISTINE.is_dir():
        PRISTINE.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "--no-checkout", SOURCE_URL, str(PRISTINE)], check=True)
        subprocess.run(["git", "-C", str(PRISTINE), "checkout", "--detach", SOURCE_COMMIT], check=True)
    head = subprocess.run(
        ["git", "-C", str(PRISTINE), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if head != SOURCE_COMMIT:
        raise RuntimeError(f"Vortek guest cache is at {head}, expected {SOURCE_COMMIT}")
    dirty = subprocess.run(
        ["git", "-C", str(PRISTINE), "status", "--porcelain"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if dirty:
        raise RuntimeError("Vortek guest cache is modified; refusing an ambiguous build")


def build() -> dict[str, object]:
    ensure_pristine_source()
    if ADAPTED.exists():
        shutil.rmtree(ADAPTED)
    shutil.copytree(PRISTINE, ADAPTED, ignore=shutil.ignore_patterns(".git"))
    adapt_source(ADAPTED)
    BUILD_ROOT.mkdir(parents=True, exist_ok=True)

    relative_source = ADAPTED.relative_to(ROOT).as_posix()
    relative_output = OUTPUT.relative_to(ROOT).as_posix()
    relative_include = (
        NATIVE / "xserver-winlator" / "cpp" / "vortekrenderer-winlator-2.1" / "include"
    ).relative_to(ROOT).as_posix()
    sources = " ".join(
        f"/workspace/{relative_source}/{name}"
        for name in (
            "src/main.c",
            "src/vulkan_calls.c",
            "src/vk_object.c",
            "src/vk_object_pool.c",
            "src/arrays.c",
            "src/descriptor_update_template.c",
            "src/ring_buffer.c",
        )
    )
    package_args = " ".join(BUILD_PACKAGES)
    command = (
        "apt-get update >/dev/null && "
        f"apt-get install -y --no-install-recommends {package_args} >/dev/null && "
        f"SOURCE_DATE_EPOCH={SOURCE_DATE_EPOCH} aarch64-linux-gnu-gcc-13 "
        "-shared -fPIC -O2 -Wall -Wno-discarded-qualifiers "
        "-DVK_USE_PLATFORM_XLIB_KHR "
        f"-I/workspace/{relative_source}/include "
        f"-I/workspace/{relative_include} -I/usr/include/vulkan "
        f"-ffile-prefix-map=/workspace/{relative_source}=. "
        f"{sources} "
        "-Wl,-soname,libvulkan_vortek.so "
        "-Wl,-rpath=/data/data/com.pocketrealm/files/rfs/lib "
        "-Wl,--build-id -Wl,-z,max-page-size=16384 "
        f"-o /workspace/{relative_output}"
    )
    subprocess.run(
        [
            "docker", "run", "--rm",
            "-e", "DEBIAN_FRONTEND=noninteractive",
            "-v", f"{ROOT}:/workspace",
            "-w", "/workspace",
            BUILDER_IMAGE,
            "sh", "-lc", command,
        ],
        check=True,
    )

    size = OUTPUT.stat().st_size
    digest = sha256(OUTPUT)
    if EXPECTED_SIZE and (size, digest) != (EXPECTED_SIZE, EXPECTED_SHA256):
        raise RuntimeError(
            "Vortek guest output identity changed: "
            f"got ({size}, {digest}), expected ({EXPECTED_SIZE}, {EXPECTED_SHA256})"
        )
    data = OUTPUT.read_bytes()
    if data[:4] != b"\x7fELF" or int.from_bytes(data[18:20], "little") != 0xB7:
        raise RuntimeError("Vortek guest output is not AArch64 ELF")
    if data.count(b"/data/data/com.pocketrealm/files/rfs") != 2:
        raise RuntimeError("Vortek guest does not contain the exact Pocket Realm root twice")
    if b"/data/data/com.winlator/files/rootfs" in data:
        raise RuntimeError("Vortek guest retains Winlator's package root")

    provenance: dict[str, object] = {
        "schema": 1,
        "source": {"url": SOURCE_URL, "commit": SOURCE_COMMIT},
        "builder_image": BUILDER_IMAGE,
        "build_packages": list(BUILD_PACKAGES),
        "source_date_epoch": int(SOURCE_DATE_EPOCH),
        "adaptations": [
            "Pocket Realm app-private Vortek socket and glibc RUNPATH",
            "32-event process-scoped recvmsg/fstat/mmap diagnostic envelope",
        ],
        "output": {
            "path": str(OUTPUT.relative_to(ROOT)).replace("\\", "/"),
            "size": size,
            "sha256": digest,
            "elf_machine": 0xB7,
        },
    }
    PROVENANCE.write_text(json.dumps(provenance, indent=2) + "\n", newline="\n")
    return provenance


def verify() -> dict[str, object]:
    if not OUTPUT.is_file() or not PROVENANCE.is_file():
        raise RuntimeError("Vortek guest output/provenance is missing; run the builder")
    record = json.loads(PROVENANCE.read_text())
    output = record["output"]
    actual = (OUTPUT.stat().st_size, sha256(OUTPUT))
    recorded = (int(output["size"]), str(output["sha256"]))
    if actual != recorded:
        raise RuntimeError(f"Vortek guest differs from provenance: {actual} != {recorded}")
    if EXPECTED_SIZE and actual != (EXPECTED_SIZE, EXPECTED_SHA256):
        raise RuntimeError("Vortek guest differs from the reviewed build identity")
    return record


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    record = verify() if args.verify else build()
    print(json.dumps(record, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
