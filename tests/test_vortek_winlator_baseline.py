from __future__ import annotations

import hashlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VORTEK = ROOT / "native/xserver-winlator/cpp/vortekrenderer-winlator-2.1"
EXPECTED_SOURCE_COUNT = 41
# Re-pinned 2026-08-16 (devibe Phase 1): the previous digest captured
# uncommitted CRLF dirt in the working tree; the tree now byte-matches its
# committed blobs (deterministic across fresh checkouts).
EXPECTED_SOURCE_DIGEST = "d74c5269183330ad186a0cf51997c1841aecec1096577e5e885055297fda686b"


def source_digest() -> tuple[int, str]:
    digest = hashlib.sha256()
    files = sorted(
        path
        for path in VORTEK.rglob("*")
        if path.is_file() and path.name != "README.pocketrealm.md"
    )
    for path in files:
        relative = path.relative_to(VORTEK).as_posix().encode()
        contents = path.read_bytes()
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        digest.update(len(contents).to_bytes(8, "big"))
        digest.update(contents)
    return len(files), digest.hexdigest()


def test_vortek_sources_are_the_pinned_winlator_2_1_compatibility_import() -> None:
    assert source_digest() == (EXPECTED_SOURCE_COUNT, EXPECTED_SOURCE_DIGEST)


def test_source_matched_import_is_the_system_production_protocol() -> None:
    cmake = (ROOT / "native/xserver-winlator/cpp/CMakeLists.txt").read_text()
    adapter = (ROOT / "native/xserver-winlator/cpp/src/vortek_system_main.c").read_text()

    assert "set(VORTEK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vortekrenderer-winlator-2.1)" in cmake
    assert "handle_registry.c" not in cmake
    assert "vortek_decode.c" not in cmake
    assert "VORTEK_REQUEST_HANDLE_AUTHORITY_COMPLETE=1" not in cmake
    assert "${CMAKE_CURRENT_SOURCE_DIR}/src/vortek_system_main.c" in cmake
    assert "${VORTEK_DIR}/src/main.c" not in cmake
    assert "vortek_safe_lane.c" not in cmake
    assert 'dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL)' in adapter
    assert "adrenotools" not in adapter.lower()


def test_java_bridge_matches_winlators_jni_contract_with_bounded_control_frames() -> None:
    component = (
        ROOT
        / "runtime/xserver-winlator/com/winlator/xenvironment/components/"
        "VortekRendererComponent.java"
    ).read_text()

    for callback in (
        "getWindowWidth",
        "getWindowHeight",
        "getWindowHardwareBuffer",
        "updateWindowContent",
    ):
        assert callback in component
    assert "MAX_EXTRA_DATA_SIZE" in component
    assert "Repeated Vortek context creation" in component
    assert "Unknown Vortek control request" in component
    assert "hardenedSafeLane" not in component


def test_startup_boundary_has_bounded_native_milestones() -> None:
    handler = (VORTEK / "src/request_handler.c").read_text()

    assert "milestone=CREATE_INSTANCE" in handler
    assert "milestone=ENUMERATE_PHYSICAL_DEVICES" in handler
    assert "milestone=CREATE_DEVICE" in handler


def test_display_starts_and_stops_vortek_around_x_transport() -> None:
    display = (
        ROOT
        / "android/app/src/main/java/com/pocketrealm/client/ClientDisplayHost.kt"
    ).read_text()

    assert display.index("sysvConnector.start()") < display.index("connector.start()")
    assert display.index("connector.start()") < display.index("vortekComponent?.start()")
    close_start = display.index("override fun close()")
    close_body = display[close_start:]
    assert close_body.index("vortekComponent?.close()") < close_body.index("connector.destroy()")


def test_production_keeps_source_shader_compatibility() -> None:
    helper = (
        VORTEK / "src/vulkan_helper.c"
    ).read_text()
    inspector = (
        VORTEK / "src/shader_inspector.c"
    ).read_text()

    assert "else if (isFormatScaled(format))" in helper
    assert "formatProperties->bufferFeatures |= VK_FORMAT_FEATURE_VERTEX_BUFFER_BIT" in helper
    assert "shaderInspector->convertFormatScaled = true" in inspector
    assert "supportedFeatures->shaderClipDistance == VK_FALSE" in inspector
    assert "shaderInspector->removeImageBoundCheck = isMaliDevice && isDXVKEngine" in inspector
    assert "context->engineVersion > MAKE_ENGINE_VERSION(2, 3, 1)" in inspector


def test_production_accepts_the_source_ahardwarebuffer_memory_route() -> None:
    helper = (
        VORTEK / "src/vulkan_helper.c"
    ).read_text()
    resource_memory = (
        VORTEK / "src/resource_memory.c"
    ).read_text()

    assert "!context->hasExternalMemoryFd" not in helper
    assert "VK_ERROR_INCOMPATIBLE_DRIVER" not in helper
    assert "VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID" in resource_memory


def test_display_selects_an_mmap_compatible_resource_memory_transport() -> None:
    component = (
        ROOT
        / "runtime/xserver-winlator/com/winlator/xenvironment/components/"
        "VortekRendererComponent.java"
    ).read_text()
    display = (
        ROOT
        / "android/app/src/main/java/com/pocketrealm/client/ClientDisplayHost.kt"
    ).read_text()

    assert "RESOURCE_MEMORY_TYPE_DMA_BUF = 2" in component
    assert (
        "resourceMemoryType = "
        "VortekRendererComponent.Options.RESOURCE_MEMORY_TYPE_DMA_BUF"
    ) in display


def test_memory_type_selection_requires_every_requested_property_bit() -> None:
    helper = (VORTEK / "src/vulkan_helper.c").read_text()

    assert "(flags & properties) == properties" in helper
    assert "propertyFlags & properties)) return i" not in helper

    def select(type_bits: int, properties: int, available: list[int]) -> int:
        for index, flags in enumerate(available):
            if type_bits & 1 and flags & properties == properties:
                return index
            type_bits >>= 1
        return 0

    host_visible = 0x2
    host_coherent = 0x4
    host_cached = 0x8
    memory_types = [
        host_visible | host_cached,
        host_visible | host_coherent,
        host_coherent,
    ]
    assert select(0b111, host_visible | host_coherent, memory_types) == 1
    assert select(0b101, host_visible | host_coherent, memory_types) == 0
    assert select(0b100, host_coherent, memory_types) == 2


def test_memory_transport_diagnostics_are_session_bounded_and_cover_fd_handoff() -> None:
    context = (VORTEK / "include/vk_context.h").read_text()
    resource = (VORTEK / "src/resource_memory.c").read_text()
    handler = (VORTEK / "src/request_handler.c").read_text()

    assert "VORTEK_MEMORY_DIAGNOSTIC_LIMIT 64" in context
    assert "memoryDiagnosticCount >= VORTEK_MEMORY_DIAGNOSTIC_LIMIT" in context
    assert "event=allocate route=%s" in resource
    assert "fstat(resourceMemory->fd" in resource
    assert "event=send-map route=%d" in handler
    assert "sendmsgErrno=%d" in handler
    assert "fstat(resourceMemory->fd" in handler


def test_source_built_guest_is_pinned_and_reports_receiver_map_errno() -> None:
    builder = (ROOT / "tools/build_vortek_guest.py").read_text()
    stage = (ROOT / "tools/stage_renderer_packages.py").read_text()
    gradle = (ROOT / "android/app/build.gradle.kts").read_text()

    assert 'SOURCE_COMMIT = "ab7329c4b445a4abd9b9af91b8148e1ca41464fa"' in builder
    assert "ubuntu@sha256:561618e2c15bf2397621dd04f96926663a3b5616c189cf7e38db7e82f5c538ea" in builder
    assert "VORTEK_MEMORY_DIAGNOSTIC_LIMIT 32" in builder
    assert "PocketVortekGuest: event=memory-map" in builder
    assert 'placedAddr ? "placed" : "map2"' in builder
    assert "recvmsgErrno=%d" in builder
    assert "mmapErrno=%d" in builder
    assert 'VORTEK_GUEST_SIZE = 406_496' in stage
    assert 'VORTEK_GUEST_SHA256 = "10208431d516184e0e92ac1612a04c25a2e6a8925e89f3ef4829ee35eb1164d9"' in stage
    assert '"protocol_profile": "winlator-2.1-source-matched"' in stage
    assert 'tasks.register<Exec>("buildVortekGuest")' in gradle
    assert "buildVortekGuest?.let { dependsOn(it) }" in gradle


def test_compatibility_tree_retains_behavior_transparent_lifecycle_bounds() -> None:
    context = (VORTEK / "src/vk_context.c").read_text()
    swapchain = (VORTEK / "src/xwindow_swapchain.c").read_text()

    assert "VORTEK_EXTRA_DATA_MAX_FRAME_SIZE" in context
    assert "while (total < requestLength)" in context
    assert "ExtraDataRequest* extraDataRequest = NULL;" in context
    assert "requestHandlerThreadStarted" in context
    assert "if (!hardwareBuffer) return VK_ERROR_SURFACE_LOST_KHR;" in swapchain
    assert "vkDestroyImage(device, image, NULL)" in swapchain


def test_context_ownership_has_a_callback_independent_drain_path() -> None:
    component = (
        ROOT
        / "runtime/xserver-winlator/com/winlator/xenvironment/components/"
        "VortekRendererComponent.java"
    ).read_text()
    registry = (
        ROOT
        / "runtime/xserver-winlator/com/winlator/xenvironment/components/"
        "VortekContextRegistry.java"
    ).read_text()
    display = (
        ROOT / "android/app/src/main/java/com/pocketrealm/client/ClientDisplayHost.kt"
    ).read_text()

    assert "new VortekContextRegistry(VortekRendererComponent::destroyVkContext)" in component
    assert component.index("int count = registerContext(contextPtr)") < component.index(
        "client.setTag(contextPtr)"
    )
    assert 'drainTrackedContexts("component-close")' in component
    assert 'drainTrackedContexts("component-stop")' in component
    assert "if (!contexts.remove(contextPtr)) return false" in registry
    assert "contexts.clear()" in registry
    close_body = display[display.index("override fun close()") :]
    assert close_body.index("vortekComponent?.close()") < close_body.index(
        "CountDownLatch(1)"
    )
    assert "VortekRendererComponent.reclaimLeakedContexts()" in close_body


def test_idle_ring_wait_uses_peer_loss_and_progressive_backoff() -> None:
    ring_header = (ROOT / "native/xserver-winlator/cpp/include/ring_buffer.h").read_text()
    ring = (ROOT / "native/xserver-winlator/cpp/src/ring_buffer.c").read_text()
    context_header = (VORTEK / "include/vk_context.h").read_text()
    context = (VORTEK / "src/vk_context.c").read_text()

    assert "int peerFd;" in ring_header
    assert "RingBuffer_setPeerFd" in ring_header
    assert "RING_WAIT_MAX_SLEEP_US 2000u" in ring
    assert "poll(&peer, 1" in ring
    assert "POLLHUP | POLLERR | POLLNVAL" in ring
    assert "if (ring->peerFd < 0)" in ring
    assert "busyWait(iteration)" in ring
    assert "_Atomic int status" in context_header
    assert "RingBuffer_setPeerFd(context->serverRing, context->clientFd)" in context
    assert "RingBuffer_setPeerFd(context->clientRing, context->clientFd)" in context
