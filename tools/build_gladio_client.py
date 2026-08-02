#!/usr/bin/env python3
"""Build the pinned x86_64 Gladio client as Wine's libGL.so.1.

Gladio is split into a glibc client library and an Android/GLES server.  The
Winlator release asset only contains an AArch64 client because Winlator runs
x86_64 Wine through Box64.  Pocket Realm's x86_64 AVD executes Wine natively,
so the client must be rebuilt for x86_64 while the Android server remains the
NDK-built ``libgladiorenderer.so``.

The source adaptations replace Winlator's package-specific X socket, bind the
client to the server's bounded transient-attribute protocol, preserve BGRA
buffer offsets, and advertise only the qualified OpenGL compatibility profile.
Every replacement is signature-locked so an upstream change fails closed.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import stat
import subprocess
import tarfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native" / ".providers-extracted" / "gladio-source"
SOURCE_URL = "https://github.com/brunodev85/gladio.git"
SOURCE_COMMIT = "eaa2a8d6eda3a1a6af755370ea9fac6cf7792ac3"
SOURCE_DATE_EPOCH = "1776258695"
BUILD_ROOT = ROOT / "native" / ".build-x86_64" / "gladio-client"
SOURCE_TREE = BUILD_ROOT / "source"
OUTPUT = BUILD_ROOT / "libGL.so.1"
PROVENANCE = BUILD_ROOT / "BUILD_PROVENANCE.json"
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


def docker_path(path: Path) -> str:
    value = str(path.resolve()).replace("\\", "/")
    if len(value) >= 2 and value[1] == ":":
        value = "//" + value[0].lower() + value[2:]
    return value


def checked_rmtree(path: Path) -> None:
    resolved = path.resolve()
    allowed = BUILD_ROOT.resolve()
    if resolved == allowed or allowed not in resolved.parents:
        raise RuntimeError(f"refusing to remove path outside Gladio build root: {resolved}")
    if resolved.exists():
        def retry(func, failed, _exc):
            os.chmod(failed, stat.S_IWRITE)
            func(failed)
        shutil.rmtree(resolved, onexc=retry)


def acquire_source() -> None:
    if not (SOURCE / ".git").is_dir():
        SOURCE.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", SOURCE_URL, str(SOURCE)], check=True)
    subprocess.run(["git", "-C", str(SOURCE), "checkout", "--detach", SOURCE_COMMIT], check=True)
    head = subprocess.check_output(
        ["git", "-C", str(SOURCE), "rev-parse", "HEAD"], text=True
    ).strip()
    if head != SOURCE_COMMIT:
        raise RuntimeError(f"Gladio source pin mismatch: {head} != {SOURCE_COMMIT}")


def prepare_source() -> None:
    checked_rmtree(SOURCE_TREE)
    SOURCE_TREE.mkdir(parents=True)
    archive = BUILD_ROOT / "gladio-source.tar"
    try:
        subprocess.run(
            ["git", "-c", "core.autocrlf=false", "-C", str(SOURCE), "archive",
             "--format=tar", "--output", str(archive), SOURCE_COMMIT],
            check=True,
        )
        with tarfile.open(archive, "r") as bundle:
            bundle.extractall(SOURCE_TREE, filter="data")
    finally:
        archive.unlink(missing_ok=True)

    source = SOURCE_TREE / "src" / "main.c"
    text = source.read_text(encoding="utf-8")
    old = """    strncpy(server_addr.sun_path, X11_SERVER_PATH, sizeof(server_addr.sun_path) - 1);\n"""
    new = """    const char* socket_path = getenv(\"POCKET_GLADIO_X11_SOCKET\");
    if (!socket_path || socket_path[0] != '/' ||
        strlen(socket_path) >= sizeof(server_addr.sun_path)) {
        close(fd);
        return -1;
    }
    memcpy(server_addr.sun_path, socket_path, strlen(socket_path) + 1);
"""
    if text.count(old) != 1:
        raise RuntimeError("pinned Gladio X11 socket-path anchor missing or ambiguous")
    source.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

    # Match the server's conservative D3D9 compatibility profile. Advertising
    # desktop 3.3 makes WineD3D select texture-buffer, sampler-object and
    # instanced/base-vertex paths that this GLES bridge does not implement
    # completely.
    header = SOURCE_TREE / "include" / "gladio.h"
    text = header.read_text(encoding="utf-8")
    for old, new in (
        ('#define GL_STRING_VERSION "3.3"', '#define GL_STRING_VERSION "3.0"'),
        ('#define GL_STRING_SHADING_LANGUAGE_VERSION "3.30"',
         '#define GL_STRING_SHADING_LANGUAGE_VERSION "1.30"'),
    ):
        if text.count(old) != 1:
            raise RuntimeError(f"pinned Gladio compatibility-profile anchor missing: {old}")
        text = text.replace(old, new)
    header.write_text(text, encoding="utf-8", newline="\n")

    # The pinned client/server protocol keeps a bound BGRA attribute's original
    # VBO offset on the server, but the client omitted that prefix from the
    # transient payload. The server then subtracted a larger offset from a
    # smaller byte count and walked outside the ring-buffer mapping. Preserve
    # the offset client-side and include the prefix in the private client/server
    # payload that is versioned below.
    vao = SOURCE_TREE / "include" / "gl_vao.h"
    text = vao.read_text(encoding="utf-8")
    old = """    void* pointer;
    short stride;
"""
    new = """    void* pointer;
#ifndef GL_SERVER
    uint64_t pointerOffset;
#endif
    short stride;
"""
    if text.count(old) != 1:
        raise RuntimeError("pinned Gladio client attribute-layout anchor missing or ambiguous")
    vao.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

    calls = SOURCE_TREE / "src" / "gl_calls.c"
    text = calls.read_text(encoding="utf-8")
    old = """            clientState->vao->attribs[index].pointer = arrayBuffer ? arrayBuffer->mappedData : pointer;
            GLVertexArrayObject_setAttribState(clientState, index, VERTEX_ATTRIB_ENABLED, false);
"""
    new = """            clientState->vao->attribs[index].pointer = arrayBuffer ? arrayBuffer->mappedData : pointer;
            clientState->vao->attribs[index].pointerOffset =
                    arrayBuffer && size == GL_BGRA ? (uint64_t)pointer : 0;
            GLVertexArrayObject_setAttribState(clientState, index, VERTEX_ATTRIB_ENABLED, false);
"""
    if text.count(old) != 1:
        raise RuntimeError("pinned Gladio BGRA pointer anchor missing or ambiguous")
    calls.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

    # The upstream draw protocol infers the transient attribute order from two
    # independently-maintained VAO state machines. WineD3D can legitimately
    # leave those machines out of sync, causing the server to interpret vertex
    # bytes as a byte-count and walk past the request mapping. Replace only the
    # pinned function (guarded by its exact SHA-256) with an explicit list of
    # attribute index/kind/length records. Both ends remain private to this
    # provider and are built together.
    text = source.read_text(encoding="utf-8")
    start = text.index("void writeUnboundVertexArrays(")
    end = text.index("bool setupRingBuffers(", start)
    old = text[start:end]
    old_sha256 = hashlib.sha256(old.encode("utf-8")).hexdigest()
    expected_sha256 = "5dcf84438842f8d44e1dfd917882bc43ac4647908fb58840ea55218cec8577d2"
    if old_sha256 != expected_sha256:
        raise RuntimeError(
            "pinned Gladio draw-payload function changed: "
            f"{old_sha256} != {expected_sha256}"
        )
    new = r"""#define POCKET_DRAW_ATTR_MAGIC 0x504b4131
#define POCKET_DRAW_ATTR_GENERIC 1
#define POCKET_DRAW_ATTR_LEGACY 2

static int getDrawIndex(const void* indices, int index, GLenum indexType,
                        GLint basevertex) {
    switch (indexType) {
        case GL_UNSIGNED_BYTE:
            return ((const GLubyte*)indices)[index] + basevertex;
        case GL_UNSIGNED_SHORT:
            return ((const GLushort*)indices)[index] + basevertex;
        case GL_UNSIGNED_INT:
            return ((const GLuint*)indices)[index] + basevertex;
        default:
            return 0;
    }
}

void writeUnboundVertexArrays(GLint first, GLsizei count, const void* indices,
                              GLenum indexType, GLint basevertex) {
    GLClientState* clientState = currentGLContext->clientState;
    int indexCount = 0;

    if (indexType != GL_NONE) {
        GLBuffer* elementArrayBuffer = GLBuffer_getBound(GL_ELEMENT_ARRAY_BUFFER);
        if (elementArrayBuffer) {
            ArrayBuffer_putInt(&outputBuffer, (uint64_t)indices);
            indices = elementArrayBuffer->mappedData + (uint64_t)indices;
        }
        else ArrayBuffer_putBytes(&outputBuffer, indices,
                                  count * sizeofGLType(indexType));
    }

    ArrayBuffer_putInt(&outputBuffer, POCKET_DRAW_ATTR_MAGIC);
    int recordCountOffset = outputBuffer.size;
    ArrayBuffer_putInt(&outputBuffer, 0);
    int recordCount = 0;

    for (int i = 0; i < clientState->vao->maxEnabledAttribs; i++) {
        GLVertexAttrib* attrib = &clientState->vao->attribs[i];
        if (!attrib->state || !attrib->pointer) continue;

        bool legacyEnabledWithProgram =
                attrib->state == VERTEX_ATTRIB_LEGACY_ENABLED &&
                clientState->program > 0;
        short stride = attrib->stride;
        if (stride <= 0) continue;

        if (attrib->state == VERTEX_ATTRIB_ENABLED ||
            legacyEnabledWithProgram) {
            if (indexType != GL_NONE && indexCount == 0) {
                GLuint range[2];
                getRangeIndices(indices, count, indexType, basevertex, range);
                indexCount = range[1] + 1;
            }
            uint64_t baseBytes = (uint64_t)(indexType == GL_NONE
                    ? first + count : indexCount) * (uint64_t)stride;
            uint64_t byteCount = baseBytes + attrib->pointerOffset;
            if (byteCount > INT32_MAX) continue;

            ArrayBuffer_putInt(&outputBuffer, i);
            ArrayBuffer_putInt(&outputBuffer, POCKET_DRAW_ATTR_GENERIC);
            ArrayBuffer_putInt(&outputBuffer, (int)byteCount);
            ArrayBuffer_putBytes(&outputBuffer, attrib->pointer, (int)byteCount);
            recordCount++;
        }
        else if (attrib->state == VERTEX_ATTRIB_LEGACY_ENABLED) {
            uint64_t byteCount = (uint64_t)count * (uint64_t)stride;
            if (byteCount > INT32_MAX) continue;

            ArrayBuffer_putInt(&outputBuffer, i);
            ArrayBuffer_putInt(&outputBuffer, POCKET_DRAW_ATTR_LEGACY);
            ArrayBuffer_putInt(&outputBuffer, (int)byteCount);
            if (indexType == GL_NONE) {
                ArrayBuffer_putBytes(&outputBuffer,
                        attrib->pointer + ((uint64_t)first * stride),
                        (int)byteCount);
            }
            else {
                for (int j = 0; j < count; j++) {
                    int index = getDrawIndex(indices, j, indexType, basevertex);
                    ArrayBuffer_putBytes(&outputBuffer,
                            attrib->pointer + ((uint64_t)index * stride), stride);
                }
            }
            recordCount++;
        }
    }

    memcpy(outputBuffer.buffer + recordCountOffset, &recordCount,
           sizeof(recordCount));
}

"""
    source.write_text(text[:start] + new + text[end:], encoding="utf-8", newline="\n")


def build() -> None:
    sources = " ".join(
        f"/work/native/.build-x86_64/gladio-client/source/src/{name}"
        for name in (
            "main.c", "gl_calls.c", "glx_calls.c", "arrays.c",
            "ring_buffer.c", "gl_buffer.c", "gl_vao.c",
        )
    )
    command = " && ".join([
        f"export SOURCE_DATE_EPOCH={SOURCE_DATE_EPOCH}",
        "cd /work",
        (
            "gcc -std=gnu2x -O2 -fPIC -fvisibility=default "
            "-DGL_GLEXT_PROTOTYPES -pthread "
            "-ffile-prefix-map=/work=. -fdebug-prefix-map=/work=. "
            "-I/work/native/.build-x86_64/gladio-client/source/include "
            "-shared -Wl,--no-undefined -Wl,--as-needed "
            "-Wl,-soname,libGL.so.1 -Wl,-z,max-page-size=0x4000 "
            f"{sources} -lX11 -lm -o /work/native/.build-x86_64/gladio-client/libGL.so.1"
        ),
        "gcc --version | head -1 > /work/native/.build-x86_64/gladio-client/BUILD_TOOLCHAIN.txt",
    ])
    subprocess.run([
        "docker", "run", "--rm", "--user", "0",
        "-v", f"{docker_path(ROOT)}:/work", "--workdir", "/work",
        BUILDER_IMAGE, "sh", "-lc", command,
    ], check=True)


def verify() -> dict[str, object]:
    if not OUTPUT.is_file():
        raise FileNotFoundError(OUTPUT)
    from stage_wine_runtime import validate_elf_page_compatibility
    validate_elf_page_compatibility(OUTPUT.read_bytes(), "Gladio x86_64 libGL.so.1")

    readelf = subprocess.check_output(
        ["docker", "run", "--rm", "--user", "0",
         "-v", f"{docker_path(ROOT)}:/work", BUILDER_IMAGE,
         "readelf", "-d", "/work/native/.build-x86_64/gladio-client/libGL.so.1"],
        text=True,
    )
    if "Library soname: [libGL.so.1]" not in readelf:
        raise RuntimeError("Gladio client does not advertise SONAME libGL.so.1")
    needed = sorted(
        line.split("[", 1)[1].split("]", 1)[0]
        for line in readelf.splitlines() if "(NEEDED)" in line
    )
    # CGCT's glibc shared-library link intentionally records the loader as a
    # dependency; it is the same pinned loader already in the runtime closure.
    allowed = {"ld-linux-x86-64.so.2", "libX11.so.6", "libc.so.6", "libm.so.6"}
    unexpected = sorted(set(needed) - allowed)
    if unexpected:
        raise RuntimeError(f"unexpected Gladio client dependencies: {unexpected}")

    return {
        "schema": 1,
        "source_url": SOURCE_URL,
        "source_commit": SOURCE_COMMIT,
        "source_date_epoch": SOURCE_DATE_EPOCH,
        "license": "LGPL-2.1",
        "builder_image": BUILDER_IMAGE,
        "adaptations": [
            "Signature-locked replacement of Winlator's hard-coded X11 socket "
            "with POCKET_GLADIO_X11_SOCKET.",
            "Use explicit transient vertex-attribute records so client/server VAO "
            "state drift cannot reinterpret vertex bytes as lengths; preserve "
            "bound BGRA vertex-buffer offsets in those records.",
            "Advertise the qualified OpenGL 3.0 / GLSL 1.30 compatibility "
            "profile; the paired server retains internal-format queries but "
            "omits unsupported modern draw capabilities.",
        ],
        "soname": "libGL.so.1",
        "dt_needed": needed,
        "output": {"path": str(OUTPUT.relative_to(ROOT)).replace("\\", "/"),
                   "size": OUTPUT.stat().st_size, "sha256": sha256(OUTPUT)},
        "toolchain": (BUILD_ROOT / "BUILD_TOOLCHAIN.txt").read_text(
            encoding="utf-8"
        ).splitlines(),
    }


def main() -> int:
    acquire_source()
    prepare_source()
    build()
    provenance = verify()
    PROVENANCE.write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(provenance, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
