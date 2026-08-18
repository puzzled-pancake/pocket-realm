from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "native" / "xserver-winlator" / "cpp"
VORTEK = CPP / "vortekrenderer"
JAVA = (
    ROOT
    / "runtime"
    / "xserver-winlator"
    / "com"
    / "winlator"
    / "xenvironment"
    / "components"
    / "VortekRendererComponent.java"
)
WINDOW = (
    ROOT
    / "runtime"
    / "xserver-winlator"
    / "com"
    / "winlator"
    / "xserver"
    / "Window.java"
)
WINDOW_LIFETIME = WINDOW.with_name("WindowAuthorityLifetime.java")
WINDOW_BINDINGS = JAVA.with_name("WindowAuthorityBindings.java")
WINDOW_ELIGIBILITY = JAVA.with_name("WindowAuthorityEligibility.java")
GPU_IMAGE = (
    ROOT
    / "runtime"
    / "xserver-winlator"
    / "com"
    / "winlator"
    / "renderer"
    / "GPUImage.java"
)


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_thread_pool_owns_joinable_workers_and_cleans_cancelled_jobs():
    source = _read(CPP / "include" / "thread_pool.h")
    assert "pthread_detach" not in source
    assert "pthread_join(threadPool->threads[i]" in source
    assert "ThreadPool_TaskCleanupFunc" in source
    assert "THREAD_POOL_MAX_QUEUED_TASKS" in source
    assert "cancellationRequested" in source
    assert "threadPool->numThreads = requestedThreads" in source


def test_extra_data_is_bounded_one_shot_and_condition_driven():
    header = _read(VORTEK / "include" / "vk_context.h")
    source = _read(VORTEK / "src" / "vk_context.c")
    assert "VORTEK_EXTRA_DATA_MAX_FRAME_SIZE" in header
    assert "VORTEK_EXTRA_DATA_MAX_AGGREGATE_SIZE" in header
    assert "VORTEK_EXTRA_DATA_MAX_PENDING" in header
    assert "seenExtraDataRequestIds" in header
    assert "pthread_cond_timedwait" in source
    assert "busyWait" not in source
    assert "readExactBeforeDeadline" in source
    assert "requestIdWasSeenLocked" in source
    assert "handleRequestFunc" in source and "VkContext_requestStop" in source


def test_async_paths_own_decoded_graphs_and_cancel_with_device_leases():
    pipeline = _read(VORTEK / "src" / "async_pipeline_creator.c")
    timeline = _read(VORTEK / "src" / "timeline_semaphore.c")
    for source in (pipeline, timeline):
        assert "MemoryPool memoryPool;" in source
        assert "vt_request_decode_pass_begin" in source
        assert "vt_decode_finished" in source
        assert "VkContext_acquireDeviceLease" in source
        assert "VkContext_releaseDeviceLease" in source
        assert "ThreadPool_runWithCleanup" in source
    assert "VkObjectAuthority_publishVulkanBatch" in pipeline
    assert "VkObjectAuthority_rollbackBatchWithLease" in pipeline
    assert "destroyPipelines" in pipeline
    assert "VORTEK_TIMELINE_WAIT_SLICE_NS" in timeline
    assert "ThreadPool_isCancellationRequested" in timeline


def test_java_control_plane_rejects_ambiguous_ownership_and_unknown_requests():
    source = _read(JAVA)
    assert "MAX_EXTRA_DATA_SIZE" in source
    assert "Repeated Vortek context creation" in source
    assert "client.setTag(null)" in source
    assert "Unknown Vortek control request" in source
    assert "requestLength != 0" in source
    assert "public boolean hardenedSafeLane = true" in source


def test_native_context_requires_explicit_hardened_safe_lane():
    header = _read(VORTEK / "include" / "vk_context.h")
    source = _read(VORTEK / "src" / "vk_context.c")
    assert "bool hardenedSafeLane" in header
    assert 'getJFieldByName(env, options, "hardenedSafeLane", "Z")' in source
    assert "if (!context->hardenedSafeLane) goto error" in source


def test_production_dispatch_opens_only_in_reviewed_bridge_target():
    request_header = _read(VORTEK / "include" / "request_handler.h")
    cmake = _read(CPP / "CMakeLists.txt")
    assert "#define VORTEK_REQUEST_HANDLE_AUTHORITY_COMPLETE 0" in request_header
    assert "VORTEK_REQUEST_HANDLE_AUTHORITY_COMPLETE=1" in cmake
    assert "Production opens dispatch only here" in cmake


def test_window_authority_is_generation_instance_and_lifetime_bound():
    component = _read(JAVA)
    window = _read(WINDOW)
    lifetime = _read(WINDOW_LIFETIME)
    bindings = _read(WINDOW_BINDINGS)
    eligibility = _read(WINDOW_ELIGIBILITY)
    registry = _read(VORTEK / "src" / "handle_registry.c")
    context = _read(VORTEK / "src" / "vk_context.c")
    request = _read(VORTEK / "src" / "request_handler.c")

    assert "AtomicLong NEXT" in lifetime
    assert "current == Long.MAX_VALUE ? 0L" in lifetime
    assert "public final long authorityLifetime" in window
    assert "WindowAuthorityLifetime.allocate()" in window
    assert "WindowAuthorityBindings windowAuthority" in component
    assert "activeGenerations" in bindings
    assert "Map<Key, Long> bindings" in bindings
    assert "return bound == lifetime ? bound : 0L" in bindings
    assert "XServer.Lockable.WINDOW_MANAGER" in component
    assert "Window.MapState.VIEWABLE" in component
    assert "window == xServer.windowManager.rootWindow" in component
    assert "window != null && window.originClient != null" in component
    assert "present && !root && viewable && hasOriginClient" in eligibility
    assert "width > 0 && height > 0 && lifetime > 0L" in eligibility
    assert "registerWindowAuthorityGeneration" in context
    assert context.index("cacheJMethods(&context->jmethods, env)") < context.index(
        "setupRingBuffers(context)"
    )
    assert context.index("VortekHandleRegistry_setWindowValidator") < context.index(
        "setupRingBuffers(context)"
    )
    assert context.index("ThreadPool_destroy(context->threadPool)") < context.rindex(
        "unregisterWindowAuthorityGeneration"
    )
    assert "VortekWindowBinding" in registry
    assert "binding->lifetime == lifetime" in registry
    assert "purgeWindowBindingsLocked" in registry
    assert "wireValue > INT32_MAX" in registry
    assert "VkContext_releaseWindowInstanceAuthority" in request
    destroy_instance = request[request.index("void vt_handle_vkDestroyInstance"):]
    destroy_instance = destroy_instance[:destroy_instance.index("\nvoid vt_handle_")]
    assert destroy_instance.index("VkContext_reclaimAuthority") < destroy_instance.index(
        "VkContext_releaseWindowInstanceAuthority"
    )
    assert "VORTEK_HANDLE_DRAIN_INSTANCE" in destroy_instance


def test_window_operations_revalidate_and_swapchain_creation_is_transactional():
    component = _read(JAVA)
    gpu_image = _read(GPU_IMAGE)
    gpu_image_native = _read(CPP / "src" / "gpu_image.c")
    swapchain = _read(VORTEK / "src" / "xwindow_swapchain.c")
    transaction = _read(VORTEK / "include" / "xwindow_swapchain_transaction.h")
    transaction_test = _read(VORTEK / "tests" / "xwindow_transaction_test.c")

    assert "getWindowExtentAuthority" in component
    assert "getWindowHardwareBufferAuthority" in component
    assert "updateWindowContentAuthority" in component
    assert "synchronized (drawable.renderLock)" in component
    assert "isSameEligibleWindowLocked" in component
    assert "acquireHardwareBufferPtr" in gpu_image
    assert "GPUImage_acquireHardwareBuffer" in gpu_image_native
    assert "AHardwareBuffer_acquire(hardwareBuffer)" in gpu_image_native

    assert "*swapchainOut = NULL;" in swapchain
    assert "VORTEK_XWINDOW_SWAPCHAIN_MAX_IMAGES" in swapchain
    assert "vkGetAndroidHardwareBufferPropertiesANDROID" in swapchain
    assert "if (result != VK_SUCCESS) return result;" in swapchain
    assert "findMemoryTypeIndex" in swapchain
    assert "vkBindImageMemory" in swapchain
    assert "freeTransactionMemory" in swapchain
    assert "destroyTransactionImage" in swapchain
    assert "AHardwareBuffer_release(context->hardwareBuffer);" in swapchain
    assert "vt_xwindow_transaction_allocate_heap" in swapchain
    assert "vt_xwindow_transaction_build" in swapchain
    assert "vt_xwindow_transaction_rollback" in swapchain
    assert "if (image != 0) ops->destroyImage" in transaction
    assert "if (memory != 0) ops->freeMemory" in transaction
    assert "FAIL_BEGIN" in transaction_test
    assert "FAIL_CREATE" in transaction_test
    assert "FAIL_ALLOCATE" in transaction_test
    assert "FAIL_BIND" in transaction_test
    assert "failCall <= 2" in transaction_test
    assert "XWindowSwapchain_destroy(device, swapchain);" in swapchain
    assert swapchain.index("getWindowExtent(") < swapchain.index(
        "vkQueueSubmit("
    )


def test_device_leases_block_retirement_and_context_close_is_ordered():
    header = _read(VORTEK / "include" / "vk_context.h")
    source = _read(VORTEK / "src" / "vk_context.c")
    registry = _read(VORTEK / "src" / "handle_registry.c")
    request = _read(VORTEK / "src" / "request_handler.c")
    assert "VkContext_acquireDeviceLease" in header
    assert "VkContext_releaseDeviceLease" in header
    assert "VkObjectAuthority_beginClose(context->handleAuthority)" in source
    assert source.index("ThreadPool_destroy(context->threadPool)") < source.index(
        "VkObjectAuthority_close(context->handleAuthority)"
    )
    assert "activeCalls" in registry and "VORTEK_HANDLE_RETIRING" in registry
    assert "validateExpectationLockedEx" in registry
    assert "entryHasLiveDependentsLocked" in registry
    assert "VortekHandleRegistry_drainNext" in registry
    assert "if (!drained) return false;" in registry
    assert "memset(registry->entries" not in registry
    assert "unregisterDeviceOwned" not in registry
    assert "unregisterInstanceOwned" not in registry
    assert "VkContext_beginDeviceRetirement" in request
    assert "VkContext_beginInstanceRetirement" in request
    assert "VkContext_reclaimAuthority" in source
    destroy_context = source[source.index("void destroyVkContext"):]
    assert destroy_context.index("VkObjectAuthority_close(context->handleAuthority)") < destroy_context.index(
        "VkContext_reclaimAuthority("
    )
    assert destroy_context.index("VkContext_reclaimAuthority(") < destroy_context.index(
        "VkObjectAuthority_destroy(context->handleAuthority)"
    )
    assert "context->debugReportCallback = VK_NULL_HANDLE;" in source

    pipeline = _read(VORTEK / "src" / "async_pipeline_creator.c")
    rollback = pipeline[pipeline.index("const VortekHandleStatus rollbackStatus"):]
    rollback = rollback[:rollback.index("done:")]
    assert "if (rollbackStatus != VORTEK_HANDLE_OK)" in rollback
    assert "else {" in rollback
    assert rollback.index("else {") < rollback.index("destroyPipelines(")
