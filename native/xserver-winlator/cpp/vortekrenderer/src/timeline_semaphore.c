#include "timeline_semaphore.h"

#include "request_handler.h"
#include "socket_utils.h"
#include "vk_context.h"
#include "vulkan_helper.h"

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <unistd.h>

#define VORTEK_TIMELINE_WAIT_SLICE_NS (50ull * 1000ull * 1000ull)

typedef struct WaitSemaphoresRequest {
    VkContext* context;
    VkDevice device;
    uint64_t deviceToken;
    VkSemaphoreWaitInfo waitInfo;
    uint64_t timeout;
    MemoryPool memoryPool;
    VortekDeviceLease deviceLease;
    int notifyFd;
} WaitSemaphoresRequest;

static bool writeExact(int fd, const void* data, size_t size) {
    const uint8_t* current = data;
    size_t remaining = size;
    while (remaining > 0) {
        ssize_t written = write(fd, current, remaining);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        current += (size_t)written;
        remaining -= (size_t)written;
    }
    return true;
}

static void freeOwnedPool(MemoryPool* memoryPool) {
    if (!memoryPool) return;
    vt_free(memoryPool);
    ArrayList_free(&memoryPool->allocationList, false);
    free(memoryPool->data);
    memset(memoryPool, 0, sizeof(*memoryPool));
}

static uint64_t resultToken(VkResult result) {
    return result == VK_SUCCESS ? 1u :
            result == VK_ERROR_DEVICE_LOST ? 2u : 3u;
}

static void releaseWaitRequest(WaitSemaphoresRequest* request) {
    if (!request) return;
    if (request->notifyFd >= 0) {
        close(request->notifyFd);
        request->notifyFd = -1;
    }
    if (request->deviceLease.active) {
        (void)VkContext_releaseDeviceLease(&request->deviceLease);
    }
    freeOwnedPool(&request->memoryPool);
    free(request);
}

static bool notifyWaitResult(
        WaitSemaphoresRequest* request, VkResult result) {
    if (!request || request->notifyFd < 0) return false;
    const uint64_t token = resultToken(result);
    return writeExact(request->notifyFd, &token, sizeof(token));
}

static bool waitCancelled(const WaitSemaphoresRequest* request) {
    return !request || !request->context ||
            VkContext_isClosing(request->context) ||
            VortekHandleRegistry_deviceLeaseShouldCancel(
                    &request->deviceLease) ||
            ThreadPool_isCancellationRequested(request->context->threadPool);
}

static VkResult waitWithCancellation(WaitSemaphoresRequest* request) {
    if (!request || !vulkanWrapper.vkWaitSemaphores) {
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }
    if (waitCancelled(request)) return VK_ERROR_DEVICE_LOST;

    if (request->timeout == 0) {
        return vulkanWrapper.vkWaitSemaphores(
                request->device, &request->waitInfo, 0);
    }

    uint64_t remaining = request->timeout;
    for (;;) {
        if (waitCancelled(request)) return VK_ERROR_DEVICE_LOST;
        const uint64_t slice = remaining == UINT64_MAX ||
                remaining > VORTEK_TIMELINE_WAIT_SLICE_NS
                ? VORTEK_TIMELINE_WAIT_SLICE_NS : remaining;
        VkResult result = vulkanWrapper.vkWaitSemaphores(
                request->device, &request->waitInfo, slice);
        if (result != VK_TIMEOUT) return result;
        if (remaining != UINT64_MAX) {
            if (remaining <= slice) return VK_TIMEOUT;
            remaining -= slice;
        }
    }
}

static void waitSemaphoresThread(void* opaque) {
    WaitSemaphoresRequest* request = opaque;
    VkResult result = waitWithCancellation(request);
    if (!notifyWaitResult(request, result) && request && request->context) {
        VkContext_requestStop(request->context, VK_ERROR_DEVICE_LOST);
    }
    releaseWaitRequest(request);
}

static void cancelWaitRequest(void* opaque) {
    WaitSemaphoresRequest* request = opaque;
    if (!notifyWaitResult(request, VK_ERROR_DEVICE_LOST) &&
            request && request->context) {
        VkContext_requestStop(request->context, VK_ERROR_DEVICE_LOST);
    }
    releaseWaitRequest(request);
}

static bool decodeOwnedWait(
        WaitSemaphoresRequest* request,
        VtRequestDecode* decode) {
    VtDecodeCursor cursor;
    uint64_t decodedDeviceToken = 0;
    uint64_t decodedTimeout = 0;
    if (!request || !decode ||
            !vt_request_decode_pass_begin(decode, &cursor) ||
            !vt_unserialize_vkWaitSemaphores(
                    (VkDevice)&decodedDeviceToken, &request->waitInfo,
                    &decodedTimeout, &cursor, &request->memoryPool) ||
            !vt_decode_finished(&cursor)) {
        return false;
    }
    return decodedDeviceToken == request->deviceToken &&
            decodedTimeout == request->timeout &&
            request->waitInfo.sType == VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO &&
            request->waitInfo.semaphoreCount > 0 &&
            request->waitInfo.pSemaphores && request->waitInfo.pValues;
}

bool TimelineSemaphore_asyncWait(
        VkContext* context,
        VtRequestDecode* decode,
        uint64_t deviceToken,
        VkDevice device,
        uint64_t timeout) {
    if (!context || !decode || VkContext_isClosing(context)) return false;

    WaitSemaphoresRequest* request = calloc(1, sizeof(*request));
    if (!request) return false;
    request->context = context;
    request->device = device;
    request->deviceToken = deviceToken;
    request->timeout = timeout;
    request->notifyFd = -1;

    if (!VkContext_acquireDeviceLease(
            context, deviceToken, &request->deviceLease) ||
            request->deviceLease.hostDeviceBits !=
                    (uint64_t)(uintptr_t)device ||
            !decodeOwnedWait(request, decode)) {
        releaseWaitRequest(request);
        return false;
    }

    request->notifyFd = eventfd(0, EFD_CLOEXEC);
    if (request->notifyFd < 0) {
        releaseWaitRequest(request);
        return false;
    }
    if (send_fds(context->clientFd, &request->notifyFd, 1, NULL, 0) != 1) {
        releaseWaitRequest(request);
        VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
        return false;
    }

    /* Rejection invokes cancelWaitRequest synchronously; accepted work owns
     * its immutable decoded arena, eventfd, and device lease. */
    (void)ThreadPool_runWithCleanup(context->threadPool,
            waitSemaphoresThread, request, cancelWaitRequest);
    return true;
}
