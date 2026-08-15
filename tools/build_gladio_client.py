#!/usr/bin/env python3
"""Build the pinned Gladio client as Wine's libGL.so.1.

Gladio is split into a glibc client library and an Android/GLES server.  The
Winlator release asset only contains an AArch64 client because Winlator runs
x86_64 Wine through Box64.  Pocket Realm's x86_64 AVD executes Wine natively,
so the client must be rebuilt for x86_64 while the Android server remains the
NDK-built ``libgladiorenderer.so``.

The source adaptations replace Winlator's package-specific X socket, bind the
client to the server's bounded transient-attribute protocol, preserve BGRA
buffer offsets, and advertise only the qualified OpenGL compatibility profile.
Every replacement is signature-locked so an upstream change fails closed.

The default remains the qualified x86_64 lane.  ``--abi arm64-v8a`` uses an
explicit GNU/Linux cross toolchain (not the Android NDK) because Box64 loads
the AArch64 client in Winlator's glibc namespace.  The resulting library is
staged as a closed experimental renderer payload. At runtime it is copied into
the selected prefix generation; the shared Winlator rootfs is not modified.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
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
STAGED_OUTPUT: Path | None = None
BUILDER_IMAGE = (
    "ghcr.io/termux/package-builder-cgct@"
    "sha256:69ffa5cfe02ca569e7d03d1c99e3c9a0f79390ad6bf11a3629d048c29c6ccb61"
)
TARGET_ABI = "x86_64"
TARGET_BUILD_SUFFIX = "x86_64"
TARGET_MACHINE = 62  # EM_X86_64
TARGET_COMPILER = "gcc"
TARGET_LOADER = "ld-linux-x86-64.so.2"
TARGET_EXPECTED_SHA256 = "7b60dafa5e071e11187c0936840201920e141160f0897609ce530cb6f69b60b6"
# Phase-2 client corrections from the external Gladio/WoW research pass:
# spec-exact integer color/normal/secondary-color normalization
# ((2c+1)/(2^b-1) signed, c/(2^b-1) unsigned), client-active-texture fixes for
# glMultiTexCoordPointerEXT and the (previously stubbed) indexed client-state
# enables, clamped glGetProgramInfoLog/glGetShaderInfoLog replies, and
# glAreTexturesResident output population.  Applied to the ARM64 lane only;
# the x86_64 validation lane keeps its byte-pinned output.
PHASE2_PATCH = ROOT / "tools" / "patches" / "gladio-phase2-gl_calls.patch"
PHASE2_GL_CALLS_SHA256 = "7e1f99d3f1ed98086cd679aba216e8694e197b9502a29d9af894111d276c5a19"
# WoW 1.12.1 on-device findings: glTexGenfv (water/terrain texcoord
# generation) and glLightModelfv (ambient scene light) were stubbed on both
# ends of the bridge, spamming stderr every frame until the tracked output
# pipe back-pressured and froze the game's render thread. Forward the vector
# variants with plane-aware payload counts and log remaining unimplemented
# calls at most once per name.
PHASE3_PATCH = ROOT / "tools" / "patches" / "gladio-wow-texgen-gl_calls.patch"
PHASE3_GL_CALLS_SHA256 = "614693d16ae2cc20c5d78c6ed4073172124b3d05580ac934d1ac9c93266296f9"
# Phase-4 production transport corrections from the external Phase-2
# engineering pass (validated against the exact deployed v5 sources):
# atomic three-part client publication (header/payload/trailing bytes) via
# RingBuffer_writeParts so a request is never partially visible, client ring
# hardening (RingBuffer_create now stores the sharedData mapping, power-of-two
# capacity and reply-length validation, peer-socket death detection, first
# send failure reporting, unaligned wire-header loads fixed), uint32 wrap-safe
# head/tail commits, glDisableVertexAttribArray-style mirroring for
# glEnableVertexAttribArray, and all-or-nothing texture/immediate-mode writes.
# No request code or payload layout changes.  Applied last against the fully
# mutated tree; every touched file is sha-locked to the reviewed content.
PHASE4_PATCH = ROOT / "tools" / "patches" / "gladio-phase4-transport.patch"
PHASE4_FILE_SHA256 = {
    "include/gladio.h":
        "e4f63db1b52850950b9a0a1bc0862c1059a84aee94397a0170aa860fb49ad729",
    "include/ring_buffer.h":
        "49e298c6d638e1be4db913d452e95bb6b6a61f1b42b447bc1f18b11dc661a40b",
    "src/ring_buffer.c":
        "7c9c61a6bddbaf29c6af92f6979e1609de83e1b2aa6f6f8bef06062fda6400b4",
    "src/gl_calls.c":
        "0524588dd61c28cdae80859f49769e11e5b848ecd7cf1bb9fba3ea5edffe4935",
    "src/main.c":
        "9088a6fb743687092cdf536994c63415a5c8dec5ef8fde4cd1bc35269150e706",
}
ARM_CROSS_PACKAGES = (
    "gcc-aarch64-linux-gnu=4:15.2.0-5ubuntu1",
    "libc6-dev-arm64-cross=2.43-2ubuntu2cross1",
    "libx11-dev:arm64=2:1.8.13-1",
)
WINLATOR_ROOTFS = (
    ROOT / "native" / ".providers-extracted" / "winlator-app-ca3d735" /
    "app" / "src" / "main" / "assets" / "rootfs.tzst"
)
def select_target(abi: str) -> None:
    global BUILD_ROOT, SOURCE_TREE, OUTPUT, PROVENANCE, STAGED_OUTPUT
    global TARGET_ABI, TARGET_BUILD_SUFFIX, TARGET_MACHINE
    global TARGET_COMPILER, TARGET_LOADER, TARGET_EXPECTED_SHA256

    if abi == "x86_64":
        suffix = "x86_64"
        machine = 62
        compiler = "gcc"
        loader = "ld-linux-x86-64.so.2"
        expected_sha256 = "7b60dafa5e071e11187c0936840201920e141160f0897609ce530cb6f69b60b6"
    elif abi == "arm64-v8a":
        suffix = "arm64"
        machine = 183
        compiler = "aarch64-linux-gnu-gcc"
        loader = "ld-linux-aarch64.so.1"
        expected_sha256 = "85af99dcd3320197537e35ea0eeece24cb3fdbb4279a763def534286cb21a866"
    else:
        raise ValueError(f"unsupported Gladio target ABI: {abi}")

    TARGET_ABI = abi
    TARGET_BUILD_SUFFIX = suffix
    TARGET_MACHINE = machine
    TARGET_COMPILER = compiler
    TARGET_LOADER = loader
    TARGET_EXPECTED_SHA256 = expected_sha256
    BUILD_ROOT = ROOT / "native" / f".build-{suffix}" / "gladio-client"
    SOURCE_TREE = BUILD_ROOT / "source"
    OUTPUT = BUILD_ROOT / "libGL.so.1"
    PROVENANCE = BUILD_ROOT / "BUILD_PROVENANCE.json"
    STAGED_OUTPUT = (
        ROOT / "native" / ".build-arm64" / "wine-staging" / "assets" /
        "arm-translated" / "renderer-packages" /
        "box64-gladio-eaa2a8d" / "libGL.so.1"
        if abi == "arm64-v8a" else None
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

    # A texture upload's client-memory span is larger than width*height when
    # desktop GL_UNPACK_ROW_LENGTH, alignment, or SKIP_* state is active. The
    # upstream transport tracked only row/image length and omitted the prefix
    # and row padding, so the Android server could read beyond the bytes sent
    # through the ring (visible as deterministic half-frame scanlines in WoW's
    # tiled loading artwork). Preserve all size-affecting unpack state and send
    # the exact byte span that the server-side GLES upload may access.
    client_state = SOURCE_TREE / "include" / "gl_client_state.h"
    text = client_state.read_text(encoding="utf-8")
    old = """    struct {
        short unpackRowLength;
        short unpackImageHeight;
    } pixelStore;
"""
    new = """    struct {
        GLint unpackAlignment;
        GLint unpackRowLength;
        GLint unpackImageHeight;
        GLint unpackSkipPixels;
        GLint unpackSkipRows;
        GLint unpackSkipImages;
    } pixelStore;
"""
    if text.count(old) != 1:
        raise RuntimeError("pinned Gladio client pixel-store layout anchor missing")
    text = text.replace(old, new)
    old = """static inline void GLClientState_init(GLClientState* clientState, GLClientState* sharedState) {
    if (sharedState) {
"""
    new = """static inline void GLClientState_init(GLClientState* clientState, GLClientState* sharedState) {
#ifndef GL_SERVER
    clientState->pixelStore.unpackAlignment = 4;
#endif
    if (sharedState) {
"""
    if text.count(old) != 1:
        raise RuntimeError("pinned Gladio client-state initialization anchor missing")
    if TARGET_ABI != "x86_64":
        client_state.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

    texture_utils = SOURCE_TREE / "include" / "texture_utils.h"
    text = texture_utils.read_text(encoding="utf-8")
    anchor = "\n#ifdef __ANDROID__\nstatic inline bool isCompressedFormat(GLenum format) {"
    if text.count(anchor) != 1:
        raise RuntimeError("pinned Gladio texture-size helper anchor missing")
    helper = r'''

static inline int computeTexImageDataSpan(
        uint32_t format, uint32_t type,
        int width, int height, int depth,
        int rowLength, int imageHeight, int alignment,
        int skipPixels, int skipRows, int skipImages) {
    if (width <= 0 || height <= 0 || depth <= 0 ||
        rowLength < 0 || imageHeight < 0 ||
        skipPixels < 0 || skipRows < 0 || skipImages < 0) return 0;
    if (alignment != 1 && alignment != 2 && alignment != 4 && alignment != 8) return 0;

    int pixelBytes = computeTexImageDataSize(format, type, 1, 1, 1);
    if (pixelBytes <= 0) return 0;
    uint64_t storageWidth = rowLength > 0 ? (uint64_t)rowLength : (uint64_t)width;
    uint64_t storageHeight = imageHeight > 0 ? (uint64_t)imageHeight : (uint64_t)height;
    uint64_t rowBytes = storageWidth * (uint64_t)pixelBytes;
    uint64_t rowStride = (rowBytes + (uint64_t)alignment - 1u) &
                         ~((uint64_t)alignment - 1u);
    uint64_t imageStride = rowStride * storageHeight;
    uint64_t start = (uint64_t)skipImages * imageStride +
                     (uint64_t)skipRows * rowStride +
                     (uint64_t)skipPixels * (uint64_t)pixelBytes;
    uint64_t span = start + (uint64_t)(depth - 1) * imageStride +
                    (uint64_t)(height - 1) * rowStride +
                    (uint64_t)width * (uint64_t)pixelBytes;
    return span > INT32_MAX ? 0 : (int)span;
}
'''
    if TARGET_ABI != "x86_64":
        texture_utils.write_text(text.replace(anchor, helper + anchor), encoding="utf-8", newline="\n")

    text = header.read_text(encoding="utf-8")
    old = """        int dataSize = compressedSize; \\
        if (compressedSize == 0) { \\
            if (clientState->pixelStore.unpackRowLength > 0) width = clientState->pixelStore.unpackRowLength; \\
            if (clientState->pixelStore.unpackImageHeight > 0) height = clientState->pixelStore.unpackImageHeight; \\
            dataSize = imageData && !pixelUnpackBuffer ? computeTexImageDataSize(format, type, width, height, depth) : 0; \\
            ArrayBuffer_putInt(&outputBuffer, dataSize); \\
        } \\
        else ArrayBuffer_putInt(&outputBuffer, compressedSize); \\
"""
    new = """        int dataSize = compressedSize; \\
        if (compressedSize == 0) { \\
            dataSize = imageData && !pixelUnpackBuffer ? computeTexImageDataSpan( \\
                    format, type, width, height, depth, \\
                    clientState->pixelStore.unpackRowLength, \\
                    clientState->pixelStore.unpackImageHeight, \\
                    clientState->pixelStore.unpackAlignment, \\
                    clientState->pixelStore.unpackSkipPixels, \\
                    clientState->pixelStore.unpackSkipRows, \\
                    clientState->pixelStore.unpackSkipImages) : 0; \\
            ArrayBuffer_putInt(&outputBuffer, dataSize); \\
        } \\
        else ArrayBuffer_putInt(&outputBuffer, compressedSize); \\
"""
    if text.count(old) != 1:
        raise RuntimeError("pinned Gladio texture transport-size anchor missing")
    if TARGET_ABI != "x86_64":
        header.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

    # Android normally means the GLES server in upstream Gladio.  ARM64EC
    # Wine, however, is itself a native Bionic process and needs the client
    # half.  Keep upstream behaviour unless the dedicated Bionic client build
    # explicitly opts out with POCKET_GLADIO_CLIENT.
    text = header.read_text(encoding="utf-8")
    old = """#ifdef __ANDROID__\n#define GL_SERVER 1\n#endif\n"""
    new = """#if defined(__ANDROID__) && !defined(POCKET_GLADIO_CLIENT)\n#define GL_SERVER 1\n#endif\n"""
    if text.count(old) != 1:
        raise RuntimeError("pinned Gladio Android server/client anchor missing")
    header.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

    # WineD3D creates a small GLX pbuffer while probing its final shared
    # context. Upstream Gladio advertises the entry point but returns XID 0,
    # which makes WoW abort device creation before its first swap. The Android
    # server already renders surfaceless EGL contexts and understands ordinary
    # X windows as drawables, so use an unmapped X window as the private
    # client/server pbuffer representation. It has real XID lifetime and size
    # semantics without adding a second native-surface protocol.
    glx_calls = SOURCE_TREE / "src" / "glx_calls.c"
    text = glx_calls.read_text(encoding="utf-8")
    replacements = (
        (
            '''GLXPbuffer glXCreatePbuffer(Display* dpy, GLXFBConfig config, const int* attrib_list) {
    println(MSG_DEBUG_UNIMPLEMENTED_GLXCALL, "glXCreatePbuffer");
    return 0;
}
''',
            '''GLXPbuffer glXCreatePbuffer(Display* dpy, GLXFBConfig config, const int* attrib_list) {
    if (!dpy || !config) return 0;

    unsigned int width = 1;
    unsigned int height = 1;
    if (attrib_list) {
        for (int i = 0; attrib_list[i] != None; i += 2) {
            int value = attrib_list[i + 1];
            if (attrib_list[i] == GLX_PBUFFER_WIDTH) {
                if (value <= 0 || value > 16384) return 0;
                width = value;
            }
            else if (attrib_list[i] == GLX_PBUFFER_HEIGHT) {
                if (value <= 0 || value > 16384) return 0;
                height = value;
            }
        }
    }

    Window root = DefaultRootWindow(dpy);
    return (GLXPbuffer)XCreateSimpleWindow(
            dpy, root, 0, 0, width, height, 0, 0, 0);
}
''',
        ),
        (
            '''void glXDestroyPbuffer(Display* dpy, GLXPbuffer pbuf) {
    println(MSG_DEBUG_UNIMPLEMENTED_GLXCALL, "glXDestroyPbuffer");
}
''',
            '''void glXDestroyPbuffer(Display* dpy, GLXPbuffer pbuf) {
    if (dpy && pbuf) XDestroyWindow(dpy, (Window)pbuf);
}
''',
        ),
        (
            '''void glXQueryDrawable(Display* dpy, GLXDrawable draw, int attribute, unsigned int* value) {
    println(MSG_DEBUG_UNIMPLEMENTED_GLXCALL, "glXQueryDrawable");
}
''',
            '''void glXQueryDrawable(Display* dpy, GLXDrawable draw, int attribute, unsigned int* value) {
    if (!value) return;
    *value = 0;

    if (attribute == GLX_FBCONFIG_ID) {
        *value = DEFAULT_FBCONFIG_ID;
        return;
    }
    if (attribute == GLX_PRESERVED_CONTENTS ||
        attribute == GLX_LARGEST_PBUFFER || attribute == GLX_EVENT_MASK) return;
    if (!dpy || !draw || (attribute != GLX_WIDTH && attribute != GLX_HEIGHT)) return;

    Window root;
    int x, y;
    unsigned int width, height, border, depth;
    if (XGetGeometry(dpy, (Drawable)draw, &root, &x, &y,
                     &width, &height, &border, &depth)) {
        *value = attribute == GLX_WIDTH ? width : height;
    }
}
''',
        ),
    )
    for old, new in replacements:
        if text.count(old) != 1:
            raise RuntimeError("pinned Gladio pbuffer anchor missing or ambiguous")
        text = text.replace(old, new)
    glx_calls.write_text(text, encoding="utf-8", newline="\n")

    # The pinned client/server protocol must preserve every bound attribute's
    # VBO offset in the transient payload. Omitting it either points position /
    # texture attributes at the VBO origin (black/off-screen geometry) or, for
    # BGRA conversion, walks beyond the ring-buffer mapping.
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
    text = text.replace(old, new)

    # Desktop compatibility arrays have semantic normalization rules.  The
    # upstream client normalized every integer array whenever GLSL was bound,
    # which turns integer positions/texture coordinates into [-1, 1] while
    # fragment-only fixed-vertex draws can leave colors unnormalized.  Keep the
    # rule independent of the selected program: only integer color and normal
    # arrays normalize.
    old = """            GLboolean normalized = type != GL_FLOAT && type != GL_HALF_FLOAT ? GL_TRUE : GL_FALSE; \\
            glVertexAttribPointer(INT32_MAX + index, size, type, normalized, stride, pointer); \\
"""
    new = """            GLboolean normalized = \\
                    (index == COLOR_ARRAY_INDEX || index == NORMAL_ARRAY_INDEX) && \\
                    type != GL_FLOAT && type != GL_HALF_FLOAT; \\
            glVertexAttribPointer(INT32_MAX + index, size, type, normalized, stride, pointer); \\
"""
    if text.count(old) != 1:
        raise RuntimeError("pinned Gladio compatibility-array normalization anchor missing or ambiguous")
    vao.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

    calls = SOURCE_TREE / "src" / "gl_calls.c"
    text = calls.read_text(encoding="utf-8")
    old = """    if (pname == GL_UNPACK_ROW_LENGTH) {
        currentGLContext->clientState->pixelStore.unpackRowLength = param;
    }
    else if (pname == GL_UNPACK_IMAGE_HEIGHT) {
        currentGLContext->clientState->pixelStore.unpackImageHeight = param;
    }
"""
    new = """    if (pname == GL_UNPACK_ALIGNMENT &&
            (param == 1 || param == 2 || param == 4 || param == 8)) {
        currentGLContext->clientState->pixelStore.unpackAlignment = param;
    }
    else if (param >= 0) {
        if (pname == GL_UNPACK_ROW_LENGTH) {
            currentGLContext->clientState->pixelStore.unpackRowLength = param;
        }
        else if (pname == GL_UNPACK_IMAGE_HEIGHT) {
            currentGLContext->clientState->pixelStore.unpackImageHeight = param;
        }
        else if (pname == GL_UNPACK_SKIP_PIXELS) {
            currentGLContext->clientState->pixelStore.unpackSkipPixels = param;
        }
        else if (pname == GL_UNPACK_SKIP_ROWS) {
            currentGLContext->clientState->pixelStore.unpackSkipRows = param;
        }
        else if (pname == GL_UNPACK_SKIP_IMAGES) {
            currentGLContext->clientState->pixelStore.unpackSkipImages = param;
        }
    }
"""
    if text.count(old) != 1:
        raise RuntimeError("pinned Gladio pixel-store tracking anchor missing")
    if TARGET_ABI != "x86_64":
        text = text.replace(old, new)
    old = """            clientState->vao->attribs[index].pointer = arrayBuffer ? arrayBuffer->mappedData : pointer;
            GLVertexArrayObject_setAttribState(clientState, index, VERTEX_ATTRIB_ENABLED, false);
"""
    new = """            clientState->vao->attribs[index].pointer = arrayBuffer ? arrayBuffer->mappedData : pointer;
            clientState->vao->attribs[index].pointerOffset =
                    arrayBuffer ? (uint64_t)pointer : 0;
            GLVertexArrayObject_setAttribState(clientState, index, VERTEX_ATTRIB_ENABLED, false);
"""
    if text.count(old) != 1:
        raise RuntimeError("pinned Gladio BGRA pointer anchor missing or ambiguous")
    calls.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

    # Phase-2 research corrections (see PHASE2_PATCH): applied after the
    # pixel-store/BGRA edits above so the unified diff context matches the
    # generated tree exactly; fail closed on any drift from the reviewed
    # post-patch content.
    if TARGET_ABI != "x86_64":
        subprocess.run(
            ["git", "apply", str(PHASE2_PATCH)],
            cwd=SOURCE_TREE, check=True,
        )
        patched_sha256 = sha256(calls)
        if patched_sha256 != PHASE2_GL_CALLS_SHA256:
            raise RuntimeError(
                "Gladio phase-2 gl_calls.c drift: "
                f"{patched_sha256} != {PHASE2_GL_CALLS_SHA256}"
            )
        subprocess.run(
            ["git", "apply", str(PHASE3_PATCH)],
            cwd=SOURCE_TREE, check=True,
        )
        patched_sha256 = sha256(calls)
        if patched_sha256 != PHASE3_GL_CALLS_SHA256:
            raise RuntimeError(
                "Gladio phase-3 gl_calls.c drift: "
                f"{patched_sha256} != {PHASE3_GL_CALLS_SHA256}"
            )

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

    # Phase-4 production transport corrections (see PHASE4_PATCH): applied
    # after every anchored text edit above so the unified diff context matches
    # the generated tree exactly; fail closed on drift in any touched file.
    if TARGET_ABI != "x86_64":
        subprocess.run(
            ["git", "apply", "--whitespace=nowarn", str(PHASE4_PATCH)],
            cwd=SOURCE_TREE, check=True,
        )
        for rel, expected in PHASE4_FILE_SHA256.items():
            got = sha256(SOURCE_TREE / rel)
            if got != expected:
                raise RuntimeError(
                    f"Gladio phase-4 drift in {rel}: {got} != {expected}"
                )


def build() -> None:
    container_build_root = f"/work/native/.build-{TARGET_BUILD_SUFFIX}/gladio-client"
    sources = " ".join(
        f"{container_build_root}/source/src/{name}"
        for name in (
            "main.c", "gl_calls.c", "glx_calls.c", "arrays.c",
            "ring_buffer.c", "gl_buffer.c", "gl_vao.c",
        )
    )
    commands = [
        f"export SOURCE_DATE_EPOCH={SOURCE_DATE_EPOCH}",
        "cd /work",
    ]
    if TARGET_ABI == "arm64-v8a":
        # These versions are resolved inside the immutable CGCT image.  The
        # foreign-architecture libX11 development package supplies the
        # AArch64 link interface; verification below rejects output requiring
        # a glibc newer than Winlator's pinned 2.39 runtime.
        commands.extend([
            "dpkg --add-architecture arm64",
            "apt-get update -qq",
            "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "
            + " ".join(ARM_CROSS_PACKAGES),
        ])
    commands.extend([
        (
            f"{TARGET_COMPILER} -std=gnu2x -O2 -fPIC -fvisibility=default "
            "-DGL_GLEXT_PROTOTYPES -pthread "
            "-ffile-prefix-map=/work=. -fdebug-prefix-map=/work=. "
            f"-I{container_build_root}/source/include "
            "-shared -Wl,--no-undefined -Wl,--as-needed "
            "-Wl,-soname,libGL.so.1 -Wl,-z,max-page-size=0x4000 "
            f"{sources} -lX11 -lm -o {container_build_root}/libGL.so.1"
        ),
        f"{TARGET_COMPILER} --version | head -1 > {container_build_root}/BUILD_TOOLCHAIN.txt",
    ])
    if TARGET_ABI == "arm64-v8a":
        commands.append(
            "dpkg-query -W -f='${Package}=${Version}\\n' "
            "gcc-aarch64-linux-gnu libc6-dev-arm64-cross libx11-dev:arm64 "
            f">> {container_build_root}/BUILD_TOOLCHAIN.txt"
        )
    command = " && ".join(commands)
    subprocess.run([
        "docker", "run", "--rm", "--user", "0",
        "-v", f"{docker_path(ROOT)}:/work", "--workdir", "/work",
        BUILDER_IMAGE, "sh", "-lc", command,
    ], check=True)


def verify() -> dict[str, object]:
    if not OUTPUT.is_file():
        raise FileNotFoundError(OUTPUT)
    from stage_wine_runtime import validate_elf_page_compatibility
    output_bytes = OUTPUT.read_bytes()
    validate_elf_page_compatibility(
        output_bytes, f"Gladio {TARGET_ABI} libGL.so.1"
    )
    if len(output_bytes) < 20 or output_bytes[:4] != b"\x7fELF":
        raise RuntimeError(f"Gladio output is not ELF: {OUTPUT}")
    if output_bytes[5] != 1:
        raise RuntimeError("Gladio output must be little-endian ELF")
    machine = int.from_bytes(output_bytes[18:20], "little")
    if machine != TARGET_MACHINE:
        raise RuntimeError(
            f"Gladio ELF e_machine={machine}, expected {TARGET_MACHINE} for {TARGET_ABI}"
        )
    output_sha256 = hashlib.sha256(output_bytes).hexdigest()
    if TARGET_EXPECTED_SHA256 and output_sha256 != TARGET_EXPECTED_SHA256:
        raise RuntimeError(
            f"Gladio {TARGET_ABI} output drift: {output_sha256} != "
            f"{TARGET_EXPECTED_SHA256}"
        )

    container_output = f"/work/native/.build-{TARGET_BUILD_SUFFIX}/gladio-client/libGL.so.1"
    readelf = subprocess.check_output(
        ["docker", "run", "--rm", "--user", "0",
         "-v", f"{docker_path(ROOT)}:/work", BUILDER_IMAGE,
         "readelf", "-d", container_output],
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
    allowed = {TARGET_LOADER, "libX11.so.6", "libc.so.6", "libm.so.6"}
    unexpected = sorted(set(needed) - allowed)
    if unexpected:
        raise RuntimeError(f"unexpected Gladio client dependencies: {unexpected}")
    required = {TARGET_LOADER, "libX11.so.6", "libc.so.6"}
    missing = sorted(required - set(needed))
    if missing:
        raise RuntimeError(f"missing Gladio client dependencies: {missing}")

    glibc_versions: list[str] = []
    if TARGET_ABI == "arm64-v8a":
        version_info = subprocess.check_output(
            ["docker", "run", "--rm", "--user", "0",
             "-v", f"{docker_path(ROOT)}:/work", BUILDER_IMAGE,
             "readelf", "--version-info", container_output],
            text=True,
        )
        glibc_versions = sorted(
            set(re.findall(r"GLIBC_([0-9]+(?:\.[0-9]+)+)", version_info)),
            key=lambda value: tuple(int(part) for part in value.split(".")),
        )
        too_new = [
            version for version in glibc_versions
            if tuple(int(part) for part in version.split(".")) > (2, 39)
        ]
        if too_new:
            raise RuntimeError(
                "AArch64 Gladio client exceeds Winlator glibc 2.39: "
                + ", ".join(too_new)
            )

        if not WINLATOR_ROOTFS.is_file():
            raise FileNotFoundError(
                f"pinned Winlator rootfs is unavailable: {WINLATOR_ROOTFS}"
            )
        rootfs_entries = set(subprocess.check_output(
            ["tar", "-tf", str(WINLATOR_ROOTFS)], text=True,
        ).splitlines())
        runtime_members = {
            "./usr/lib/ld-linux-aarch64.so.1",
            "./usr/lib/libX11.so.6",
            "./usr/lib/libc.so.6",
        }
        absent_members = sorted(runtime_members - rootfs_entries)
        if absent_members:
            raise RuntimeError(
                "Winlator AArch64 runtime closure is incomplete: "
                + ", ".join(absent_members)
            )

    provenance: dict[str, object] = {
        "schema": 1,
        "declared_abi": TARGET_ABI,
        "elf_machine": machine,
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
            "Back GLX pbuffers with bounded, unmapped X drawables so WineD3D "
            "can complete its final shared-context probe before first swap.",
        ],
        "soname": "libGL.so.1",
        "dt_needed": needed,
        "output": {"path": str(OUTPUT.relative_to(ROOT)).replace("\\", "/"),
                   "size": OUTPUT.stat().st_size, "sha256": output_sha256},
        "toolchain": (BUILD_ROOT / "BUILD_TOOLCHAIN.txt").read_text(
            encoding="utf-8"
        ).splitlines(),
    }
    if TARGET_ABI != "x86_64":
        provenance["adaptations"].append(
            "Preserve all size-affecting desktop GL_UNPACK state and transmit "
            "the exact client-memory span for uncompressed texture uploads; "
            "compressed payload sizes remain explicit and pixel-store independent."
        )
        provenance["adaptations"].append(
            "Phase-2 WoW 1.12.1 research corrections: OpenGL 2.1 table 2.9 "
            "signed/unsigned integer color/normal/secondary-color "
            "normalization, client-active-texture unit selection for "
            "glMultiTexCoordPointerEXT and indexed client-state enables, "
            "clamped program/shader info-log replies, populated "
            "glAreTexturesResident output."
        )
        provenance["adaptations"].append(
            "WoW texgen/light-model vectors: forward glTexGen{d,f,i}v and "
            "glLightModel{f,i}v with plane/ambient-aware payload counts "
            "(previously stubbed on both ends, corrupting water and ambient "
            "light), and log remaining unimplemented calls once per name so "
            "per-frame stderr spam cannot back-pressure the tracked output "
            "pipe into a render-thread freeze."
        )
    if TARGET_ABI == "arm64-v8a":
        provenance["glibc_versions"] = [f"GLIBC_{value}" for value in glibc_versions]
        provenance["runtime_compatibility"] = {
            "rootfs": str(WINLATOR_ROOTFS.relative_to(ROOT)).replace("\\", "/"),
            "rootfs_sha256": sha256(WINLATOR_ROOTFS),
            "maximum_glibc": "2.39",
            "runtime_destination": "generation-local/graphics/libGL.so.1",
        }
    return provenance


def stage_arm_client(provenance: dict[str, object]) -> None:
    if STAGED_OUTPUT is None:
        return
    STAGED_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = STAGED_OUTPUT.with_name(f".{STAGED_OUTPUT.name}.tmp")
    shutil.copyfile(OUTPUT, temporary)
    os.replace(temporary, STAGED_OUTPUT)
    if sha256(STAGED_OUTPUT) != sha256(OUTPUT):
        raise RuntimeError("staged AArch64 Gladio client does not match build output")
    provenance["staging"] = {
        "path": str(STAGED_OUTPUT.relative_to(ROOT)).replace("\\", "/"),
        "size": STAGED_OUTPUT.stat().st_size,
        "sha256": sha256(STAGED_OUTPUT),
        "runtime_destination": "generation-local/graphics/libGL.so.1",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--abi", choices=("x86_64", "arm64-v8a"), default="x86_64",
        help="target client ABI (default: existing x86_64 lane)",
    )
    args = parser.parse_args()
    select_target(args.abi)
    acquire_source()
    prepare_source()
    build()
    provenance = verify()
    stage_arm_client(provenance)
    PROVENANCE.write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(provenance, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
