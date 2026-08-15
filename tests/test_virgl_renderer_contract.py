from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_guest_payload_and_provenance_are_exactly_pinned():
    source = text("tools/stage_virgl_renderer.py")
    archive = re.search(r'ARCHIVE_SHA256 = "([0-9a-f]+)"', source).group(1)
    client = re.search(r'CLIENT_SHA256 = "([0-9a-f]+)"', source).group(1)
    assert len(archive) == 64
    assert archive == "614b1edc8e47c57b2cbb2d96f9c7ab5f5b1a89038de618a58b2faf9c64380e09"
    assert len(client) == 64
    assert client == "531e3dc809281feadcc2120abc6d9f88025d92d567ac32eed9c376bd9e4e04f6"
    assert 'MEMBER = "./usr/lib/libGL.so.1.7.0"' in source


def test_native_server_uses_current_generation_and_transactional_lifecycle():
    server = text("native/xserver-winlator/cpp/virglrenderer/server/virgl_server.c")
    renderer = text(
        "native/xserver-winlator/cpp/virglrenderer/server/virgl_server_renderer.c"
    )
    component = text(
        "runtime/xserver-winlator/com/winlator/xenvironment/components/"
        "VirGLRendererComponent.java"
    )
    assert "active_client_mutex" in server
    assert "virgl_server_command_length_valid" in server
    assert "client->initialized = true" in server
    assert server.index("if (ret < 0)") < server.index("client->initialized = true")
    assert '#include "egl_context_registry.h"' in renderer
    assert "pthread_mutex_lock(&globalEGLContextMutex)" in renderer
    assert "globalEGLContextGeneration != renderer->surface_generation" in renderer
    assert "renderer->vrend_initialized" in renderer
    assert "renderer->context_created" in renderer
    assert "VIRGL_MAX_SHM_BYTES" in renderer
    assert "eglTerminate" not in renderer
    # A failed native request can synchronously schedule connection teardown.
    # Readiness milestones therefore travel in the request result; Java must
    # never query a possibly freed native client pointer afterwards.
    assert "int milestones = handleRequest(clientPtr);" in component
    assert "isClientInitialized" not in component
    assert "isClientCapsReady" not in component
    assert "return 1 | (header[1] == VCMD_GET_CAPS ? 2 : 0);" in server
    assert "client->request_env = NULL;" in server
    assert "connector.setMultithreadedClients(true);" in component
    assert "EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR" in renderer
    assert "EGL_SURFACE_TYPE, EGL_PBUFFER_BIT" in renderer
    assert "EGL_ALPHA_SIZE, 8" in renderer


def test_arm_only_build_and_runtime_have_no_renderer_fallback():
    cmake = text("native/xserver-winlator/cpp/CMakeLists.txt")
    service = text(
        "android/app/src/main/java/com/pocketrealm/client/ClientRuntimeService.kt"
    )
    assert 'if(ANDROID_ABI STREQUAL "arm64-v8a")\n    add_subdirectory(virglrenderer)' in cmake
    for setting in (
        "GALLIUM_DRIVER=virpipe",
        "VIRGL_NO_READBACK=true",
        "VIRGL_SERVER_PATH=",
        "MESA_GL_VERSION_OVERRIDE=3.1",
    ):
        assert setting in service
    assert '"VirGL readiness timed out:' in service


def test_unscoped_rootfs_opengl_client_is_removed_only_at_provisioning():
    provisioner = text(
        "android/app/src/main/java/com/pocketrealm/client/ArmRootfsProvisioner.kt"
    )
    store = text(
        "android/app/src/main/java/com/pocketrealm/client/WineRuntimeStore.kt"
    )
    assert "stripUnscopedOpenGlClient(staging)" in provisioner
    assert "hasNoUnscopedOpenGlClient(rootfs)" in provisioner
    assert 'private const val SCHEMA = 4' in provisioner
    assert "disableArmOpenGlClient" not in store
