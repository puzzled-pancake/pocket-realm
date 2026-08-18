#ifndef VORTEK_REQUEST_HANDLER_H
#define VORTEK_REQUEST_HANDLER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

typedef enum VtRequestBatchStep {
    VT_REQUEST_BATCH_ERROR = -1,
    VT_REQUEST_BATCH_DONE = 0,
    VT_REQUEST_BATCH_CHUNK = 1,
} VtRequestBatchStep;

typedef struct VtRequestBatchChunk {
    int32_t requestCode;
    const uint8_t* data;
    size_t size;
} VtRequestBatchChunk;

static inline VtRequestBatchStep vt_request_batch_next(
        const uint8_t* data,
        size_t size,
        size_t* position,
        VtRequestBatchChunk* chunk) {
    const size_t headerSize = 8;
    if (!data || !position || !chunk || *position > size) return VT_REQUEST_BATCH_ERROR;
    if (*position == size) return VT_REQUEST_BATCH_DONE;
    if (size - *position < headerSize) return VT_REQUEST_BATCH_ERROR;
    int32_t payloadSize = 0;
    memcpy(&chunk->requestCode, data + *position, sizeof(chunk->requestCode));
    memcpy(&payloadSize, data + *position + sizeof(chunk->requestCode), sizeof(payloadSize));
    if (payloadSize < 0 || (size_t)payloadSize > size - *position - headerSize)
        return VT_REQUEST_BATCH_ERROR;
    chunk->data = data + *position + headerSize;
    chunk->size = (size_t)payloadSize;
    *position += headerSize + chunk->size;
    return VT_REQUEST_BATCH_CHUNK;
}

static inline uint32_t vt_request_query_copy_count_inline(
        uint32_t guestCapacity, uint32_t serverActual) {
    return guestCapacity < serverActual ? guestCapacity : serverActual;
}

/* Output arrays that carry guest-provided pNext state need room for every
 * guest element decoded and every element the host may write.  Count-only
 * queries deliberately allocate nothing. */
static inline uint32_t vt_request_query_storage_count_inline(
        uint32_t guestCapacity, uint32_t serverActual) {
    if (guestCapacity == 0) return 0;
    return guestCapacity > serverActual ? guestCapacity : serverActual;
}

#ifndef VORTEK_REQUEST_HANDLER_CONTRACT_ONLY
#ifndef VORTEK_REQUEST_HANDLE_AUTHORITY_COMPLETE
#define VORTEK_REQUEST_HANDLE_AUTHORITY_COMPLETE 0
#endif

#include "vortek_serializer.h"
#include "vk_context.h"

typedef void (*HandleRequestFunc)(VkContext* context);

typedef enum VtRequestPublicationCleanup {
    VT_REQUEST_PUBLICATION_NONE = 0,
    VT_REQUEST_PUBLICATION_SINGLE,
    VT_REQUEST_PUBLICATION_DESCRIPTOR_SETS,
    VT_REQUEST_PUBLICATION_COMMAND_BUFFERS,
} VtRequestPublicationCleanup;

typedef struct VtRequestPublication {
    VtRequestPublicationCleanup cleanup;
    VkObjectType objectType;
    VortekHandleRole role;
    VortekHandleOwner owner;
    uint64_t hostDeviceValue;
    uint64_t hostParentValue;
    uint64_t hostValue;
    uint64_t wireToken;
    uint64_t* hostValues;
    uint64_t* wireTokens;
    size_t count;
    bool active;
} VtRequestPublication;

/* One decode budget is shared by every exact pass over a request. */
struct VtRequestDecode {
    VtDecodeState ownedState;
    VtDecodeState* state;
    VkContext* context;
    const uint8_t* data;
    size_t size;
    MemoryPool* memoryPool;
    VtRequestPublication publication;
};

extern bool vt_request_decode_begin(VtRequestDecode* request, VkContext* context);
extern bool vt_request_decode_pass_begin(VtRequestDecode* request, VtDecodeCursor* cursor);
extern void* vt_request_decode_alloc(
        VtRequestDecode* request, size_t count, size_t elementSize);
extern void* vt_request_output_alloc(VtRequestDecode* request, size_t size);
extern bool vt_request_send_response(
        VtRequestDecode* request, int requestCode,
        const void* data, int size);
extern void vt_request_response_abort(
        VtRequestDecode* request, VtDecodeError error);
extern bool vt_request_seed_handle_scope(
        VtRequestDecode* request, size_t tokenOffset,
        VkObjectType objectType, VortekHandleRole role);
extern bool vt_request_resolve_root_handle(
        VtRequestDecode* request, uint64_t wireToken,
        VkObjectType objectType, VortekHandleRole role,
        bool requireInstanceOwner, bool requireDeviceOwner,
        bool allowNull, uint64_t* hostValue);
extern bool vt_request_retire_root_handle(
        VtRequestDecode* request, uint64_t wireToken,
        VkObjectType objectType, VortekHandleRole role,
        bool requireInstanceOwner, bool requireDeviceOwner,
        bool allowNull, uint64_t* hostValue);
extern bool vt_request_publish_handle(
        VtRequestDecode* request, VkObjectType objectType,
        VortekHandleRole role, uint64_t hostValue,
        uint64_t instanceOwner, uint64_t deviceOwner,
        uint64_t hostDeviceValue,
        uint64_t* wireToken);
extern void vt_request_rollback_output(
        VtRequestDecode* request, VkObjectType objectType,
        VortekHandleRole role, uint64_t hostValue, uint64_t hostDeviceValue);
extern bool vt_request_publish_vulkan_batch(
        VtRequestDecode* request, VkObjectType objectType,
        void* hostHandlesInOut, size_t handleSize, size_t count,
        uint64_t instanceOwner, uint64_t deviceOwner, uint64_t parentOwner,
        VtRequestPublicationCleanup cleanup,
        uint64_t hostDeviceValue, uint64_t hostParentValue);
extern bool vt_request_capture_begin(
        VtRequestDecode* request, VkObjectType objectType,
        uint64_t* wireTokens, size_t capacity);
extern bool vt_request_capture_complete(
        VtRequestDecode* request, size_t expectedCount);
extern bool vt_request_tombstone_batch(
        VtRequestDecode* request, const uint64_t* wireTokens, size_t count,
        VkObjectType objectType, uint64_t parentOwner);
extern bool vt_request_validate_batch(
        VtRequestDecode* request, const uint64_t* wireTokens, size_t count,
        VkObjectType objectType, uint64_t parentOwner);
extern bool vt_request_tombstone_children(
        VtRequestDecode* request, uint64_t parentOwner);
extern void vt_request_protocol_error(VkContext* context, VtDecodeError error);
extern uint32_t vt_request_query_copy_count(uint32_t guestCapacity, uint32_t serverActual);
extern VkResult vt_request_query_result(
        VkResult serverResult, bool guestArray,
        uint32_t guestCapacity, uint32_t serverActual);

#define VT_REQUEST_BEGIN(context_) \
    VtRequestDecode _vt_request; \
    if (!vt_request_decode_begin(&_vt_request, (context_))) { \
        vt_request_protocol_error((context_), _vt_request.state \
                ? vt_decode_error(&(VtDecodeCursor){.state = _vt_request.state}) \
                : VT_DECODE_ERROR_ARGUMENT); \
        return; \
    }

#define VT_REQUEST_DECODE(call_) \
    do { \
        VtDecodeCursor _vt_cursor; \
        if (!vt_request_decode_pass_begin(&_vt_request, &_vt_cursor) || \
                !(call_) || !vt_decode_finished(&_vt_cursor)) { \
            vt_request_protocol_error(context, vt_decode_error(&_vt_cursor)); \
            return; \
        } \
    } while (0)

#define VT_REQUEST_ARRAY(type_, name_, count_) \
    size_t _vt_count_##name_ = (size_t)(count_); \
    type_* name_ = _vt_count_##name_ == 0 ? NULL : \
            vt_request_decode_alloc(&_vt_request, _vt_count_##name_, sizeof(type_)); \
    if (_vt_count_##name_ != 0 && !(name_)) { \
        vt_request_protocol_error(context, \
                vt_decode_error(&(VtDecodeCursor){.state = _vt_request.state})); \
        return; \
    }

#define VT_REQUEST_BYTES(type_, name_, count_) \
    VT_REQUEST_ARRAY(type_, name_, count_)

#define VT_REQUEST_SCOPE_AT(offset_, object_type_, role_) \
    do { \
        if (!vt_request_seed_handle_scope(&_vt_request, (offset_), \
                (object_type_), (role_))) { \
            vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED); \
            return; \
        } \
    } while (0)

/* A standalone VkFoo decoder starts with its eight-byte token.  A generated
 * vkFoo command decoder prefixes its first handle with one presence byte. */
#define VT_REQUEST_SCOPE_SIMPLE(object_type_, role_) \
    VT_REQUEST_SCOPE_AT(0u, (object_type_), (role_))
#define VT_REQUEST_SCOPE_COMMAND(object_type_, role_) \
    VT_REQUEST_SCOPE_AT(1u, (object_type_), (role_))

#define VT_REQUEST_HANDLE(type_, name_, token_, object_type_, role_, \
        require_instance_, require_device_, allow_null_) \
    uint64_t _vt_host_##name_ = 0; \
    if (!vt_request_resolve_root_handle(&_vt_request, (uint64_t)(token_), \
            (object_type_), (role_), (require_instance_), (require_device_), \
            (allow_null_), &_vt_host_##name_)) { \
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED); \
        return; \
    } \
    type_ name_ = (type_)(uintptr_t)_vt_host_##name_

#define VT_REQUEST_RETIRED_HANDLE(type_, name_, token_, object_type_, role_, \
        require_instance_, require_device_, allow_null_) \
    uint64_t _vt_host_##name_ = 0; \
    if (!vt_request_retire_root_handle(&_vt_request, (uint64_t)(token_), \
            (object_type_), (role_), (require_instance_), (require_device_), \
            (allow_null_), &_vt_host_##name_)) { \
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED); \
        return; \
    } \
    type_ name_ = (type_)(uintptr_t)_vt_host_##name_

#define VT_REQUEST_PUBLISH(object_type_, role_, host_, instance_, device_, \
        host_device_, token_) \
    do { \
        if (!vt_request_publish_handle(&_vt_request, (object_type_), (role_), \
                (uint64_t)(uintptr_t)(host_), (uint64_t)(instance_), \
                (uint64_t)(device_), \
                (uint64_t)(uintptr_t)(host_device_), &(token_))) { \
            vt_request_rollback_output(&_vt_request, (object_type_), (role_), \
                    (uint64_t)(uintptr_t)(host_), \
                    (uint64_t)(uintptr_t)(host_device_)); \
            vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED); \
            return; \
        } \
    } while (0)

#define VT_REQUEST_SEND(request_code_, data_, size_) \
    do { \
        if (!vt_request_send_response(&_vt_request, (int)(request_code_), \
                (const void*)(data_), (int)(size_))) return; \
    } while (0)

/* Vulkan creation failures must never publish an undefined driver output. */
#define VT_REQUEST_PUBLISH_RESULT(result_, object_type_, role_, host_, \
        instance_, device_, host_device_, token_) \
    do { \
        if ((result_) == VK_SUCCESS) { \
            VT_REQUEST_PUBLISH((object_type_), (role_), (host_), (instance_), \
                    (device_), (host_device_), (token_)); \
        } else { \
            /* Discard pending authority metadata and serialize null. */ \
            vt_request_rollback_output(&_vt_request, (object_type_), (role_), \
                    0, \
                    (uint64_t)(uintptr_t)(host_device_)); \
            (token_) = 0; \
        } \
    } while (0)

#define VT_REQUEST_AUTHORITY(call_) \
    do { \
        if (!(call_)) { \
            vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED); \
            return; \
        } \
    } while (0)

extern void vt_handle_vkCreateInstance(VkContext* context);
extern void vt_handle_vkDestroyInstance(VkContext* context);
extern void vt_handle_vkEnumeratePhysicalDevices(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceProperties(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceQueueFamilyProperties(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceMemoryProperties(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceFeatures(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceFormatProperties(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceImageFormatProperties(VkContext* context);
extern void vt_handle_vkCreateDevice(VkContext* context);
extern void vt_handle_vkDestroyDevice(VkContext* context);
extern void vt_handle_vkEnumerateInstanceVersion(VkContext* context);
extern void vt_handle_vkEnumerateInstanceExtensionProperties(VkContext* context);
extern void vt_handle_vkEnumerateDeviceExtensionProperties(VkContext* context);
extern void vt_handle_vkGetDeviceQueue(VkContext* context);
extern void vt_handle_vkQueueSubmit(VkContext* context);
extern void vt_handle_vkQueueWaitIdle(VkContext* context);
extern void vt_handle_vkDeviceWaitIdle(VkContext* context);
extern void vt_handle_vkAllocateMemory(VkContext* context);
extern void vt_handle_vkFreeMemory(VkContext* context);
extern void vt_handle_vkMapMemory(VkContext* context);
extern void vt_handle_vkUnmapMemory(VkContext* context);
extern void vt_handle_vkFlushMappedMemoryRanges(VkContext* context);
extern void vt_handle_vkInvalidateMappedMemoryRanges(VkContext* context);
extern void vt_handle_vkGetDeviceMemoryCommitment(VkContext* context);
extern void vt_handle_vkGetBufferMemoryRequirements(VkContext* context);
extern void vt_handle_vkBindBufferMemory(VkContext* context);
extern void vt_handle_vkGetImageMemoryRequirements(VkContext* context);
extern void vt_handle_vkBindImageMemory(VkContext* context);
extern void vt_handle_vkGetImageSparseMemoryRequirements(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceSparseImageFormatProperties(VkContext* context);
extern void vt_handle_vkQueueBindSparse(VkContext* context);
extern void vt_handle_vkCreateFence(VkContext* context);
extern void vt_handle_vkDestroyFence(VkContext* context);
extern void vt_handle_vkResetFences(VkContext* context);
extern void vt_handle_vkGetFenceStatus(VkContext* context);
extern void vt_handle_vkWaitForFences(VkContext* context);
extern void vt_handle_vkCreateSemaphore(VkContext* context);
extern void vt_handle_vkDestroySemaphore(VkContext* context);
extern void vt_handle_vkCreateEvent(VkContext* context);
extern void vt_handle_vkDestroyEvent(VkContext* context);
extern void vt_handle_vkGetEventStatus(VkContext* context);
extern void vt_handle_vkSetEvent(VkContext* context);
extern void vt_handle_vkResetEvent(VkContext* context);
extern void vt_handle_vkCreateQueryPool(VkContext* context);
extern void vt_handle_vkDestroyQueryPool(VkContext* context);
extern void vt_handle_vkGetQueryPoolResults(VkContext* context);
extern void vt_handle_vkResetQueryPool(VkContext* context);
extern void vt_handle_vkCreateBuffer(VkContext* context);
extern void vt_handle_vkDestroyBuffer(VkContext* context);
extern void vt_handle_vkCreateBufferView(VkContext* context);
extern void vt_handle_vkDestroyBufferView(VkContext* context);
extern void vt_handle_vkCreateImage(VkContext* context);
extern void vt_handle_vkDestroyImage(VkContext* context);
extern void vt_handle_vkGetImageSubresourceLayout(VkContext* context);
extern void vt_handle_vkCreateImageView(VkContext* context);
extern void vt_handle_vkDestroyImageView(VkContext* context);
extern void vt_handle_vkCreateShaderModule(VkContext* context);
extern void vt_handle_vkDestroyShaderModule(VkContext* context);
extern void vt_handle_vkCreatePipelineCache(VkContext* context);
extern void vt_handle_vkDestroyPipelineCache(VkContext* context);
extern void vt_handle_vkGetPipelineCacheData(VkContext* context);
extern void vt_handle_vkMergePipelineCaches(VkContext* context);
extern void vt_handle_vkCreateGraphicsPipelines(VkContext* context);
extern void vt_handle_vkCreateComputePipelines(VkContext* context);
extern void vt_handle_vkDestroyPipeline(VkContext* context);
extern void vt_handle_vkCreatePipelineLayout(VkContext* context);
extern void vt_handle_vkDestroyPipelineLayout(VkContext* context);
extern void vt_handle_vkCreateSampler(VkContext* context);
extern void vt_handle_vkDestroySampler(VkContext* context);
extern void vt_handle_vkCreateDescriptorSetLayout(VkContext* context);
extern void vt_handle_vkDestroyDescriptorSetLayout(VkContext* context);
extern void vt_handle_vkCreateDescriptorPool(VkContext* context);
extern void vt_handle_vkDestroyDescriptorPool(VkContext* context);
extern void vt_handle_vkResetDescriptorPool(VkContext* context);
extern void vt_handle_vkAllocateDescriptorSets(VkContext* context);
extern void vt_handle_vkFreeDescriptorSets(VkContext* context);
extern void vt_handle_vkUpdateDescriptorSets(VkContext* context);
extern void vt_handle_vkCreateFramebuffer(VkContext* context);
extern void vt_handle_vkDestroyFramebuffer(VkContext* context);
extern void vt_handle_vkCreateRenderPass(VkContext* context);
extern void vt_handle_vkDestroyRenderPass(VkContext* context);
extern void vt_handle_vkGetRenderAreaGranularity(VkContext* context);
extern void vt_handle_vkCreateCommandPool(VkContext* context);
extern void vt_handle_vkDestroyCommandPool(VkContext* context);
extern void vt_handle_vkResetCommandPool(VkContext* context);
extern void vt_handle_vkAllocateCommandBuffers(VkContext* context);
extern void vt_handle_vkFreeCommandBuffers(VkContext* context);
extern void vt_handle_vkBeginCommandBuffer(VkContext* context);
extern void vt_handle_vkEndCommandBuffer(VkContext* context);
extern void vt_handle_vkResetCommandBuffer(VkContext* context);
extern void vt_handle_vkCmdBindPipeline(VkContext* context);
extern void vt_handle_vkCmdSetViewport(VkContext* context);
extern void vt_handle_vkCmdSetScissor(VkContext* context);
extern void vt_handle_vkCmdSetLineWidth(VkContext* context);
extern void vt_handle_vkCmdSetDepthBias(VkContext* context);
extern void vt_handle_vkCmdSetBlendConstants(VkContext* context);
extern void vt_handle_vkCmdSetDepthBounds(VkContext* context);
extern void vt_handle_vkCmdSetStencilCompareMask(VkContext* context);
extern void vt_handle_vkCmdSetStencilWriteMask(VkContext* context);
extern void vt_handle_vkCmdSetStencilReference(VkContext* context);
extern void vt_handle_vkCmdBindDescriptorSets(VkContext* context);
extern void vt_handle_vkCmdBindIndexBuffer(VkContext* context);
extern void vt_handle_vkCmdBindVertexBuffers(VkContext* context);
extern void vt_handle_vkCmdDraw(VkContext* context);
extern void vt_handle_vkCmdDrawIndexed(VkContext* context);
extern void vt_handle_vkCmdDrawIndirect(VkContext* context);
extern void vt_handle_vkCmdDrawIndexedIndirect(VkContext* context);
extern void vt_handle_vkCmdDispatch(VkContext* context);
extern void vt_handle_vkCmdDispatchIndirect(VkContext* context);
extern void vt_handle_vkCmdCopyBuffer(VkContext* context);
extern void vt_handle_vkCmdCopyImage(VkContext* context);
extern void vt_handle_vkCmdBlitImage(VkContext* context);
extern void vt_handle_vkCmdCopyBufferToImage(VkContext* context);
extern void vt_handle_vkCmdCopyImageToBuffer(VkContext* context);
extern void vt_handle_vkCmdUpdateBuffer(VkContext* context);
extern void vt_handle_vkCmdFillBuffer(VkContext* context);
extern void vt_handle_vkCmdClearColorImage(VkContext* context);
extern void vt_handle_vkCmdClearDepthStencilImage(VkContext* context);
extern void vt_handle_vkCmdClearAttachments(VkContext* context);
extern void vt_handle_vkCmdResolveImage(VkContext* context);
extern void vt_handle_vkCmdSetEvent(VkContext* context);
extern void vt_handle_vkCmdResetEvent(VkContext* context);
extern void vt_handle_vkCmdWaitEvents(VkContext* context);
extern void vt_handle_vkCmdPipelineBarrier(VkContext* context);
extern void vt_handle_vkCmdBeginQuery(VkContext* context);
extern void vt_handle_vkCmdEndQuery(VkContext* context);
extern void vt_handle_vkCmdBeginConditionalRenderingEXT(VkContext* context);
extern void vt_handle_vkCmdEndConditionalRenderingEXT(VkContext* context);
extern void vt_handle_vkCmdResetQueryPool(VkContext* context);
extern void vt_handle_vkCmdWriteTimestamp(VkContext* context);
extern void vt_handle_vkCmdCopyQueryPoolResults(VkContext* context);
extern void vt_handle_vkCmdPushConstants(VkContext* context);
extern void vt_handle_vkCmdBeginRenderPass(VkContext* context);
extern void vt_handle_vkCmdNextSubpass(VkContext* context);
extern void vt_handle_vkCmdEndRenderPass(VkContext* context);
extern void vt_handle_vkCmdExecuteCommands(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceSurfaceCapabilitiesKHR(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceSurfaceFormatsKHR(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceSurfacePresentModesKHR(VkContext* context);
extern void vt_handle_vkCreateSwapchainKHR(VkContext* context);
extern void vt_handle_vkDestroySwapchainKHR(VkContext* context);
extern void vt_handle_vkGetSwapchainImagesKHR(VkContext* context);
extern void vt_handle_vkAcquireNextImageKHR(VkContext* context);
extern void vt_handle_vkQueuePresentKHR(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceFeatures2(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceProperties2(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceFormatProperties2(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceImageFormatProperties2(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceQueueFamilyProperties2(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceMemoryProperties2(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceSparseImageFormatProperties2(VkContext* context);
extern void vt_handle_vkCmdPushDescriptorSetKHR(VkContext* context);
extern void vt_handle_vkTrimCommandPool(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceExternalBufferProperties(VkContext* context);
extern void vt_handle_vkGetMemoryFdKHR(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceExternalSemaphoreProperties(VkContext* context);
extern void vt_handle_vkGetSemaphoreFdKHR(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceExternalFenceProperties(VkContext* context);
extern void vt_handle_vkGetFenceFdKHR(VkContext* context);
extern void vt_handle_vkEnumeratePhysicalDeviceGroups(VkContext* context);
extern void vt_handle_vkGetDeviceGroupPeerMemoryFeatures(VkContext* context);
extern void vt_handle_vkBindBufferMemory2(VkContext* context);
extern void vt_handle_vkBindImageMemory2(VkContext* context);
extern void vt_handle_vkCmdSetDeviceMask(VkContext* context);
extern void vt_handle_vkAcquireNextImage2KHR(VkContext* context);
extern void vt_handle_vkCmdDispatchBase(VkContext* context);
extern void vt_handle_vkGetPhysicalDevicePresentRectanglesKHR(VkContext* context);
extern void vt_handle_vkCmdSetSampleLocationsEXT(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceMultisamplePropertiesEXT(VkContext* context);
extern void vt_handle_vkGetBufferMemoryRequirements2(VkContext* context);
extern void vt_handle_vkGetImageMemoryRequirements2(VkContext* context);
extern void vt_handle_vkGetImageSparseMemoryRequirements2(VkContext* context);
extern void vt_handle_vkGetDeviceBufferMemoryRequirements(VkContext* context);
extern void vt_handle_vkGetDeviceImageMemoryRequirements(VkContext* context);
extern void vt_handle_vkGetDeviceImageSparseMemoryRequirements(VkContext* context);
extern void vt_handle_vkCreateSamplerYcbcrConversion(VkContext* context);
extern void vt_handle_vkDestroySamplerYcbcrConversion(VkContext* context);
extern void vt_handle_vkGetDeviceQueue2(VkContext* context);
extern void vt_handle_vkGetDescriptorSetLayoutSupport(VkContext* context);
extern void vt_handle_vkGetPhysicalDeviceCalibrateableTimeDomainsKHR(VkContext* context);
extern void vt_handle_vkGetCalibratedTimestampsKHR(VkContext* context);
extern void vt_handle_vkCreateRenderPass2(VkContext* context);
extern void vt_handle_vkCmdBeginRenderPass2(VkContext* context);
extern void vt_handle_vkCmdNextSubpass2(VkContext* context);
extern void vt_handle_vkCmdEndRenderPass2(VkContext* context);
extern void vt_handle_vkGetSemaphoreCounterValue(VkContext* context);
extern void vt_handle_vkWaitSemaphores(VkContext* context);
extern void vt_handle_vkSignalSemaphore(VkContext* context);
extern void vt_handle_vkCmdDrawIndirectCount(VkContext* context);
extern void vt_handle_vkCmdDrawIndexedIndirectCount(VkContext* context);
extern void vt_handle_vkCmdBindTransformFeedbackBuffersEXT(VkContext* context);
extern void vt_handle_vkCmdBeginTransformFeedbackEXT(VkContext* context);
extern void vt_handle_vkCmdEndTransformFeedbackEXT(VkContext* context);
extern void vt_handle_vkCmdBeginQueryIndexedEXT(VkContext* context);
extern void vt_handle_vkCmdEndQueryIndexedEXT(VkContext* context);
extern void vt_handle_vkCmdDrawIndirectByteCountEXT(VkContext* context);
extern void vt_handle_vkGetBufferOpaqueCaptureAddress(VkContext* context);
extern void vt_handle_vkGetBufferDeviceAddress(VkContext* context);
extern void vt_handle_vkGetDeviceMemoryOpaqueCaptureAddress(VkContext* context);
extern void vt_handle_vkCmdSetLineStippleKHR(VkContext* context);
extern void vt_handle_vkCmdSetCullMode(VkContext* context);
extern void vt_handle_vkCmdSetFrontFace(VkContext* context);
extern void vt_handle_vkCmdSetPrimitiveTopology(VkContext* context);
extern void vt_handle_vkCmdSetViewportWithCount(VkContext* context);
extern void vt_handle_vkCmdSetScissorWithCount(VkContext* context);
extern void vt_handle_vkCmdBindVertexBuffers2(VkContext* context);
extern void vt_handle_vkCmdSetDepthTestEnable(VkContext* context);
extern void vt_handle_vkCmdSetDepthWriteEnable(VkContext* context);
extern void vt_handle_vkCmdSetDepthCompareOp(VkContext* context);
extern void vt_handle_vkCmdSetDepthBoundsTestEnable(VkContext* context);
extern void vt_handle_vkCmdSetStencilTestEnable(VkContext* context);
extern void vt_handle_vkCmdSetStencilOp(VkContext* context);
extern void vt_handle_vkCmdSetRasterizerDiscardEnable(VkContext* context);
extern void vt_handle_vkCmdSetDepthBiasEnable(VkContext* context);
extern void vt_handle_vkCmdSetPrimitiveRestartEnable(VkContext* context);
extern void vt_handle_vkCmdSetTessellationDomainOriginEXT(VkContext* context);
extern void vt_handle_vkCmdSetDepthClampEnableEXT(VkContext* context);
extern void vt_handle_vkCmdSetPolygonModeEXT(VkContext* context);
extern void vt_handle_vkCmdSetRasterizationSamplesEXT(VkContext* context);
extern void vt_handle_vkCmdSetSampleMaskEXT(VkContext* context);
extern void vt_handle_vkCmdSetAlphaToCoverageEnableEXT(VkContext* context);
extern void vt_handle_vkCmdSetAlphaToOneEnableEXT(VkContext* context);
extern void vt_handle_vkCmdSetLogicOpEnableEXT(VkContext* context);
extern void vt_handle_vkCmdSetColorBlendEnableEXT(VkContext* context);
extern void vt_handle_vkCmdSetColorBlendEquationEXT(VkContext* context);
extern void vt_handle_vkCmdSetColorWriteMaskEXT(VkContext* context);
extern void vt_handle_vkCmdSetRasterizationStreamEXT(VkContext* context);
extern void vt_handle_vkCmdSetConservativeRasterizationModeEXT(VkContext* context);
extern void vt_handle_vkCmdSetExtraPrimitiveOverestimationSizeEXT(VkContext* context);
extern void vt_handle_vkCmdSetDepthClipEnableEXT(VkContext* context);
extern void vt_handle_vkCmdSetSampleLocationsEnableEXT(VkContext* context);
extern void vt_handle_vkCmdSetColorBlendAdvancedEXT(VkContext* context);
extern void vt_handle_vkCmdSetProvokingVertexModeEXT(VkContext* context);
extern void vt_handle_vkCmdSetLineRasterizationModeEXT(VkContext* context);
extern void vt_handle_vkCmdSetLineStippleEnableEXT(VkContext* context);
extern void vt_handle_vkCmdSetDepthClipNegativeOneToOneEXT(VkContext* context);
extern void vt_handle_vkCmdCopyBuffer2(VkContext* context);
extern void vt_handle_vkCmdCopyImage2(VkContext* context);
extern void vt_handle_vkCmdBlitImage2(VkContext* context);
extern void vt_handle_vkCmdCopyBufferToImage2(VkContext* context);
extern void vt_handle_vkCmdCopyImageToBuffer2(VkContext* context);
extern void vt_handle_vkCmdResolveImage2(VkContext* context);
extern void vt_handle_vkCmdSetColorWriteEnableEXT(VkContext* context);
extern void vt_handle_vkCmdSetEvent2(VkContext* context);
extern void vt_handle_vkCmdResetEvent2(VkContext* context);
extern void vt_handle_vkCmdWaitEvents2(VkContext* context);
extern void vt_handle_vkCmdPipelineBarrier2(VkContext* context);
extern void vt_handle_vkQueueSubmit2(VkContext* context);
extern void vt_handle_vkCmdWriteTimestamp2(VkContext* context);
extern void vt_handle_vkCmdBeginRendering(VkContext* context);
extern void vt_handle_vkCmdEndRendering(VkContext* context);
extern void vt_handle_vkGetShaderModuleIdentifierEXT(VkContext* context);
extern void vt_handle_vkGetShaderModuleCreateInfoIdentifierEXT(VkContext* context);

extern HandleRequestFunc getHandleRequestFunc(short requestCode);

#endif /* VORTEK_REQUEST_HANDLER_CONTRACT_ONLY */

#endif
