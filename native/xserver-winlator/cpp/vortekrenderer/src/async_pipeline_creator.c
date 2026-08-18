#include "async_pipeline_creator.h"

#include "request_handler.h"
#include "vk_context.h"
#include "vulkan_helper.h"

#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

typedef struct PipelineCreateRequest {
    VkContext* context;
    PipelineType type;
    VkDevice device;
    VkPipelineCache pipelineCache;
    uint64_t instanceToken;
    uint64_t deviceToken;
    uint64_t pipelineCacheToken;
    uint32_t pipelineCount;
    void* pipelineInfos;
    MemoryPool memoryPool;
    VortekDeviceLease deviceLease;
    int notifyFd;
} PipelineCreateRequest;

static bool writeSocketExact(int fd, const void* data, size_t size) {
    const uint8_t* current = data;
    size_t remaining = size;
    while (remaining > 0) {
        ssize_t written = send(fd, current, remaining, MSG_NOSIGNAL);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        current += (size_t)written;
        remaining -= (size_t)written;
    }
    return true;
}

static bool sendSetupByte(VkContext* context, char success) {
    if (!context) return false;
    ssize_t result;
    do {
        result = send(context->clientFd, &success, sizeof(success), MSG_NOSIGNAL);
    } while (result < 0 && errno == EINTR);
    return result == (ssize_t)sizeof(success);
}

static void freeOwnedPool(MemoryPool* memoryPool) {
    if (!memoryPool) return;
    vt_free(memoryPool);
    ArrayList_free(&memoryPool->allocationList, false);
    free(memoryPool->data);
    memset(memoryPool, 0, sizeof(*memoryPool));
}

static void releasePipelineRequest(PipelineCreateRequest* request) {
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

static bool writePipelineResponse(
        PipelineCreateRequest* request,
        VkResult result,
        const uint64_t* wireTokens) {
    if (!request || request->notifyFd < 0) return false;
    const size_t size = sizeof(result) +
            (size_t)request->pipelineCount * sizeof(uint64_t);
    uint8_t* output = calloc(1, size);
    if (!output) return false;
    memcpy(output, &result, sizeof(result));
    if (wireTokens && request->pipelineCount != 0) {
        memcpy(output + sizeof(result), wireTokens,
                (size_t)request->pipelineCount * sizeof(*wireTokens));
    }
    const bool success = writeSocketExact(request->notifyFd, output, size);
    free(output);
    return success;
}

static void destroyPipelines(
        VkDevice device, VkPipeline* pipelines, uint32_t pipelineCount) {
    if (!pipelines) return;
    for (uint32_t index = 0; index < pipelineCount; ++index) {
        if (pipelines[index] != VK_NULL_HANDLE && vulkanWrapper.vkDestroyPipeline) {
            vulkanWrapper.vkDestroyPipeline(device, pipelines[index], NULL);
            pipelines[index] = VK_NULL_HANDLE;
        }
    }
}

static VkResult inspectPipelineShaders(PipelineCreateRequest* request) {
    if (!request || !request->context || !request->context->shaderInspector) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    if (request->type == PIPELINE_TYPE_GRAPHICS) {
        VkGraphicsPipelineCreateInfo* createInfos = request->pipelineInfos;
        for (uint32_t index = 0; index < request->pipelineCount; ++index) {
            VkResult result = ShaderInspector_inspectShaderStages(
                    request->context->shaderInspector, request->device,
                    (VkPipelineShaderStageCreateInfo*)createInfos[index].pStages,
                    createInfos[index].stageCount,
                    createInfos[index].pVertexInputState);
            if (result != VK_SUCCESS) return result;
        }
        return VK_SUCCESS;
    }
    if (request->type == PIPELINE_TYPE_COMPUTE) {
        VkComputePipelineCreateInfo* createInfos = request->pipelineInfos;
        for (uint32_t index = 0; index < request->pipelineCount; ++index) {
            VkResult result = ShaderInspector_inspectShaderStages(
                    request->context->shaderInspector, request->device,
                    &createInfos[index].stage, 1, NULL);
            if (result != VK_SUCCESS) return result;
        }
        return VK_SUCCESS;
    }
    return VK_ERROR_INITIALIZATION_FAILED;
}

static VkResult createHostPipelines(
        PipelineCreateRequest* request, VkPipeline* pipelines) {
    if (request->type == PIPELINE_TYPE_GRAPHICS &&
            vulkanWrapper.vkCreateGraphicsPipelines) {
        return vulkanWrapper.vkCreateGraphicsPipelines(
                request->device, request->pipelineCache,
                request->pipelineCount, request->pipelineInfos,
                NULL, pipelines);
    }
    if (request->type == PIPELINE_TYPE_COMPUTE &&
            vulkanWrapper.vkCreateComputePipelines) {
        return vulkanWrapper.vkCreateComputePipelines(
                request->device, request->pipelineCache,
                request->pipelineCount, request->pipelineInfos,
                NULL, pipelines);
    }
    return VK_ERROR_EXTENSION_NOT_PRESENT;
}

static void pipelineCreateThread(void* opaque) {
    PipelineCreateRequest* request = opaque;
    VkPipeline* pipelines = NULL;
    uint64_t* hostBits = NULL;
    uint64_t* wireTokens = NULL;
    VkResult result = VK_ERROR_DEVICE_LOST;
    bool published = false;

    if (!request || !request->context) goto done;
    const size_t count = (size_t)request->pipelineCount;
    if (count > SIZE_MAX / sizeof(*pipelines) ||
            count > SIZE_MAX / sizeof(*hostBits) ||
            count > SIZE_MAX / sizeof(*wireTokens)) {
        result = VK_ERROR_OUT_OF_HOST_MEMORY;
        goto respond;
    }
    pipelines = calloc(count, sizeof(*pipelines));
    hostBits = calloc(count, sizeof(*hostBits));
    wireTokens = calloc(count, sizeof(*wireTokens));
    if (!pipelines || !hostBits || !wireTokens) {
        result = VK_ERROR_OUT_OF_HOST_MEMORY;
        goto respond;
    }

    pthread_mutex_lock(&request->context->pipelineMutex);
    if (VkContext_isClosing(request->context) ||
            VortekHandleRegistry_deviceLeaseShouldCancel(
                    &request->deviceLease) ||
            ThreadPool_isCancellationRequested(request->context->threadPool)) {
        result = VK_ERROR_DEVICE_LOST;
    }
    else {
        result = inspectPipelineShaders(request);
        if (result == VK_SUCCESS) result = createHostPipelines(request, pipelines);
    }

    if (result == VK_SUCCESS) {
        for (uint32_t index = 0; index < request->pipelineCount; ++index) {
            if (pipelines[index] == VK_NULL_HANDLE) {
                result = VK_ERROR_INITIALIZATION_FAILED;
                break;
            }
            hostBits[index] = (uint64_t)(uintptr_t)pipelines[index];
        }
    }

    if (result == VK_SUCCESS && !VkContext_isClosing(request->context) &&
            !VortekHandleRegistry_deviceLeaseShouldCancel(
                    &request->deviceLease)) {
        VortekHandleOwner owner = {
            .instance = request->instanceToken,
            .device = request->deviceToken,
            .parent = 0,
        };
        if (VkObjectAuthority_publishVulkanBatch(
                request->context->handleAuthority, VK_OBJECT_TYPE_PIPELINE,
                hostBits, count, owner, wireTokens) == VORTEK_HANDLE_OK) {
            published = true;
        }
        else {
            result = VK_ERROR_DEVICE_LOST;
            VkContext_requestStop(request->context, result);
        }
    }
    else if (result == VK_SUCCESS) result = VK_ERROR_DEVICE_LOST;

    if (!published) destroyPipelines(
            request->device, pipelines, request->pipelineCount);
    pthread_mutex_unlock(&request->context->pipelineMutex);

respond:
    if (!writePipelineResponse(request, result,
            published ? wireTokens : NULL)) {
        if (published) {
            VortekHandleExpectation expectation = {
                .contextGeneration = request->deviceLease.contextGeneration,
                .role = VORTEK_HANDLE_ROLE_VULKAN,
                .vulkanType = VK_OBJECT_TYPE_PIPELINE,
                .owner = {
                    .instance = request->instanceToken,
                    .device = request->deviceToken,
                    .parent = 0,
                },
                .requireInstanceOwner = true,
                .requireDeviceOwner = true,
                .allowNull = false,
            };
            const VortekHandleStatus rollbackStatus =
                    VkObjectAuthority_rollbackBatchWithLease(
                    request->context->handleAuthority, wireTokens, count,
                    &expectation, &request->deviceLease);
            /* If authority cannot be removed, leave the host objects owned by
             * their live entries.  Context reclamation will destroy them once
             * this lease is released; destroying them here would double-free. */
            if (rollbackStatus != VORTEK_HANDLE_OK) {
                VkContext_requestStop(
                        request->context, VK_ERROR_DEVICE_LOST);
            }
            else {
                destroyPipelines(request->device, pipelines,
                        request->pipelineCount);
                published = false;
            }
        }
        VkContext_requestStop(request->context, VK_ERROR_DEVICE_LOST);
    }

done:
    free(wireTokens);
    free(hostBits);
    free(pipelines);
    releasePipelineRequest(request);
}

static void cancelPipelineRequest(void* opaque) {
    PipelineCreateRequest* request = opaque;
    if (request && !writePipelineResponse(
            request, VK_ERROR_DEVICE_LOST, NULL) && request->context) {
        VkContext_requestStop(request->context, VK_ERROR_DEVICE_LOST);
    }
    releasePipelineRequest(request);
}

static bool decodeOwnedPipelineInfos(
        PipelineCreateRequest* request,
        VtRequestDecode* decode) {
    if (!request || !decode || request->pipelineCount == 0) return false;
    const size_t elementSize = request->type == PIPELINE_TYPE_GRAPHICS
            ? sizeof(VkGraphicsPipelineCreateInfo)
            : sizeof(VkComputePipelineCreateInfo);
    VtDecodeCursor cursor;
    if (!vt_request_decode_pass_begin(decode, &cursor)) return false;
    request->pipelineInfos = vt_decode_alloc(
            &cursor, &request->memoryPool,
            request->pipelineCount, elementSize);
    if (!request->pipelineInfos) return false;

    uint64_t decodedDeviceToken = 0;
    uint64_t decodedCacheToken = 0;
    uint32_t decodedCount = 0;
    bool success = false;
    if (request->type == PIPELINE_TYPE_GRAPHICS) {
        success = vt_unserialize_vkCreateGraphicsPipelines(
                (VkDevice)&decodedDeviceToken,
                (VkPipelineCache)&decodedCacheToken,
                &decodedCount, request->pipelineInfos, NULL, NULL,
                &cursor, &request->memoryPool);
    }
    else if (request->type == PIPELINE_TYPE_COMPUTE) {
        success = vt_unserialize_vkCreateComputePipelines(
                (VkDevice)&decodedDeviceToken,
                (VkPipelineCache)&decodedCacheToken,
                &decodedCount, request->pipelineInfos, NULL, NULL,
                &cursor, &request->memoryPool);
    }
    return success && vt_decode_finished(&cursor) &&
            decodedDeviceToken == request->deviceToken &&
            decodedCacheToken == request->pipelineCacheToken &&
            decodedCount == request->pipelineCount;
}

bool AsyncPipelineCreator_create(
        VkContext* context,
        PipelineType type,
        VtRequestDecode* decode,
        uint64_t deviceToken,
        uint64_t pipelineCacheToken,
        uint32_t pipelineCount,
        VkDevice device,
        VkPipelineCache pipelineCache) {
    if (!context || !decode || !decode->state || VkContext_isClosing(context) ||
            (type != PIPELINE_TYPE_GRAPHICS &&
             type != PIPELINE_TYPE_COMPUTE) ||
            pipelineCount == 0 || pipelineCount > VT_DECODE_MAX_ELEMENTS) {
        return false;
    }

    PipelineCreateRequest* request = calloc(1, sizeof(*request));
    if (!request) return sendSetupByte(context, 0);
    request->context = context;
    request->type = type;
    request->device = device;
    request->pipelineCache = pipelineCache;
    request->instanceToken = decode->state->instance_owner;
    request->deviceToken = deviceToken;
    request->pipelineCacheToken = pipelineCacheToken;
    request->pipelineCount = pipelineCount;
    request->notifyFd = -1;

    if (!VkContext_acquireDeviceLease(
            context, deviceToken, &request->deviceLease) ||
            request->deviceLease.hostDeviceBits !=
                    (uint64_t)(uintptr_t)device ||
            !decodeOwnedPipelineInfos(request, decode)) {
        releasePipelineRequest(request);
        return false;
    }

    int sockets[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets) != 0) {
        releasePipelineRequest(request);
        return sendSetupByte(context, 0);
    }
    request->notifyFd = sockets[1];
    const char success = 1;
    if (send_fds(context->clientFd, &sockets[0], 1,
            (void*)&success, sizeof(success)) != (int)sizeof(success)) {
        close(sockets[0]);
        close(sockets[1]);
        request->notifyFd = -1;
        releasePipelineRequest(request);
        VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
        return false;
    }
    close(sockets[0]);

    /* Rejection invokes cancelPipelineRequest synchronously; accepted jobs
     * own every decoded byte, the notification socket, and the device lease. */
    (void)ThreadPool_runWithCleanup(context->threadPool,
            pipelineCreateThread, request, cancelPipelineRequest);
    return true;
}
