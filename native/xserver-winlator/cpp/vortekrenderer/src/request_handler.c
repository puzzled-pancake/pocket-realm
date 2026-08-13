#include "request_handler.h"
#include "vk_context.h"
#include "vulkan_helper.h"
#include "sysvshared_memory.h"
#include <limits.h>
#include <stdint.h>

static _Thread_local VtRequestDecode* vt_active_batch_request;

static void vt_handle_authority_incomplete(VkContext* context) {
    /* The legacy bodies remain generated for compile-time migration, but no
     * guest token may reach them until every root input/output is authoritative. */
    VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
}

static bool vt_request_authority_lookup(
        VtRequestDecode* decode,
        uint64_t wireToken,
        VkObjectType objectType,
        VortekHandleRole role,
        bool requireInstanceOwner,
        bool requireDeviceOwner,
        bool allowNull,
        bool establishScope,
        VortekHandleValue* value) {
    if (!decode || !decode->state || !decode->context || !value) return false;
    VkContext* context = decode->context;
    if (!context->handleAuthority ||
            context->contextGeneration != decode->state->context_generation) return false;

    const bool missingInstance = requireInstanceOwner &&
            decode->state->instance_owner == 0;
    const bool missingDevice = requireDeviceOwner &&
            decode->state->device_owner == 0;
    VortekHandleExpectation expectation = {
        .contextGeneration = context->contextGeneration,
        .role = role,
        .vulkanType = objectType,
        .owner = {
            .instance = decode->state->instance_owner,
            .device = decode->state->device_owner,
        },
        .requireInstanceOwner = requireInstanceOwner && !missingInstance,
        .requireDeviceOwner = requireDeviceOwner && !missingDevice,
        .allowNull = allowNull,
    };
    VortekHandleStatus status = VkObjectAuthority_resolve(
            context->handleAuthority, wireToken, &expectation, value);
    if (status != VORTEK_HANDLE_OK) return false;
    if (wireToken == 0) return allowNull;

    if (establishScope) {
        uint64_t instanceOwner = decode->state->instance_owner;
        uint64_t deviceOwner = decode->state->device_owner;
        if (instanceOwner == 0) {
            instanceOwner = objectType == VK_OBJECT_TYPE_INSTANCE &&
                    role == VORTEK_HANDLE_ROLE_VULKAN
                    ? wireToken : value->owner.instance;
        }
        if (deviceOwner == 0) {
            deviceOwner = objectType == VK_OBJECT_TYPE_DEVICE &&
                    role == VORTEK_HANDLE_ROLE_VULKAN
                    ? wireToken : value->owner.device;
        }
        vt_decode_set_handle_scope(decode->state, instanceOwner, deviceOwner);
    }
    if (requireInstanceOwner && (decode->state->instance_owner == 0 ||
            value->owner.instance != decode->state->instance_owner)) return false;
    if (requireDeviceOwner && (decode->state->device_owner == 0 ||
            value->owner.device != decode->state->device_owner)) return false;
    if (decode->state->device_owner != 0) {
        VortekHandleExpectation deviceExpectation = {
            .contextGeneration = context->contextGeneration,
            .role = VORTEK_HANDLE_ROLE_VULKAN,
            .vulkanType = VK_OBJECT_TYPE_DEVICE,
            .owner = {.instance = decode->state->instance_owner},
            .requireInstanceOwner = decode->state->instance_owner != 0,
            .allowNull = false,
        };
        VortekHandleValue deviceValue = {0};
        if (VkObjectAuthority_resolve(context->handleAuthority,
                decode->state->device_owner, &deviceExpectation,
                &deviceValue) != VORTEK_HANDLE_OK) return false;
        vt_decode_set_null_descriptor_enabled(
                decode->state, deviceValue.nullDescriptorEnabled);
    }
    else vt_decode_set_null_descriptor_enabled(decode->state, false);
    return true;
}

static bool vt_request_resolve_handle(
        void* userdata,
        const VtDecodeHandleRequest* request,
        uint64_t* hostValue) {
    VtRequestDecode* decode = userdata;
    VkContext* context = decode ? decode->context : NULL;
    if (!decode || !context || !request || !hostValue ||
            request->context_generation != context->contextGeneration) return false;
    *hostValue = 0;

    if (request->role == VT_DECODE_HANDLE_ROLE_WINDOW_ID) {
        uint32_t windowId = 0;
        VortekHandleStatus status = VortekHandleRegistry_validateWindowId(
                context->handleAuthority, request->wire_token,
                request->context_generation, request->instance_owner,
                request->nullability != VT_DECODE_NULL_NEVER, &windowId);
        if (status != VORTEK_HANDLE_OK && status != VORTEK_HANDLE_NULL) return false;
        *hostValue = windowId;
        return true;
    }

    VortekHandleValue value = {0};
    VortekHandleRole authorityRole = VORTEK_HANDLE_ROLE_VULKAN;
    if (request->role == VT_DECODE_HANDLE_ROLE_RESOURCE_MEMORY_DEVICE_MEMORY)
        authorityRole = VORTEK_HANDLE_ROLE_RESOURCE_MEMORY;
    else if (request->role == VT_DECODE_HANDLE_ROLE_SHADER_MODULE_WRAPPER)
        authorityRole = VORTEK_HANDLE_ROLE_SHADER_MODULE;
    else if (request->role == VT_DECODE_HANDLE_ROLE_XWINDOW_SWAPCHAIN_WRAPPER)
        authorityRole = VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN;
    if (!vt_request_authority_lookup(decode, request->wire_token,
            (VkObjectType)request->object_type,
            authorityRole,
            (request->owner_requirements & VT_DECODE_OWNER_INSTANCE) != 0,
            (request->owner_requirements & VT_DECODE_OWNER_DEVICE) != 0,
            request->nullability != VT_DECODE_NULL_NEVER, true, &value)) return false;
    if (request->wire_token == 0) return true;
    if (request->role == VT_DECODE_HANDLE_ROLE_RESOURCE_MEMORY_DEVICE_MEMORY) {
        ResourceMemory* memory = (ResourceMemory*)(uintptr_t)value.hostValue;
        if (!memory) return false;
        *hostValue = (uint64_t)(uintptr_t)memory->memory;
    }
    else *hostValue = value.hostValue;
    return true;
}

bool vt_request_decode_begin(VtRequestDecode* request, VkContext* context) {
    if (!request || !context || context->inputBufferSize < 0 ||
            (!context->inputBuffer && context->inputBufferSize != 0)) return false;
    memset(request, 0, sizeof(*request));
    request->context = context;
    request->data = (const uint8_t*)context->inputBuffer;
    request->size = (size_t)context->inputBufferSize;
    request->memoryPool = &context->memoryPool;
    if (vt_active_batch_request) {
        const uint8_t* outerBegin = vt_active_batch_request->state->request_base;
        const uint8_t* outerEnd = vt_active_batch_request->state->request_end;
        if (request->data < outerBegin || request->data > outerEnd ||
                request->size > (size_t)(outerEnd - request->data)) return false;
        request->state = vt_active_batch_request->state;
        return true;
    }
    VtDecodeCursor initial;
    request->state = &request->ownedState;
    if (!vt_decode_cursor_init(&initial, request->state, request->data,
            request->size, request->memoryPool)) return false;
    vt_decode_set_handle_resolver(request->state, vt_request_resolve_handle,
            request, context->contextGeneration);
    vt_decode_set_null_descriptor_enabled(request->state, false);
    return true;
}

bool vt_request_decode_pass_begin(VtRequestDecode* request, VtDecodeCursor* cursor) {
    if (!request || !request->state || !cursor || request->state->error != VT_DECODE_ERROR_NONE)
        return false;
    memset(cursor, 0, sizeof(*cursor));
    cursor->base = request->data;
    cursor->ptr = request->data;
    cursor->end = request->data + request->size;
    cursor->request_end = request->state->request_end;
    cursor->state = request->state;
    cursor->expected_pnext_type = -1;
    return true;
}

void* vt_request_decode_alloc(VtRequestDecode* request, size_t count, size_t elementSize) {
    VtDecodeCursor cursor;
    if (!vt_request_decode_pass_begin(request, &cursor)) return NULL;
    return vt_decode_alloc(&cursor, request->memoryPool, count, elementSize);
}

void* vt_request_output_alloc(VtRequestDecode* request, size_t size) {
    if (size == 0) return NULL;
    if (!request || !request->state || !request->memoryPool ||
            !request->context || !request->context->clientRing ||
            !vt_transport_size_fits(size,
                    request->context->clientRing->bufferSize,
                    CLIENT_RING_BUFFER_SIZE - HEADER_SIZE) ||
            size > (size_t)INT_MAX) {
        if (request && request->state) {
            VtDecodeCursor cursor = {.state = request->state};
            (void)vt_decode_fail(&cursor, VT_DECODE_ERROR_LIMIT);
        }
        return NULL;
    }
    void* result = vt_alloc(request->memoryPool, (int)size);
    if (!result) {
        VtDecodeCursor cursor = {.state = request->state};
        (void)vt_decode_fail(&cursor, VT_DECODE_ERROR_OUT_OF_MEMORY);
    }
    return result;
}

static bool vt_request_track_single_publication(
        VtRequestDecode* request, VkObjectType objectType,
        VortekHandleRole role, VortekHandleOwner owner,
        uint64_t hostValue, uint64_t hostDeviceValue,
        uint64_t wireToken) {
    if (!request || request->publication.active || wireToken == 0 ||
            hostValue == 0) return false;
    request->publication = (VtRequestPublication) {
        .cleanup = VT_REQUEST_PUBLICATION_SINGLE,
        .objectType = objectType,
        .role = role,
        .owner = owner,
        .hostDeviceValue = hostDeviceValue,
        .hostValue = hostValue,
        .wireToken = wireToken,
        .active = true,
    };
    return true;
}

bool vt_request_seed_handle_scope(
        VtRequestDecode* request, size_t tokenOffset,
        VkObjectType objectType, VortekHandleRole role) {
    VtDecodeCursor cursor;
    uint64_t wireToken = 0;
    VortekHandleValue value = {0};
    if (!vt_request_decode_pass_begin(request, &cursor) ||
            !vt_decode_read_at(&cursor, tokenOffset, &wireToken, sizeof(wireToken))) {
        return false;
    }
    return vt_request_authority_lookup(request, wireToken, objectType, role,
            false, false, false, true, &value);
}

bool vt_request_resolve_root_handle(
        VtRequestDecode* request, uint64_t wireToken,
        VkObjectType objectType, VortekHandleRole role,
        bool requireInstanceOwner, bool requireDeviceOwner,
        bool allowNull, uint64_t* hostValue) {
    VortekHandleValue value = {0};
    if (!hostValue || !vt_request_authority_lookup(request, wireToken,
            objectType, role, requireInstanceOwner, requireDeviceOwner,
            allowNull, true, &value)) return false;
    *hostValue = value.hostValue;
    return true;
}

bool vt_request_retire_root_handle(
        VtRequestDecode* request, uint64_t wireToken,
        VkObjectType objectType, VortekHandleRole role,
        bool requireInstanceOwner, bool requireDeviceOwner,
        bool allowNull, uint64_t* hostValue) {
    if (!request || !request->state || !request->context || !hostValue) return false;
    *hostValue = 0;
    if (wireToken == 0) return allowNull;
    VortekHandleExpectation expectation = {
        .contextGeneration = request->context->contextGeneration,
        .role = role,
        .vulkanType = objectType,
        .owner = {
            .instance = request->state->instance_owner,
            .device = request->state->device_owner,
        },
        .requireInstanceOwner = requireInstanceOwner,
        .requireDeviceOwner = requireDeviceOwner,
        .allowNull = false,
    };
    VortekHandleValue value = {0};
    VortekHandleStatus status = VkObjectAuthority_tombstone(
            request->context->handleAuthority, wireToken, &expectation, &value);
    if (status != VORTEK_HANDLE_OK) return false;
    *hostValue = value.hostValue;
    return true;
}

bool vt_request_publish_handle(
        VtRequestDecode* request, VkObjectType objectType,
        VortekHandleRole role, uint64_t hostValue,
        uint64_t instanceOwner, uint64_t deviceOwner,
        uint64_t hostDeviceValue,
        uint64_t* wireToken) {
    if (!request || !request->context || !request->context->handleAuthority ||
            !wireToken) return false;
    *wireToken = 0;
    const bool isDevice = role == VORTEK_HANDLE_ROLE_VULKAN &&
            objectType == VK_OBJECT_TYPE_DEVICE;
    const bool nullDescriptorEnabled = isDevice &&
            request->context->nullDescriptorEnabled;
    if (isDevice) request->context->nullDescriptorEnabled = false;
    /* Every call site reaches publication only after a successful create or
     * a required identity query.  A null host output is therefore invalid. */
    if (hostValue == 0) return false;
    VortekHandleOwner owner = {
        .instance = instanceOwner,
        .device = deviceOwner,
    };
    VortekHandleStatus status = role == VORTEK_HANDLE_ROLE_VULKAN
            ? VkObjectAuthority_publishVulkan(request->context->handleAuthority,
                    objectType, hostValue, owner, wireToken)
            : VkObjectAuthority_publishWrapper(request->context->handleAuthority,
                    role, (const void*)(uintptr_t)hostValue, owner, wireToken);
    if (status == VORTEK_HANDLE_OK && isDevice) {
        status = VkObjectAuthority_setDeviceNullDescriptor(
                request->context->handleAuthority, *wireToken,
                nullDescriptorEnabled);
        if (status != VORTEK_HANDLE_OK) {
            VortekHandleExpectation expectation = {
                .contextGeneration = request->context->contextGeneration,
                .role = VORTEK_HANDLE_ROLE_VULKAN,
                .vulkanType = VK_OBJECT_TYPE_DEVICE,
                .owner = {.instance = instanceOwner},
                .requireInstanceOwner = instanceOwner != 0,
                .allowNull = false,
            };
            (void)VkObjectAuthority_tombstone(
                    request->context->handleAuthority, *wireToken,
                    &expectation, NULL);
            *wireToken = 0;
        }
    }
    if (status == VORTEK_HANDLE_OK && !vt_request_track_single_publication(
            request, objectType, role, owner, hostValue,
            hostDeviceValue, *wireToken)) {
        VortekHandleExpectation expectation = {
            .contextGeneration = request->context->contextGeneration,
            .role = role,
            .vulkanType = objectType,
            .owner = owner,
            .requireInstanceOwner = owner.instance != 0,
            .requireDeviceOwner = owner.device != 0,
            .requireParentOwner = owner.parent != 0,
            .allowNull = false,
        };
        (void)VkObjectAuthority_tombstone(
                request->context->handleAuthority, *wireToken,
                &expectation, NULL);
        *wireToken = 0;
        return false;
    }
    return status == VORTEK_HANDLE_OK;
}

void vt_request_rollback_output(
        VtRequestDecode* request, VkObjectType objectType,
        VortekHandleRole role, uint64_t hostValue, uint64_t hostDeviceValue) {
    if (!request || !request->context) return;
    if (role == VORTEK_HANDLE_ROLE_VULKAN &&
            objectType == VK_OBJECT_TYPE_DEVICE) {
        request->context->nullDescriptorEnabled = false;
    }
    if (hostValue == 0) return;
    VkDevice device = (VkDevice)(uintptr_t)hostDeviceValue;
    switch (role) {
        case VORTEK_HANDLE_ROLE_VULKAN:
            /* Queues are queried device-owned identities, not created objects. */
#if ENABLE_VALIDATION_LAYER
            if (objectType == VK_OBJECT_TYPE_INSTANCE &&
                    request->context->debugReportCallback) {
                vulkanWrapper.vkDestroyDebugReportCallback(
                        (VkInstance)(uintptr_t)hostValue,
                        request->context->debugReportCallback, NULL);
                request->context->debugReportCallback = VK_NULL_HANDLE;
            }
#endif
            if (objectType == VK_OBJECT_TYPE_IMAGE &&
                    request->context->textureDecoder &&
                    TextureDecoder_containsImage(
                            request->context->textureDecoder,
                            (VkImage)(uintptr_t)hostValue)) {
                TextureDecoder_destroyImage(request->context->textureDecoder,
                        device, (VkImage)(uintptr_t)hostValue);
            }
            else if (objectType != VK_OBJECT_TYPE_QUEUE)
                destroyVkObject(objectType, device, (void*)(uintptr_t)hostValue);
            break;
        case VORTEK_HANDLE_ROLE_RESOURCE_MEMORY:
            ResourceMemory_free(request->context, device,
                    (ResourceMemory*)(uintptr_t)hostValue);
            break;
        case VORTEK_HANDLE_ROLE_SHADER_MODULE:
            destroyVkObject(VK_OBJECT_TYPE_SHADER_MODULE, device,
                    (void*)(uintptr_t)hostValue);
            break;
        case VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN:
            XWindowSwapchain_destroy(device,
                    (XWindowSwapchain*)(uintptr_t)hostValue);
            break;
        case VORTEK_HANDLE_ROLE_WINDOW_ID:
            break;
    }
}

bool vt_request_publish_vulkan_batch(
        VtRequestDecode* request, VkObjectType objectType,
        void* hostHandlesInOut, size_t handleSize, size_t count,
        uint64_t instanceOwner, uint64_t deviceOwner, uint64_t parentOwner,
        VtRequestPublicationCleanup cleanup,
        uint64_t hostDeviceValue, uint64_t hostParentValue) {
    if (!request || !request->context || !request->context->handleAuthority ||
            (count != 0 && !hostHandlesInOut) || handleSize != sizeof(uint64_t)) {
        return false;
    }
    if (count == 0) return true;
    if (request->publication.active ||
            count > VT_DECODE_MAX_ELEMENTS ||
            count > SIZE_MAX / sizeof(uint64_t)) return false;
    uint64_t* hostBits = vt_request_output_alloc(
            request, count * sizeof(*hostBits));
    uint64_t* wireTokens = vt_request_output_alloc(
            request, count * sizeof(*wireTokens));
    if (!hostBits || !wireTokens) return false;
    for (size_t index = 0; index < count; ++index) {
        memcpy(&hostBits[index],
                (uint8_t*)hostHandlesInOut + index * handleSize, handleSize);
    }
    VortekHandleOwner owner = {
        .instance = instanceOwner,
        .device = deviceOwner,
        .parent = parentOwner,
    };
    VortekHandleStatus status = VkObjectAuthority_publishVulkanBatch(
            request->context->handleAuthority, objectType, hostBits,
            count, owner, wireTokens);
    if (status != VORTEK_HANDLE_OK) return false;
    request->publication = (VtRequestPublication) {
        .cleanup = cleanup,
        .objectType = objectType,
        .role = VORTEK_HANDLE_ROLE_VULKAN,
        .owner = owner,
        .hostDeviceValue = hostDeviceValue,
        .hostParentValue = hostParentValue,
        .hostValues = hostBits,
        .wireTokens = wireTokens,
        .count = count,
        .active = true,
    };
    for (size_t index = 0; index < count; ++index) {
        memcpy((uint8_t*)hostHandlesInOut + index * handleSize,
                &wireTokens[index], handleSize);
    }
    return true;
}

static void vt_request_commit_response(VtRequestDecode* request) {
    if (!request) return;
    memset(&request->publication, 0, sizeof(request->publication));
}

void vt_request_response_abort(
        VtRequestDecode* request, VtDecodeError error) {
    if (!request || !request->context) return;
    VtRequestPublication publication = request->publication;
    memset(&request->publication, 0, sizeof(request->publication));
    bool retired = false;
    if (publication.active && request->context->handleAuthority) {
        VortekHandleExpectation expectation = {
            .contextGeneration = request->context->contextGeneration,
            .role = publication.role,
            .vulkanType = publication.objectType,
            .owner = publication.owner,
            .requireInstanceOwner = publication.owner.instance != 0,
            .requireDeviceOwner = publication.owner.device != 0,
            .requireParentOwner = publication.owner.parent != 0,
            .allowNull = false,
        };
        if (publication.cleanup == VT_REQUEST_PUBLICATION_SINGLE) {
            retired = VkObjectAuthority_tombstone(
                    request->context->handleAuthority,
                    publication.wireToken, &expectation, NULL) ==
                    VORTEK_HANDLE_OK;
        }
        else {
            retired = VkObjectAuthority_tombstoneBatch(
                    request->context->handleAuthority,
                    publication.wireTokens, publication.count,
                    &expectation) == VORTEK_HANDLE_OK;
        }
    }
    if (retired) {
        VkDevice device = (VkDevice)(uintptr_t)publication.hostDeviceValue;
        if (publication.cleanup == VT_REQUEST_PUBLICATION_SINGLE) {
            vt_request_rollback_output(request, publication.objectType,
                    publication.role, publication.hostValue,
                    publication.hostDeviceValue);
        }
        else if (publication.cleanup == VT_REQUEST_PUBLICATION_DESCRIPTOR_SETS) {
            (void)vulkanWrapper.vkFreeDescriptorSets(device,
                    (VkDescriptorPool)(uintptr_t)publication.hostParentValue,
                    (uint32_t)publication.count,
                    (const VkDescriptorSet*)publication.hostValues);
        }
        else if (publication.cleanup == VT_REQUEST_PUBLICATION_COMMAND_BUFFERS) {
            vulkanWrapper.vkFreeCommandBuffers(device,
                    (VkCommandPool)(uintptr_t)publication.hostParentValue,
                    (uint32_t)publication.count,
                    (const VkCommandBuffer*)publication.hostValues);
        }
    }
    vt_request_protocol_error(request->context, error);
}

bool vt_request_send_response(
        VtRequestDecode* request, int requestCode,
        const void* data, int size) {
    if (!request || !request->context || !request->context->clientRing ||
            (size > 0 && !data) ||
            !vt_transport_payload_fits(size,
                    request->context->clientRing->bufferSize,
                    CLIENT_RING_BUFFER_SIZE - HEADER_SIZE)) {
        vt_request_response_abort(request, VT_DECODE_ERROR_LIMIT);
        return false;
    }
    uint8_t header[8];
    memcpy(header, &requestCode, sizeof(requestCode));
    memcpy(header + sizeof(requestCode), &size, sizeof(size));
    if (!RingBuffer_writeFrame(request->context->clientRing,
            header, HEADER_SIZE, data, (uint32_t)size)) {
        vt_request_response_abort(request, VT_DECODE_ERROR_ARGUMENT);
        return false;
    }
    vt_request_commit_response(request);
    return true;
}

static bool vt_request_send_fds_response(
        VtRequestDecode* request, int* fds, int fdCount,
        const void* data, int size) {
    if (!request || !request->context || fdCount < 0 || fdCount > MAX_FDS ||
            (fdCount > 0 && !fds) || size < 0 || (size > 0 && !data)) {
        vt_request_response_abort(request, VT_DECODE_ERROR_LIMIT);
        return false;
    }
    const int expected = size > 0 ? size : 1;
    if (send_fds(request->context->clientFd, fds, fdCount,
            (void*)data, size) != expected) {
        vt_request_response_abort(request, VT_DECODE_ERROR_ARGUMENT);
        return false;
    }
    vt_request_commit_response(request);
    return true;
}

bool vt_request_capture_begin(
        VtRequestDecode* request, VkObjectType objectType,
        uint64_t* wireTokens, size_t capacity) {
    if (!request || !request->state ||
            (capacity != 0 && !wireTokens)) return false;
    vt_decode_capture_handle_tokens(
            request->state, (uint32_t)objectType, wireTokens, capacity);
    return true;
}

bool vt_request_capture_complete(
        VtRequestDecode* request, size_t expectedCount) {
    if (!request || !request->state) return false;
    const bool complete =
            vt_decode_captured_handle_count(request->state) == expectedCount;
    vt_decode_capture_handle_tokens(request->state, 0, NULL, 0);
    return complete;
}

bool vt_request_tombstone_batch(
        VtRequestDecode* request, const uint64_t* wireTokens, size_t count,
        VkObjectType objectType, uint64_t parentOwner) {
    if (!request || !request->state || !request->context ||
            !request->context->handleAuthority ||
            (count != 0 && !wireTokens)) return false;
    VortekHandleExpectation expectation = {
        .contextGeneration = request->context->contextGeneration,
        .role = VORTEK_HANDLE_ROLE_VULKAN,
        .vulkanType = objectType,
        .owner = {
            .instance = request->state->instance_owner,
            .device = request->state->device_owner,
            .parent = parentOwner,
        },
        .requireInstanceOwner = true,
        .requireDeviceOwner = true,
        .requireParentOwner = parentOwner != 0,
        .allowNull = false,
    };
    return VkObjectAuthority_tombstoneBatch(
            request->context->handleAuthority, wireTokens,
            count, &expectation) == VORTEK_HANDLE_OK;
}

bool vt_request_validate_batch(
        VtRequestDecode* request, const uint64_t* wireTokens, size_t count,
        VkObjectType objectType, uint64_t parentOwner) {
    if (!request || !request->state || !request->context ||
            !request->context->handleAuthority ||
            (count != 0 && !wireTokens)) return false;
    VortekHandleExpectation expectation = {
        .contextGeneration = request->context->contextGeneration,
        .role = VORTEK_HANDLE_ROLE_VULKAN,
        .vulkanType = objectType,
        .owner = {
            .instance = request->state->instance_owner,
            .device = request->state->device_owner,
            .parent = parentOwner,
        },
        .requireInstanceOwner = true,
        .requireDeviceOwner = true,
        .requireParentOwner = parentOwner != 0,
        .allowNull = false,
    };
    return VkObjectAuthority_validateBatch(
            request->context->handleAuthority, wireTokens,
            count, &expectation) == VORTEK_HANDLE_OK;
}

bool vt_request_tombstone_children(
        VtRequestDecode* request, uint64_t parentOwner) {
    return request && request->context && request->context->handleAuthority &&
            VkObjectAuthority_tombstoneChildren(
                    request->context->handleAuthority, parentOwner) ==
                    VORTEK_HANDLE_OK;
}

void vt_request_protocol_error(VkContext* context, VtDecodeError error) {
    (void)error;
    VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
}

uint32_t vt_request_query_copy_count(uint32_t guestCapacity, uint32_t serverActual) {
    return guestCapacity < serverActual ? guestCapacity : serverActual;
}

VkResult vt_request_query_result(
        VkResult serverResult, bool guestArray,
        uint32_t guestCapacity, uint32_t serverActual) {
    if (serverResult < 0) return serverResult;
    return guestArray && guestCapacity < serverActual ? VK_INCOMPLETE : serverResult;
}


#define MSG_DEBUG_UNIMPLEMENTED_FUNC "%s not implemented yet\n"

void vt_handle_vkCreateInstance(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VkInstanceCreateInfo createInfo = {0};
    VT_REQUEST_DECODE(vt_unserialize_vkCreateInstance(&createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));

    const char* skipExtensions[] = {"VK_KHR_surface", "VK_KHR_xlib_surface"};

#if ENABLE_VALIDATION_LAYER
    createInfo.ppEnabledLayerNames = validationLayers;
    createInfo.enabledLayerCount = ARRAY_SIZE(validationLayers);

    const char* extraExtensions[] = {"VK_KHR_get_physical_device_properties2", "VK_KHR_external_memory_capabilities", "VK_KHR_external_fence_capabilities", "VK_EXT_debug_report"};
#else
    const char* extraExtensions[] = {"VK_KHR_get_physical_device_properties2", "VK_KHR_external_memory_capabilities", "VK_KHR_external_fence_capabilities"};
#endif

    injectExtensions(context, (char***)&createInfo.ppEnabledExtensionNames, &createInfo.enabledExtensionCount,
                     extraExtensions, ARRAY_SIZE(extraExtensions),
                     skipExtensions, ARRAY_SIZE(skipExtensions));

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateInstance(&createInfo, NULL, &instance);
    if (result == VK_SUCCESS) initVulkanInstance(context, instance, createInfo.pApplicationInfo);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_INSTANCE, VORTEK_HANDLE_ROLE_VULKAN, instance, 0, 0, VK_NULL_HANDLE, _vt_wire_output);

    VT_SERIALIZE_CMD(VkInstance, (VkInstance)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyInstance(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_INSTANCE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t instanceId;
    VT_REQUEST_DECODE(vt_unserialize_vkDestroyInstance((VkInstance)&instanceId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_AUTHORITY(VkContext_beginInstanceRetirement(
            context, instanceId));
    VT_REQUEST_HANDLE(VkInstance, instance, instanceId, VK_OBJECT_TYPE_INSTANCE, VORTEK_HANDLE_ROLE_VULKAN, false, false, false);

#if ENABLE_VALIDATION_LAYER
    if (context->debugReportCallback) {
        vulkanWrapper.vkDestroyDebugReportCallback(instance, context->debugReportCallback, NULL);
        context->debugReportCallback = VK_NULL_HANDLE;
    }
#endif

    VT_REQUEST_AUTHORITY(VkContext_reclaimAuthority(
            context, VORTEK_HANDLE_DRAIN_INSTANCE, instanceId));
    if (!VkContext_releaseWindowInstanceAuthority(context, instanceId))
        VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
    (void)instance;
}

void vt_handle_vkEnumeratePhysicalDevices(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_INSTANCE, VORTEK_HANDLE_ROLE_VULKAN);
    uint32_t physicalDeviceCount;
    uint64_t instanceId;
    VT_REQUEST_DECODE(vt_unserialize_vkEnumeratePhysicalDevices((VkInstance)&instanceId, &physicalDeviceCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkInstance, instance, instanceId, VK_OBJECT_TYPE_INSTANCE, VORTEK_HANDLE_ROLE_VULKAN, false, false, false);

    const uint32_t guestCapacity = physicalDeviceCount;
    uint32_t serverActual = 0;
    VkResult result = vulkanWrapper.vkEnumeratePhysicalDevices(instance, &serverActual, NULL);
    const uint32_t returnedCount = guestCapacity > 0
            ? vt_request_query_copy_count(guestCapacity, serverActual) : 0;
    VT_REQUEST_ARRAY(VkPhysicalDevice, physicalDevices, returnedCount);
    uint32_t hostCount = returnedCount;
    if (physicalDevices) result = vulkanWrapper.vkEnumeratePhysicalDevices(
            instance, &hostCount, physicalDevices);
    physicalDeviceCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);
    result = vt_request_query_result(result, guestCapacity != 0, guestCapacity, serverActual);

    const uint32_t publishedCount = physicalDevices
            ? physicalDeviceCount : 0;
    if (result >= 0 && !vt_request_publish_vulkan_batch(&_vt_request,
            VK_OBJECT_TYPE_PHYSICAL_DEVICE, physicalDevices,
            sizeof(*physicalDevices), publishedCount, instanceId, 0, 0,
            VT_REQUEST_PUBLICATION_NONE, 0, 0)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
    if (result < 0 && physicalDevices)
        memset(physicalDevices, 0, physicalDeviceCount * sizeof(*physicalDevices));
    VT_SERIALIZE_CMD(vkEnumeratePhysicalDevices, VK_NULL_HANDLE, &physicalDeviceCount, physicalDevices);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VT_REQUEST_DECODE(vt_unserialize_VkPhysicalDevice((VkPhysicalDevice)&physicalDeviceId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkPhysicalDeviceProperties properties = {0};
    vulkanWrapper.vkGetPhysicalDeviceProperties(physicalDevice, &properties);
    checkDeviceProperties(context, &properties, NULL);

    VT_SERIALIZE_CMD(VkPhysicalDeviceProperties, &properties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceQueueFamilyProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    uint32_t queueFamilyPropertyCount;

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceQueueFamilyProperties((VkPhysicalDevice)&physicalDeviceId, &queueFamilyPropertyCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    const uint32_t guestCapacity = queueFamilyPropertyCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkQueueFamilyProperties, queueFamilyProperties,
            guestCapacity > 0 ? serverActual : 0);
    uint32_t hostCount = serverActual;
    if (queueFamilyProperties) vulkanWrapper.vkGetPhysicalDeviceQueueFamilyProperties(
            physicalDevice, &hostCount, queueFamilyProperties);
    queueFamilyPropertyCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceQueueFamilyProperties, NULL, &queueFamilyPropertyCount, queueFamilyProperties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceMemoryProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VT_REQUEST_DECODE(vt_unserialize_VkPhysicalDevice((VkPhysicalDevice)&physicalDeviceId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkPhysicalDeviceMemoryProperties memoryProperties = {0};
    vulkanWrapper.vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProperties);
    checkDeviceMemoryProperties(context, &memoryProperties, NULL);

    VT_SERIALIZE_CMD(VkPhysicalDeviceMemoryProperties, &memoryProperties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceFeatures(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VT_REQUEST_DECODE(vt_unserialize_VkPhysicalDevice((VkPhysicalDevice)&physicalDeviceId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkPhysicalDeviceFeatures features = {0};
    vulkanWrapper.vkGetPhysicalDeviceFeatures(physicalDevice, &features);
    checkDeviceFeatures(&features, NULL);

    VT_SERIALIZE_CMD(VkPhysicalDeviceFeatures, &features);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceFormatProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkFormat format;

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceFormatProperties((VkPhysicalDevice)&physicalDeviceId, &format, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkFormatProperties formatProperties = {0};
    vulkanWrapper.vkGetPhysicalDeviceFormatProperties(physicalDevice, format, &formatProperties);
    checkFormatProperties(physicalDevice, format, &formatProperties);

    VT_SERIALIZE_CMD(VkFormatProperties, &formatProperties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceImageFormatProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkFormat format;
    VkImageType type;
    VkImageTiling tiling;
    VkImageUsageFlags usage;
    VkImageCreateFlags flags;

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceImageFormatProperties((VkPhysicalDevice)&physicalDeviceId, &format, &type, &tiling, &usage, &flags, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkImageFormatProperties imageFormatProperties = {0};
    VkResult result = vulkanWrapper.vkGetPhysicalDeviceImageFormatProperties(physicalDevice, format, type, tiling, usage, flags, &imageFormatProperties);
    checkImageFormatProperties(format, type, tiling, usage, flags, &imageFormatProperties, &result);

    VT_SERIALIZE_CMD(VkImageFormatProperties, &imageFormatProperties);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkCreateDevice(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkDeviceCreateInfo createInfo = {0};
    VT_REQUEST_DECODE(vt_unserialize_vkCreateDevice((VkPhysicalDevice)&physicalDeviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    disableUnsupportedDeviceFeatures(context, physicalDevice, &createInfo);

    const char* extraExtensions[] = {"VK_KHR_get_memory_requirements2", "VK_KHR_dedicated_allocation", "VK_KHR_external_memory", "VK_KHR_external_memory_fd", "VK_KHR_external_fence", "VK_KHR_external_fence_fd", "VK_ANDROID_external_memory_android_hardware_buffer", "VK_EXT_queue_family_foreign"};
    injectExtensions(context, (char***)&createInfo.ppEnabledExtensionNames, &createInfo.enabledExtensionCount,
                     extraExtensions, ARRAY_SIZE(extraExtensions),
                     globalImplementedDeviceExtensions, ARRAY_SIZE(globalImplementedDeviceExtensions));

    VkDevice device = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateDevice(physicalDevice, &createInfo, NULL, &device);
    if (result == VK_SUCCESS) {
        result = initVulkanDevice(context, physicalDevice, device);
        if (result != VK_SUCCESS) {
            vulkanWrapper.vkDestroyDevice(device, NULL);
            device = VK_NULL_HANDLE;
        }
    }

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, device, _vt_request.state->instance_owner, 0, VK_NULL_HANDLE, _vt_wire_output);

    VT_SERIALIZE_CMD(VkDevice, (VkDevice)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyDevice(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyDevice((VkDevice)&deviceId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_AUTHORITY(VkContext_beginDeviceRetirement(
            context, deviceId));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_AUTHORITY(VkContext_reclaimAuthority(
            context, VORTEK_HANDLE_DRAIN_DEVICE, deviceId));
    (void)device;
}

void vt_handle_vkEnumerateInstanceVersion(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    uint32_t requestedVersion = 0;
    VT_REQUEST_DECODE(vt_unserialize_vkEnumerateInstanceVersion(&requestedVersion, &_vt_cursor, &context->memoryPool));
    (void)requestedVersion;
    VT_REQUEST_SEND(VK_SUCCESS, &context->vkMaxVersion, 4);
}

void vt_handle_vkEnumerateInstanceExtensionProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    uint32_t propertyCount;
    VT_REQUEST_DECODE(vt_unserialize_vkEnumerateInstanceExtensionProperties(NULL, &propertyCount, NULL, &_vt_cursor, &context->memoryPool));
    const uint32_t guestCapacity = propertyCount;
    uint32_t exposedExtensionCount = 0;
    VkResult result = vulkanWrapper.vkEnumerateInstanceExtensionProperties(
            NULL, &exposedExtensionCount, NULL);
    VkExtensionProperties* exposedExtensions = vt_request_output_alloc(
            &_vt_request, (size_t)exposedExtensionCount * sizeof(*exposedExtensions));
    if (result == VK_SUCCESS && exposedExtensionCount > 0 && !exposedExtensions) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_OUT_OF_MEMORY);
        return;
    }
    if (result == VK_SUCCESS && exposedExtensions) result =
            vulkanWrapper.vkEnumerateInstanceExtensionProperties(
                    NULL, &exposedExtensionCount, exposedExtensions);

    const char* extraExtensions[] = {"VK_KHR_surface", "VK_KHR_xlib_surface"};
    const char* skipExtensions[] = {"VK_KHR_android_surface"};
    injectExtensions2(context, &exposedExtensions, &exposedExtensionCount,
                      extraExtensions, ARRAY_SIZE(extraExtensions),
                      skipExtensions, ARRAY_SIZE(skipExtensions));

    VkExtensionProperties* properties = guestCapacity > 0 ? exposedExtensions : NULL;
    propertyCount = guestCapacity == 0 ? exposedExtensionCount :
            vt_request_query_copy_count(guestCapacity, exposedExtensionCount);
    result = vt_request_query_result(
            result, guestCapacity != 0, guestCapacity, exposedExtensionCount);

    VT_SERIALIZE_CMD(vkEnumerateInstanceExtensionProperties, NULL, &propertyCount, properties);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkEnumerateDeviceExtensionProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    uint32_t propertyCount;

    VT_REQUEST_DECODE(vt_unserialize_vkEnumerateDeviceExtensionProperties((VkPhysicalDevice)&physicalDeviceId, NULL, &propertyCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    const uint32_t guestCapacity = propertyCount;
    uint32_t serverActual = 0;
    VkExtensionProperties* properties = getExposedDeviceExtensionProperties(
            context, physicalDevice, &serverActual);
    VkResult result = properties ? VK_SUCCESS : VK_ERROR_OUT_OF_HOST_MEMORY;
    propertyCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, serverActual);
    result = vt_request_query_result(result, guestCapacity != 0, guestCapacity, serverActual);

    VT_SERIALIZE_CMD(vkEnumerateDeviceExtensionProperties, NULL, NULL, &propertyCount, guestCapacity > 0 ? properties : NULL);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkGetDeviceQueue(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint32_t queueFamilyIndex;
    uint32_t queueIndex;

    VT_REQUEST_DECODE(vt_unserialize_vkGetDeviceQueue((VkDevice)&deviceId, &queueFamilyIndex, &queueIndex, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkQueue queue = VK_NULL_HANDLE;
    vulkanWrapper.vkGetDeviceQueue(device, queueFamilyIndex, queueIndex, &queue);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH(VK_OBJECT_TYPE_QUEUE, VORTEK_HANDLE_ROLE_VULKAN, queue, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkQueue, (VkQueue)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkQueueSubmit(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_QUEUE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t queueId;
    uint32_t submitCount;
    uint64_t fenceId;

    VT_REQUEST_DECODE(vt_unserialize_vkQueueSubmit((VkQueue)&queueId, &submitCount, NULL, (VkFence)&fenceId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkQueue, queue, queueId, VK_OBJECT_TYPE_QUEUE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkFence, fence, fenceId, VK_OBJECT_TYPE_FENCE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    VT_REQUEST_ARRAY(VkSubmitInfo, submits, submitCount);
    VT_REQUEST_DECODE(vt_unserialize_vkQueueSubmit(VK_NULL_HANDLE, NULL, submits, VK_NULL_HANDLE, &_vt_cursor, &context->memoryPool));

    bool clientWaiting = RingBuffer_hasStatus(context->clientRing, RING_STATUS_WAIT);
    if (context->textureDecoder) TextureDecoder_decodeAll(context->textureDecoder);

    VkResult result = vulkanWrapper.vkQueueSubmit(queue, submitCount, submits, fence);
    if (result == VK_ERROR_DEVICE_LOST) context->status = result;

    if (clientWaiting) VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkQueueWaitIdle(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_QUEUE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t queueId;
    VT_REQUEST_DECODE(vt_unserialize_VkQueue((VkQueue)&queueId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkQueue, queue, queueId, VK_OBJECT_TYPE_QUEUE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkResult result = vulkanWrapper.vkQueueWaitIdle(queue);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkDeviceWaitIdle(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VT_REQUEST_DECODE(vt_unserialize_VkDevice((VkDevice)&deviceId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkResult result = vulkanWrapper.vkDeviceWaitIdle(device);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkAllocateMemory(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkMemoryAllocateInfo allocateInfo = {0};
    VT_REQUEST_DECODE(vt_unserialize_vkAllocateMemory((VkDevice)&deviceId, &allocateInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    ResourceMemory* resourceMemory = ResourceMemory_allocate(context, device, &allocateInfo);
    VkResult result = resourceMemory ? VK_SUCCESS : VK_ERROR_OUT_OF_DEVICE_MEMORY;

    if (result == VK_SUCCESS) {
        uint64_t _vt_wire_output = 0;
        VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_RESOURCE_MEMORY, resourceMemory, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);
        VT_SERIALIZE_CMD(VkDeviceMemory, (VkDeviceMemory)(uintptr_t)_vt_wire_output);
        VT_REQUEST_SEND(result, outputBuffer, bufferSize);
    }
    else VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkFreeMemory(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t memoryId;

    VT_REQUEST_DECODE(vt_unserialize_vkFreeMemory((VkDevice)&deviceId, (VkDeviceMemory)&memoryId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(ResourceMemory*, resourceMemory, memoryId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_RESOURCE_MEMORY, true, true, true);

    ResourceMemory_free(context, device, resourceMemory);
}

void vt_handle_vkMapMemory(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_RESOURCE_MEMORY);
    uint64_t memoryId;
    VT_REQUEST_DECODE(vt_unserialize_VkDeviceMemory((VkDeviceMemory)&memoryId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(ResourceMemory*, resourceMemory, memoryId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_RESOURCE_MEMORY, true, true, false);

    VkResult result = resourceMemory->fd != -1 ? VK_SUCCESS : VK_ERROR_MEMORY_MAP_FAILED;
    if (!vt_request_send_fds_response(&_vt_request,
            &resourceMemory->fd, result == VK_SUCCESS ? 1 : 0,
            &result, sizeof(result))) return;
}

void vt_handle_vkUnmapMemory(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t memoryId;
    VT_REQUEST_DECODE(vt_unserialize_vkUnmapMemory((VkDevice)&deviceId, (VkDeviceMemory)&memoryId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(ResourceMemory*, resourceMemory, memoryId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_RESOURCE_MEMORY, true, true, false);
    /* ResourceMemory mappings are persistent fd-backed mappings; unmap is a
     * transport no-op, but both authorities must still be exact and live. */
    (void)device;
    (void)resourceMemory;
}

void vt_handle_vkFlushMappedMemoryRanges(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint32_t memoryRangeCount;

    VT_REQUEST_DECODE(vt_unserialize_vkFlushMappedMemoryRanges((VkDevice)&deviceId, &memoryRangeCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_ARRAY(VkMappedMemoryRange, memoryRanges, memoryRangeCount);
    VT_REQUEST_DECODE(vt_unserialize_vkFlushMappedMemoryRanges(VK_NULL_HANDLE, NULL, memoryRanges, &_vt_cursor, &context->memoryPool));

    VkResult result = vulkanWrapper.vkFlushMappedMemoryRanges(device, memoryRangeCount, memoryRanges);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkInvalidateMappedMemoryRanges(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint32_t memoryRangeCount;

    VT_REQUEST_DECODE(vt_unserialize_vkInvalidateMappedMemoryRanges((VkDevice)&deviceId, &memoryRangeCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_ARRAY(VkMappedMemoryRange, memoryRanges, memoryRangeCount);
    VT_REQUEST_DECODE(vt_unserialize_vkInvalidateMappedMemoryRanges(VK_NULL_HANDLE, NULL, memoryRanges, &_vt_cursor, &context->memoryPool));

    VkResult result = vulkanWrapper.vkInvalidateMappedMemoryRanges(device, memoryRangeCount, memoryRanges);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkGetDeviceMemoryCommitment(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t memoryId;

    VT_REQUEST_DECODE(vt_unserialize_vkGetDeviceMemoryCommitment((VkDevice)&deviceId, (VkDeviceMemory)&memoryId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(ResourceMemory*, resourceMemory, memoryId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_RESOURCE_MEMORY, true, true, false);

    VkDeviceSize committedMemoryInBytes;
    vulkanWrapper.vkGetDeviceMemoryCommitment(device, resourceMemory->memory, &committedMemoryInBytes);

    VT_REQUEST_SEND(VK_SUCCESS, &committedMemoryInBytes, sizeof(VkDeviceSize));
}

void vt_handle_vkGetBufferMemoryRequirements(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t bufferId;

    VT_REQUEST_DECODE(vt_unserialize_vkGetBufferMemoryRequirements((VkDevice)&deviceId, (VkBuffer)&bufferId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkBuffer, buffer, bufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkMemoryRequirements memoryRequirements = {0};
    vulkanWrapper.vkGetBufferMemoryRequirements(device, buffer, &memoryRequirements);

    VT_SERIALIZE_CMD(VkMemoryRequirements, &memoryRequirements);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkBindBufferMemory(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t bufferId;
    uint64_t memoryId;
    VkDeviceSize memoryOffset;

    VT_REQUEST_DECODE(vt_unserialize_vkBindBufferMemory((VkDevice)&deviceId, (VkBuffer)&bufferId, (VkDeviceMemory)&memoryId, &memoryOffset, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkBuffer, buffer, bufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(ResourceMemory*, resourceMemory, memoryId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_RESOURCE_MEMORY, true, true, false);

    VkResult result = vulkanWrapper.vkBindBufferMemory(device, buffer, resourceMemory->memory, memoryOffset);

    if (result == VK_SUCCESS && context->textureDecoder) {
        TextureDecoder_addBoundBuffer(context->textureDecoder, resourceMemory, buffer, memoryOffset);
    }

    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkGetImageMemoryRequirements(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t imageId;

    VT_REQUEST_DECODE(vt_unserialize_vkGetImageMemoryRequirements((VkDevice)&deviceId, (VkImage)&imageId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkImage, image, imageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkMemoryRequirements memoryRequirements = {0};
    vulkanWrapper.vkGetImageMemoryRequirements(device, image, &memoryRequirements);

    VT_SERIALIZE_CMD(VkMemoryRequirements, &memoryRequirements);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkBindImageMemory(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t imageId;
    uint64_t memoryId;
    VkDeviceSize memoryOffset;

    VT_REQUEST_DECODE(vt_unserialize_vkBindImageMemory((VkDevice)&deviceId, (VkImage)&imageId, (VkDeviceMemory)&memoryId, &memoryOffset, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkImage, image, imageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(ResourceMemory*, resourceMemory, memoryId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_RESOURCE_MEMORY, true, true, false);

    VkResult result = vulkanWrapper.vkBindImageMemory(device, image, resourceMemory->memory, memoryOffset);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkGetImageSparseMemoryRequirements(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t imageId;
    uint32_t requirementCount;

    VT_REQUEST_DECODE(vt_unserialize_vkGetImageSparseMemoryRequirements((VkDevice)&deviceId, (VkImage)&imageId, &requirementCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkImage, image, imageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    const uint32_t guestCapacity = requirementCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetImageSparseMemoryRequirements(device, image, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkSparseImageMemoryRequirements, requirements,
            guestCapacity > 0 ? serverActual : 0);
    uint32_t hostCount = serverActual;
    if (requirements) vulkanWrapper.vkGetImageSparseMemoryRequirements(
            device, image, &hostCount, requirements);
    requirementCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetImageSparseMemoryRequirements, VK_NULL_HANDLE, VK_NULL_HANDLE, &requirementCount, requirements);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceSparseImageFormatProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkFormat format;
    VkImageType type;
    VkSampleCountFlagBits samples;
    VkImageUsageFlags usage;
    VkImageTiling tiling;
    uint32_t propertyCount;

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceSparseImageFormatProperties((VkPhysicalDevice)&physicalDeviceId, &format, &type, &samples, &usage, &tiling, &propertyCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    const uint32_t guestCapacity = propertyCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetPhysicalDeviceSparseImageFormatProperties(
            physicalDevice, format, type, samples, usage, tiling, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkSparseImageFormatProperties, properties,
            guestCapacity > 0 ? serverActual : 0);
    uint32_t hostCount = serverActual;
    if (properties) vulkanWrapper.vkGetPhysicalDeviceSparseImageFormatProperties(
            physicalDevice, format, type, samples, usage, tiling, &hostCount, properties);
    propertyCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceSparseImageFormatProperties, VK_NULL_HANDLE, format, type, samples, usage, tiling, &propertyCount, properties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkQueueBindSparse(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_QUEUE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t queueId;
    uint32_t bindInfoCount;
    uint64_t fenceId;

    VT_REQUEST_DECODE(vt_unserialize_vkQueueBindSparse((VkQueue)&queueId, &bindInfoCount, NULL, (VkFence)&fenceId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkQueue, queue, queueId, VK_OBJECT_TYPE_QUEUE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkFence, fence, fenceId, VK_OBJECT_TYPE_FENCE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    VT_REQUEST_ARRAY(VkBindSparseInfo, bindInfo, bindInfoCount);
    VT_REQUEST_DECODE(vt_unserialize_vkQueueBindSparse(VK_NULL_HANDLE, NULL, bindInfo, VK_NULL_HANDLE, &_vt_cursor, &context->memoryPool));

    VkResult result = vulkanWrapper.vkQueueBindSparse(queue, bindInfoCount, bindInfo, fence);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkCreateFence(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkFenceCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateFence((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkExportFenceCreateInfo exportFenceInfo = {0};
    exportFenceInfo.sType = VK_STRUCTURE_TYPE_EXPORT_FENCE_CREATE_INFO;
    exportFenceInfo.handleTypes = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;
    exportFenceInfo.pNext = createInfo.pNext;
    createInfo.pNext = &exportFenceInfo;

    VkFence fence = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateFence(device, &createInfo, NULL, &fence);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_FENCE, VORTEK_HANDLE_ROLE_VULKAN, fence, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkFence, (VkFence)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyFence(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t fenceId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyFence((VkDevice)&deviceId, (VkFence)&fenceId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkFence, fence, fenceId, VK_OBJECT_TYPE_FENCE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyFence(device, fence, NULL);
}

void vt_handle_vkResetFences(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint32_t fenceCount;

    VT_REQUEST_DECODE(vt_unserialize_vkResetFences((VkDevice)&deviceId, &fenceCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_ARRAY(VkFence, fences, fenceCount);
    VT_REQUEST_DECODE(vt_unserialize_vkResetFences(VK_NULL_HANDLE, NULL, fences, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkResetFences(device, fenceCount, fences);
}

void vt_handle_vkGetFenceStatus(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t fenceId;

    VT_REQUEST_DECODE(vt_unserialize_vkGetFenceStatus((VkDevice)&deviceId, (VkFence)&fenceId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkFence, fence, fenceId, VK_OBJECT_TYPE_FENCE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkResult result = vulkanWrapper.vkGetFenceStatus(device, fence);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkWaitForFences(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint32_t fenceCount;
    VkBool32 waitAll;
    uint64_t timeout;

    VT_REQUEST_DECODE(vt_unserialize_vkWaitForFences((VkDevice)&deviceId, &fenceCount, NULL, &waitAll, &timeout, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_ARRAY(VkFence, fences, fenceCount);
    VT_REQUEST_DECODE(vt_unserialize_vkWaitForFences(VK_NULL_HANDLE, NULL, fences, NULL, NULL, &_vt_cursor, &context->memoryPool));

    if (timeout != 0) {
        if (fenceCount > MAX_FDS) {
            vt_request_protocol_error(context, VT_DECODE_ERROR_LIMIT);
            return;
        }
        VkResult result = VK_SUCCESS;
        VT_REQUEST_ARRAY(int, fds, fenceCount);
        for (uint32_t i = 0; i < fenceCount; i++) fds[i] = -1;
        uint32_t acquiredFdCount = 0;
        for (uint32_t i = 0; i < fenceCount; i++) {
            VkFenceGetFdInfoKHR getFdInfo = {0};
            getFdInfo.sType = VK_STRUCTURE_TYPE_FENCE_GET_FD_INFO_KHR;
            getFdInfo.fence = fences[i];
            getFdInfo.handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;

            result = vulkanWrapper.vkGetFenceFd(device, &getFdInfo, &fds[i]);
            if (result != VK_SUCCESS) break;
            acquiredFdCount++;
        }

        const bool sent = vt_request_send_fds_response(&_vt_request, fds,
                result == VK_SUCCESS ? (int)acquiredFdCount : 0,
                &result, sizeof(result));
        for (uint32_t i = 0; i < acquiredFdCount; i++) CLOSEFD(fds[i]);
        if (!sent) return;
    }
    else {
        VkResult result = vulkanWrapper.vkWaitForFences(device, fenceCount, fences, waitAll, timeout);
        VT_REQUEST_SEND(result, NULL, 0);
    }
}

void vt_handle_vkCreateSemaphore(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkSemaphoreCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateSemaphore((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkSemaphore semaphore = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateSemaphore(device, &createInfo, NULL, &semaphore);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_SEMAPHORE, VORTEK_HANDLE_ROLE_VULKAN, semaphore, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkSemaphore, (VkSemaphore)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroySemaphore(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t semaphoreId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroySemaphore((VkDevice)&deviceId, (VkSemaphore)&semaphoreId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkSemaphore, semaphore, semaphoreId, VK_OBJECT_TYPE_SEMAPHORE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroySemaphore(device, semaphore, NULL);
}

void vt_handle_vkCreateEvent(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkEventCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateEvent((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkEvent event = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateEvent(device, &createInfo, NULL, &event);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_EVENT, VORTEK_HANDLE_ROLE_VULKAN, event, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkEvent, (VkEvent)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyEvent(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t eventId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyEvent((VkDevice)&deviceId, (VkEvent)&eventId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkEvent, event, eventId, VK_OBJECT_TYPE_EVENT, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyEvent(device, event, NULL);
}

void vt_handle_vkGetEventStatus(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t eventId;

    VT_REQUEST_DECODE(vt_unserialize_vkGetEventStatus((VkDevice)&deviceId, (VkEvent)&eventId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkEvent, event, eventId, VK_OBJECT_TYPE_EVENT, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkResult result = vulkanWrapper.vkGetEventStatus(device, event);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkSetEvent(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t eventId;

    VT_REQUEST_DECODE(vt_unserialize_vkSetEvent((VkDevice)&deviceId, (VkEvent)&eventId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkEvent, event, eventId, VK_OBJECT_TYPE_EVENT, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkResult result = vulkanWrapper.vkSetEvent(device, event);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkResetEvent(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t eventId;

    VT_REQUEST_DECODE(vt_unserialize_vkResetEvent((VkDevice)&deviceId, (VkEvent)&eventId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkEvent, event, eventId, VK_OBJECT_TYPE_EVENT, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkResult result = vulkanWrapper.vkResetEvent(device, event);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkCreateQueryPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkQueryPoolCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateQueryPool((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkQueryPool queryPool = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateQueryPool(device, &createInfo, NULL, &queryPool);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, queryPool, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkQueryPool, (VkQueryPool)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyQueryPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t queryPoolId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyQueryPool((VkDevice)&deviceId, (VkQueryPool)&queryPoolId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyQueryPool(device, queryPool, NULL);
}

void vt_handle_vkGetQueryPoolResults(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t queryPoolId;
    uint32_t firstQuery;
    uint32_t queryCount;
    size_t dataSize;
    VkDeviceSize stride;
    VkQueryResultFlags flags;

    VT_REQUEST_DECODE(vt_unserialize_vkGetQueryPoolResults((VkDevice)&deviceId, (VkQueryPool)&queryPoolId, &firstQuery, &queryCount, &dataSize, NULL, &stride, &flags, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    void* data = dataSize > 0
            ? vt_request_output_alloc(&_vt_request, (size_t)dataSize) : NULL;
    if (dataSize > 0 && !data) {
        vt_request_response_abort(&_vt_request,
                vt_decode_error(&(VtDecodeCursor){.state = _vt_request.state}));
        return;
    }
    VkResult result = vulkanWrapper.vkGetQueryPoolResults(device, queryPool, firstQuery, queryCount, dataSize, data, stride, flags);
    VT_SERIALIZE_CMD(vkGetQueryPoolResults, VK_NULL_HANDLE, VK_NULL_HANDLE, firstQuery, queryCount, dataSize, data, stride, flags);

    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkResetQueryPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t queryPoolId;
    uint32_t firstQuery;
    uint32_t queryCount;

    VT_REQUEST_DECODE(vt_unserialize_vkResetQueryPool((VkDevice)&deviceId, (VkQueryPool)&queryPoolId, &firstQuery, &queryCount, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkResetQueryPool(device, queryPool, firstQuery, queryCount);
}

void vt_handle_vkCreateBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkBufferCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateBuffer((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkBuffer buffer = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateBuffer(device, &createInfo, NULL, &buffer);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, buffer, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkBuffer, (VkBuffer)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t bufferId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyBuffer((VkDevice)&deviceId, (VkBuffer)&bufferId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkBuffer, buffer, bufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    if (context->textureDecoder) TextureDecoder_removeBoundBuffer(context->textureDecoder, buffer);
    vulkanWrapper.vkDestroyBuffer(device, buffer, NULL);
}

void vt_handle_vkCreateBufferView(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkBufferViewCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateBufferView((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkBufferView view = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateBufferView(device, &createInfo, NULL, &view);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_BUFFER_VIEW, VORTEK_HANDLE_ROLE_VULKAN, view, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkBufferView, (VkBufferView)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyBufferView(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t bufferViewId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyBufferView((VkDevice)&deviceId, (VkBufferView)&bufferViewId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkBufferView, bufferView, bufferViewId, VK_OBJECT_TYPE_BUFFER_VIEW, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyBufferView(device, bufferView, NULL);
}

void vt_handle_vkCreateImage(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkImageCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateImage((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkImage image = VK_NULL_HANDLE;
    VkResult result;
    if (context->textureDecoder && isCompressedFormat(createInfo.format)) {
        result = TextureDecoder_createImage(context->textureDecoder, device, &createInfo, &image);
        if (result == VK_SUCCESS) RingBuffer_setStatus(context->clientRing, RING_STATUS_WAIT);
    }
    else result = vulkanWrapper.vkCreateImage(device, &createInfo, NULL, &image);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, image, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkImage, (VkImage)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyImage(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t imageId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyImage((VkDevice)&deviceId, (VkImage)&imageId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkImage, image, imageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    if (context->textureDecoder && TextureDecoder_containsImage(context->textureDecoder, image)) {
        TextureDecoder_destroyImage(context->textureDecoder, device, image);
    }
    else vulkanWrapper.vkDestroyImage(device, image, NULL);
}

void vt_handle_vkGetImageSubresourceLayout(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t imageId;
    VkImageSubresource subresource = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetImageSubresourceLayout((VkDevice)&deviceId, (VkImage)&imageId, &subresource, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkImage, image, imageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkSubresourceLayout layout = {0};
    vulkanWrapper.vkGetImageSubresourceLayout(device, image, &subresource, &layout);

    VT_SERIALIZE_CMD(VkSubresourceLayout, &layout);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkCreateImageView(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkImageViewCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateImageView((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    if (context->textureDecoder && isCompressedFormat(createInfo.format)) {
        createInfo.format = DECOMPRESSED_FORMAT;
        createInfo.subresourceRange.levelCount = 1;
    }

    VkImageView view = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateImageView(device, &createInfo, NULL, &view);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_IMAGE_VIEW, VORTEK_HANDLE_ROLE_VULKAN, view, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkImageView, (VkImageView)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyImageView(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t imageViewId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyImageView((VkDevice)&deviceId, (VkImageView)&imageViewId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkImageView, imageView, imageViewId, VK_OBJECT_TYPE_IMAGE_VIEW, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyImageView(device, imageView, NULL);
}

void vt_handle_vkCreateShaderModule(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkShaderModuleCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateShaderModule((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    ShaderModule* shaderModule = NULL;
    VkResult result = ShaderInspector_createModule(context->shaderInspector, device, createInfo.pCode, createInfo.codeSize, &shaderModule);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_SHADER_MODULE, (VkShaderModule)shaderModule, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkShaderModule, (VkShaderModule)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyShaderModule(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t shaderModuleId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyShaderModule((VkDevice)&deviceId, (VkShaderModule)&shaderModuleId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(ShaderModule*, shaderModule, shaderModuleId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_SHADER_MODULE, true, true, true);

    destroyVkObject(VK_OBJECT_TYPE_SHADER_MODULE, device, shaderModule);
}

void vt_handle_vkCreatePipelineCache(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkPipelineCacheCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreatePipelineCache((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkPipelineCache pipelineCache = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreatePipelineCache(device, &createInfo, NULL, &pipelineCache);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_PIPELINE_CACHE, VORTEK_HANDLE_ROLE_VULKAN, pipelineCache, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkPipelineCache, (VkPipelineCache)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyPipelineCache(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t pipelineCacheId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyPipelineCache((VkDevice)&deviceId, (VkPipelineCache)&pipelineCacheId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkPipelineCache, pipelineCache, pipelineCacheId, VK_OBJECT_TYPE_PIPELINE_CACHE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyPipelineCache(device, pipelineCache, NULL);
}

void vt_handle_vkGetPipelineCacheData(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t pipelineCacheId;
    size_t dataSize;

    VT_REQUEST_DECODE(vt_unserialize_vkGetPipelineCacheData((VkDevice)&deviceId, (VkPipelineCache)&pipelineCacheId, &dataSize, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkPipelineCache, pipelineCache, pipelineCacheId, VK_OBJECT_TYPE_PIPELINE_CACHE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    void* data = dataSize > 0
            ? vt_request_output_alloc(&_vt_request, (size_t)dataSize) : NULL;
    if (dataSize > 0 && !data) {
        vt_request_response_abort(&_vt_request,
                vt_decode_error(&(VtDecodeCursor){.state = _vt_request.state}));
        return;
    }
    VkResult result = vulkanWrapper.vkGetPipelineCacheData(device, pipelineCache, &dataSize, data);

    VT_SERIALIZE_CMD(vkGetPipelineCacheData, VK_NULL_HANDLE, VK_NULL_HANDLE, &dataSize, data);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkMergePipelineCaches(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t dstCacheId;
    uint32_t srcCacheCount;

    VT_REQUEST_DECODE(vt_unserialize_vkMergePipelineCaches((VkDevice)&deviceId, (VkPipelineCache)&dstCacheId, &srcCacheCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkPipelineCache, dstCache, dstCacheId, VK_OBJECT_TYPE_PIPELINE_CACHE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkPipelineCache, srcCaches, srcCacheCount);
    VT_REQUEST_DECODE(vt_unserialize_vkMergePipelineCaches(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, srcCaches, &_vt_cursor, &context->memoryPool));

    VkResult result = vulkanWrapper.vkMergePipelineCaches(device, dstCache, srcCacheCount, srcCaches);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkCreateGraphicsPipelines(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId = 0;
    uint64_t pipelineCacheId = 0;
    uint32_t createInfoCount = 0;
    VT_REQUEST_DECODE(vt_unserialize_vkCreateGraphicsPipelines((VkDevice)&deviceId, (VkPipelineCache)&pipelineCacheId, &createInfoCount, NULL, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkPipelineCache, pipelineCache, pipelineCacheId, VK_OBJECT_TYPE_PIPELINE_CACHE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);
    if (!AsyncPipelineCreator_create(context, PIPELINE_TYPE_GRAPHICS,
            &_vt_request, deviceId, pipelineCacheId, createInfoCount,
            device, pipelineCache)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
}

void vt_handle_vkCreateComputePipelines(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId = 0;
    uint64_t pipelineCacheId = 0;
    uint32_t createInfoCount = 0;
    VT_REQUEST_DECODE(vt_unserialize_vkCreateComputePipelines((VkDevice)&deviceId, (VkPipelineCache)&pipelineCacheId, &createInfoCount, NULL, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkPipelineCache, pipelineCache, pipelineCacheId, VK_OBJECT_TYPE_PIPELINE_CACHE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);
    if (!AsyncPipelineCreator_create(context, PIPELINE_TYPE_COMPUTE,
            &_vt_request, deviceId, pipelineCacheId, createInfoCount,
            device, pipelineCache)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
}

void vt_handle_vkDestroyPipeline(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t pipelineId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyPipeline((VkDevice)&deviceId, (VkPipeline)&pipelineId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkPipeline, pipeline, pipelineId, VK_OBJECT_TYPE_PIPELINE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyPipeline(device, pipeline, NULL);
}

void vt_handle_vkCreatePipelineLayout(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkPipelineLayoutCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreatePipelineLayout((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    if (createInfo.pPushConstantRanges) {
        for (int i = 0; i < createInfo.pushConstantRangeCount; i++) {
            VkPushConstantRange* pushConstantRange = (VkPushConstantRange*)&createInfo.pPushConstantRanges[i];
            if (pushConstantRange->stageFlags & VK_SHADER_STAGE_VERTEX_BIT) pushConstantRange->stageFlags |= VK_SHADER_STAGE_FRAGMENT_BIT;
        }
    }

    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreatePipelineLayout(device, &createInfo, NULL, &pipelineLayout);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_PIPELINE_LAYOUT, VORTEK_HANDLE_ROLE_VULKAN, pipelineLayout, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkPipelineLayout, (VkPipelineLayout)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyPipelineLayout(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t pipelineLayoutId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyPipelineLayout((VkDevice)&deviceId, (VkPipelineLayout)&pipelineLayoutId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkPipelineLayout, pipelineLayout, pipelineLayoutId, VK_OBJECT_TYPE_PIPELINE_LAYOUT, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyPipelineLayout(device, pipelineLayout, NULL);
}

void vt_handle_vkCreateSampler(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkSamplerCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateSampler((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkSampler sampler = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateSampler(device, &createInfo, NULL, &sampler);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_SAMPLER, VORTEK_HANDLE_ROLE_VULKAN, sampler, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkSampler, (VkSampler)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroySampler(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t samplerId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroySampler((VkDevice)&deviceId, (VkSampler)&samplerId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkSampler, sampler, samplerId, VK_OBJECT_TYPE_SAMPLER, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroySampler(device, sampler, NULL);
}

void vt_handle_vkCreateDescriptorSetLayout(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkDescriptorSetLayoutCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateDescriptorSetLayout((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateDescriptorSetLayout(device, &createInfo, NULL, &setLayout);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, VORTEK_HANDLE_ROLE_VULKAN, setLayout, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkDescriptorSetLayout, (VkDescriptorSetLayout)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyDescriptorSetLayout(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t descriptorSetLayoutId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyDescriptorSetLayout((VkDevice)&deviceId, (VkDescriptorSetLayout)&descriptorSetLayoutId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkDescriptorSetLayout, descriptorSetLayout, descriptorSetLayoutId, VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyDescriptorSetLayout(device, descriptorSetLayout, NULL);
}

void vt_handle_vkCreateDescriptorPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkDescriptorPoolCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateDescriptorPool((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateDescriptorPool(device, &createInfo, NULL, &descriptorPool);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_DESCRIPTOR_POOL, VORTEK_HANDLE_ROLE_VULKAN, descriptorPool, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkDescriptorPool, (VkDescriptorPool)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyDescriptorPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t descriptorPoolId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyDescriptorPool((VkDevice)&deviceId, (VkDescriptorPool)&descriptorPoolId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkDescriptorPool, descriptorPool, descriptorPoolId, VK_OBJECT_TYPE_DESCRIPTOR_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    VT_REQUEST_AUTHORITY(vt_request_tombstone_children(
            &_vt_request, descriptorPoolId));
    vulkanWrapper.vkDestroyDescriptorPool(device, descriptorPool, NULL);
}

void vt_handle_vkResetDescriptorPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t descriptorPoolId;
    VkDescriptorPoolResetFlags flags;

    VT_REQUEST_DECODE(vt_unserialize_vkResetDescriptorPool((VkDevice)&deviceId, (VkDescriptorPool)&descriptorPoolId, &flags, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkDescriptorPool, descriptorPool, descriptorPoolId, VK_OBJECT_TYPE_DESCRIPTOR_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkResult result = vulkanWrapper.vkResetDescriptorPool(
            device, descriptorPool, flags);
    if (result == VK_SUCCESS)
        VT_REQUEST_AUTHORITY(vt_request_tombstone_children(
                &_vt_request, descriptorPoolId));
}

void vt_handle_vkAllocateDescriptorSets(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkDescriptorSetAllocateInfo allocateInfo = {0};
    uint64_t descriptorPoolToken = 0;

    VT_REQUEST_AUTHORITY(vt_request_capture_begin(&_vt_request,
            VK_OBJECT_TYPE_DESCRIPTOR_POOL, &descriptorPoolToken, 1));
    VT_REQUEST_DECODE(vt_unserialize_vkAllocateDescriptorSets((VkDevice)&deviceId, &allocateInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_AUTHORITY(vt_request_capture_complete(&_vt_request, 1));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_ARRAY(VkDescriptorSet, descriptorSets, allocateInfo.descriptorSetCount);
    const size_t descriptorResponseSize =
            (size_t)allocateInfo.descriptorSetCount * VK_HANDLE_BYTE_COUNT;
    if (!vt_transport_size_fits(descriptorResponseSize,
            context->clientRing ? context->clientRing->bufferSize : 0u,
            CLIENT_RING_BUFFER_SIZE - HEADER_SIZE)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_LIMIT);
        return;
    }
    VkResult result = vulkanWrapper.vkAllocateDescriptorSets(device, &allocateInfo, descriptorSets);

    int bufferSize = (int)((size_t)allocateInfo.descriptorSetCount * VK_HANDLE_BYTE_COUNT);
    if (result == VK_SUCCESS && !vt_request_publish_vulkan_batch(&_vt_request,
            VK_OBJECT_TYPE_DESCRIPTOR_SET, descriptorSets,
            sizeof(*descriptorSets), allocateInfo.descriptorSetCount,
            _vt_request.state->instance_owner, deviceId, descriptorPoolToken,
            VT_REQUEST_PUBLICATION_DESCRIPTOR_SETS,
            (uint64_t)(uintptr_t)device,
            (uint64_t)(uintptr_t)allocateInfo.descriptorPool)) {
        (void)vulkanWrapper.vkFreeDescriptorSets(device, allocateInfo.descriptorPool,
                allocateInfo.descriptorSetCount, descriptorSets);
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
    char* outputBuffer = bufferSize > 0
            ? vt_request_output_alloc(&_vt_request, (size_t)bufferSize) : NULL;
    if (bufferSize > 0 && !outputBuffer) {
        vt_request_response_abort(&_vt_request,
                vt_decode_error(&(VtDecodeCursor){.state = _vt_request.state}));
        return;
    }
    if (result != VK_SUCCESS && descriptorSets)
        memset(descriptorSets, 0,
                allocateInfo.descriptorSetCount * sizeof(*descriptorSets));
    for (uint32_t i = 0; i < allocateInfo.descriptorSetCount; i++) {
        vt_serialize_VkDescriptorSet(descriptorSets[i],
                outputBuffer + (size_t)i * VK_HANDLE_BYTE_COUNT);
    }
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkFreeDescriptorSets(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t descriptorPoolId;
    uint32_t descriptorSetCount;

    VT_REQUEST_DECODE(vt_unserialize_vkFreeDescriptorSets((VkDevice)&deviceId, (VkDescriptorPool)&descriptorPoolId, &descriptorSetCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkDescriptorPool, descriptorPool, descriptorPoolId, VK_OBJECT_TYPE_DESCRIPTOR_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkDescriptorSet, descriptorSets, descriptorSetCount);
    VT_REQUEST_ARRAY(uint64_t, descriptorSetTokens, descriptorSetCount);
    VT_REQUEST_AUTHORITY(vt_request_capture_begin(&_vt_request,
            VK_OBJECT_TYPE_DESCRIPTOR_SET, descriptorSetTokens,
            descriptorSetCount));
    VT_REQUEST_DECODE(vt_unserialize_vkFreeDescriptorSets(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, descriptorSets, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_AUTHORITY(vt_request_capture_complete(
            &_vt_request, descriptorSetCount));
    VT_REQUEST_AUTHORITY(vt_request_validate_batch(&_vt_request,
            descriptorSetTokens, descriptorSetCount,
            VK_OBJECT_TYPE_DESCRIPTOR_SET, descriptorPoolId));

    VkResult result = vulkanWrapper.vkFreeDescriptorSets(
            device, descriptorPool, descriptorSetCount, descriptorSets);
    if (result == VK_SUCCESS)
        VT_REQUEST_AUTHORITY(vt_request_tombstone_batch(&_vt_request,
                descriptorSetTokens, descriptorSetCount,
                VK_OBJECT_TYPE_DESCRIPTOR_SET, descriptorPoolId));
}

void vt_handle_vkUpdateDescriptorSets(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint32_t descriptorWriteCount;
    uint32_t descriptorCopyCount;

    VT_REQUEST_DECODE(vt_unserialize_vkUpdateDescriptorSets((VkDevice)&deviceId, &descriptorWriteCount, NULL, &descriptorCopyCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_ARRAY(VkWriteDescriptorSet, descriptorWrites, descriptorWriteCount);
    VT_REQUEST_ARRAY(VkCopyDescriptorSet, descriptorCopies, descriptorCopyCount);
    VT_REQUEST_DECODE(vt_unserialize_vkUpdateDescriptorSets(VK_NULL_HANDLE, NULL, descriptorWrites, NULL, descriptorCopies, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkUpdateDescriptorSets(device, descriptorWriteCount, descriptorWrites, descriptorCopyCount, descriptorCopies);
}

void vt_handle_vkCreateFramebuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkFramebufferCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateFramebuffer((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateFramebuffer(device, &createInfo, NULL, &framebuffer);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_FRAMEBUFFER, VORTEK_HANDLE_ROLE_VULKAN, framebuffer, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkFramebuffer, (VkFramebuffer)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyFramebuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t framebufferId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyFramebuffer((VkDevice)&deviceId, (VkFramebuffer)&framebufferId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkFramebuffer, framebuffer, framebufferId, VK_OBJECT_TYPE_FRAMEBUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyFramebuffer(device, framebuffer, NULL);
}

void vt_handle_vkCreateRenderPass(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkRenderPassCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateRenderPass((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateRenderPass(device, &createInfo, NULL, &renderPass);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_RENDER_PASS, VORTEK_HANDLE_ROLE_VULKAN, renderPass, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkRenderPass, (VkRenderPass)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyRenderPass(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t renderPassId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyRenderPass((VkDevice)&deviceId, (VkRenderPass)&renderPassId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkRenderPass, renderPass, renderPassId, VK_OBJECT_TYPE_RENDER_PASS, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroyRenderPass(device, renderPass, NULL);
}

void vt_handle_vkGetRenderAreaGranularity(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t renderPassId;

    VT_REQUEST_DECODE(vt_unserialize_vkGetRenderAreaGranularity((VkDevice)&deviceId, (VkRenderPass)&renderPassId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkRenderPass, renderPass, renderPassId, VK_OBJECT_TYPE_RENDER_PASS, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkExtent2D granularity = {0};
    vulkanWrapper.vkGetRenderAreaGranularity(device, renderPass, &granularity);

    VT_SERIALIZE_CMD(VkExtent2D, &granularity);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkCreateCommandPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkCommandPoolCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateCommandPool((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateCommandPool(device, &createInfo, NULL, &commandPool);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_COMMAND_POOL, VORTEK_HANDLE_ROLE_VULKAN, commandPool, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkCommandPool, (VkCommandPool)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroyCommandPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t commandPoolId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroyCommandPool((VkDevice)&deviceId, (VkCommandPool)&commandPoolId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkCommandPool, commandPool, commandPoolId, VK_OBJECT_TYPE_COMMAND_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    VT_REQUEST_AUTHORITY(vt_request_tombstone_children(
            &_vt_request, commandPoolId));
    vulkanWrapper.vkDestroyCommandPool(device, commandPool, NULL);
}

void vt_handle_vkResetCommandPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t commandPoolId;
    VkCommandPoolResetFlags flags;

    VT_REQUEST_DECODE(vt_unserialize_vkResetCommandPool((VkDevice)&deviceId, (VkCommandPool)&commandPoolId, &flags, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkCommandPool, commandPool, commandPoolId, VK_OBJECT_TYPE_COMMAND_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VkResult result = vulkanWrapper.vkResetCommandPool(
            device, commandPool, flags);
    if (result == VK_SUCCESS)
        VT_REQUEST_AUTHORITY(vt_request_tombstone_children(
                &_vt_request, commandPoolId));
}

void vt_handle_vkAllocateCommandBuffers(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkCommandBufferAllocateInfo allocateInfo = {0};
    uint64_t commandPoolToken = 0;

    VT_REQUEST_AUTHORITY(vt_request_capture_begin(&_vt_request,
            VK_OBJECT_TYPE_COMMAND_POOL, &commandPoolToken, 1));
    VT_REQUEST_DECODE(vt_unserialize_vkAllocateCommandBuffers((VkDevice)&deviceId, &allocateInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_AUTHORITY(vt_request_capture_complete(&_vt_request, 1));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_ARRAY(VkCommandBuffer, commandBuffers, allocateInfo.commandBufferCount);
    VkResult result = vulkanWrapper.vkAllocateCommandBuffers(device, &allocateInfo, commandBuffers);

    if (result == VK_SUCCESS && !vt_request_publish_vulkan_batch(&_vt_request,
            VK_OBJECT_TYPE_COMMAND_BUFFER, commandBuffers,
            sizeof(*commandBuffers), allocateInfo.commandBufferCount,
            _vt_request.state->instance_owner, deviceId, commandPoolToken,
            VT_REQUEST_PUBLICATION_COMMAND_BUFFERS,
            (uint64_t)(uintptr_t)device,
            (uint64_t)(uintptr_t)allocateInfo.commandPool)) {
        vulkanWrapper.vkFreeCommandBuffers(device, allocateInfo.commandPool,
                allocateInfo.commandBufferCount, commandBuffers);
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
    if (result != VK_SUCCESS && commandBuffers)
        memset(commandBuffers, 0,
                allocateInfo.commandBufferCount * sizeof(*commandBuffers));
    VT_SERIALIZE_CMD(vkAllocateCommandBuffers, VK_NULL_HANDLE, &allocateInfo, commandBuffers);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkFreeCommandBuffers(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t commandPoolId;
    uint32_t commandBufferCount;

    VT_REQUEST_DECODE(vt_unserialize_vkFreeCommandBuffers((VkDevice)&deviceId, (VkCommandPool)&commandPoolId, &commandBufferCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkCommandPool, commandPool, commandPoolId, VK_OBJECT_TYPE_COMMAND_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkCommandBuffer, commandBuffers, commandBufferCount);
    VT_REQUEST_ARRAY(uint64_t, commandBufferTokens, commandBufferCount);
    VT_REQUEST_AUTHORITY(vt_request_capture_begin(&_vt_request,
            VK_OBJECT_TYPE_COMMAND_BUFFER, commandBufferTokens,
            commandBufferCount));
    VT_REQUEST_DECODE(vt_unserialize_vkFreeCommandBuffers(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, commandBuffers, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_AUTHORITY(vt_request_capture_complete(
            &_vt_request, commandBufferCount));
    VT_REQUEST_AUTHORITY(vt_request_tombstone_batch(&_vt_request,
            commandBufferTokens, commandBufferCount,
            VK_OBJECT_TYPE_COMMAND_BUFFER, commandPoolId));

    vulkanWrapper.vkFreeCommandBuffers(
            device, commandPool, commandBufferCount, commandBuffers);
}

void vt_handle_vkBeginCommandBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkCommandBufferBeginInfo beginInfo = {0};
    VT_REQUEST_DECODE(vt_unserialize_vkBeginCommandBuffer((VkCommandBuffer)&commandBufferId, &beginInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkBeginCommandBuffer(commandBuffer, &beginInfo);
}

static bool vt_request_preflight_command(
        HandleRequestFunc handler, VtRequestDecode* request) {
    VtDecodeCursor _vt_cursor;
    if (!vt_request_decode_pass_begin(request, &_vt_cursor)) return false;
    if (handler == vt_handle_vkCmdBeginConditionalRenderingEXT) {
        return vt_unserialize_vkCmdBeginConditionalRenderingEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBeginQuery) {
        return vt_unserialize_vkCmdBeginQuery(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBeginQueryIndexedEXT) {
        return vt_unserialize_vkCmdBeginQueryIndexedEXT(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBeginRenderPass) {
        return vt_unserialize_vkCmdBeginRenderPass(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBeginRenderPass2) {
        return vt_unserialize_vkCmdBeginRenderPass2(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBeginRendering) {
        return vt_unserialize_vkCmdBeginRendering(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBeginTransformFeedbackEXT) {
        return vt_unserialize_vkCmdBeginTransformFeedbackEXT(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBindDescriptorSets) {
        return vt_unserialize_vkCmdBindDescriptorSets(0, 0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBindIndexBuffer) {
        return vt_unserialize_vkCmdBindIndexBuffer(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBindPipeline) {
        return vt_unserialize_vkCmdBindPipeline(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBindTransformFeedbackBuffersEXT) {
        return vt_unserialize_vkCmdBindTransformFeedbackBuffersEXT(0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBindVertexBuffers) {
        return vt_unserialize_vkCmdBindVertexBuffers(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBindVertexBuffers2) {
        return vt_unserialize_vkCmdBindVertexBuffers2(0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBlitImage) {
        return vt_unserialize_vkCmdBlitImage(0, 0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdBlitImage2) {
        return vt_unserialize_vkCmdBlitImage2(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdClearAttachments) {
        return vt_unserialize_vkCmdClearAttachments(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdClearColorImage) {
        return vt_unserialize_vkCmdClearColorImage(0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdClearDepthStencilImage) {
        return vt_unserialize_vkCmdClearDepthStencilImage(0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdCopyBuffer) {
        return vt_unserialize_vkCmdCopyBuffer(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdCopyBuffer2) {
        return vt_unserialize_vkCmdCopyBuffer2(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdCopyBufferToImage) {
        return vt_unserialize_vkCmdCopyBufferToImage(0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdCopyBufferToImage2) {
        return vt_unserialize_vkCmdCopyBufferToImage2(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdCopyImage) {
        return vt_unserialize_vkCmdCopyImage(0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdCopyImage2) {
        return vt_unserialize_vkCmdCopyImage2(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdCopyImageToBuffer) {
        return vt_unserialize_vkCmdCopyImageToBuffer(0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdCopyImageToBuffer2) {
        return vt_unserialize_vkCmdCopyImageToBuffer2(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdCopyQueryPoolResults) {
        return vt_unserialize_vkCmdCopyQueryPoolResults(0, 0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdDispatch) {
        return vt_unserialize_vkCmdDispatch(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdDispatchBase) {
        return vt_unserialize_vkCmdDispatchBase(0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdDispatchIndirect) {
        return vt_unserialize_vkCmdDispatchIndirect(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdDraw) {
        return vt_unserialize_vkCmdDraw(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdDrawIndexed) {
        return vt_unserialize_vkCmdDrawIndexed(0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdDrawIndexedIndirect) {
        return vt_unserialize_vkCmdDrawIndexedIndirect(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdDrawIndexedIndirectCount) {
        return vt_unserialize_vkCmdDrawIndexedIndirectCount(0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdDrawIndirect) {
        return vt_unserialize_vkCmdDrawIndirect(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdDrawIndirectByteCountEXT) {
        return vt_unserialize_vkCmdDrawIndirectByteCountEXT(0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdDrawIndirectCount) {
        return vt_unserialize_vkCmdDrawIndirectCount(0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdEndConditionalRenderingEXT) {
        return vt_unserialize_vkCmdEndConditionalRenderingEXT(0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdEndQuery) {
        return vt_unserialize_vkCmdEndQuery(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdEndQueryIndexedEXT) {
        return vt_unserialize_vkCmdEndQueryIndexedEXT(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdEndRenderPass) {
        return vt_unserialize_vkCmdEndRenderPass(0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdEndRenderPass2) {
        return vt_unserialize_vkCmdEndRenderPass2(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdEndRendering) {
        return vt_unserialize_vkCmdEndRendering(0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdEndTransformFeedbackEXT) {
        return vt_unserialize_vkCmdEndTransformFeedbackEXT(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdExecuteCommands) {
        return vt_unserialize_vkCmdExecuteCommands(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdFillBuffer) {
        return vt_unserialize_vkCmdFillBuffer(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdNextSubpass) {
        return vt_unserialize_vkCmdNextSubpass(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdNextSubpass2) {
        return vt_unserialize_vkCmdNextSubpass2(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdPipelineBarrier) {
        return vt_unserialize_vkCmdPipelineBarrier(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdPipelineBarrier2) {
        return vt_unserialize_vkCmdPipelineBarrier2(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdPushConstants) {
        return vt_unserialize_vkCmdPushConstants(0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdPushDescriptorSetKHR) {
        return vt_unserialize_vkCmdPushDescriptorSetKHR(0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdResetEvent) {
        return vt_unserialize_vkCmdResetEvent(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdResetEvent2) {
        return vt_unserialize_vkCmdResetEvent2(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdResetQueryPool) {
        return vt_unserialize_vkCmdResetQueryPool(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdResolveImage) {
        return vt_unserialize_vkCmdResolveImage(0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdResolveImage2) {
        return vt_unserialize_vkCmdResolveImage2(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetAlphaToCoverageEnableEXT) {
        return vt_unserialize_vkCmdSetAlphaToCoverageEnableEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetAlphaToOneEnableEXT) {
        return vt_unserialize_vkCmdSetAlphaToOneEnableEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetBlendConstants) {
        return vt_unserialize_vkCmdSetBlendConstants(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetColorBlendAdvancedEXT) {
        return vt_unserialize_vkCmdSetColorBlendAdvancedEXT(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetColorBlendEnableEXT) {
        return vt_unserialize_vkCmdSetColorBlendEnableEXT(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetColorBlendEquationEXT) {
        return vt_unserialize_vkCmdSetColorBlendEquationEXT(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetColorWriteEnableEXT) {
        return vt_unserialize_vkCmdSetColorWriteEnableEXT(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetColorWriteMaskEXT) {
        return vt_unserialize_vkCmdSetColorWriteMaskEXT(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetConservativeRasterizationModeEXT) {
        return vt_unserialize_vkCmdSetConservativeRasterizationModeEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetCullMode) {
        return vt_unserialize_vkCmdSetCullMode(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDepthBias) {
        return vt_unserialize_vkCmdSetDepthBias(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDepthBiasEnable) {
        return vt_unserialize_vkCmdSetDepthBiasEnable(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDepthBounds) {
        return vt_unserialize_vkCmdSetDepthBounds(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDepthBoundsTestEnable) {
        return vt_unserialize_vkCmdSetDepthBoundsTestEnable(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDepthClampEnableEXT) {
        return vt_unserialize_vkCmdSetDepthClampEnableEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDepthClipEnableEXT) {
        return vt_unserialize_vkCmdSetDepthClipEnableEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDepthClipNegativeOneToOneEXT) {
        return vt_unserialize_vkCmdSetDepthClipNegativeOneToOneEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDepthCompareOp) {
        return vt_unserialize_vkCmdSetDepthCompareOp(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDepthTestEnable) {
        return vt_unserialize_vkCmdSetDepthTestEnable(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDepthWriteEnable) {
        return vt_unserialize_vkCmdSetDepthWriteEnable(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetDeviceMask) {
        return vt_unserialize_vkCmdSetDeviceMask(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetEvent) {
        return vt_unserialize_vkCmdSetEvent(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetEvent2) {
        return vt_unserialize_vkCmdSetEvent2(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetExtraPrimitiveOverestimationSizeEXT) {
        return vt_unserialize_vkCmdSetExtraPrimitiveOverestimationSizeEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetFrontFace) {
        return vt_unserialize_vkCmdSetFrontFace(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetLineRasterizationModeEXT) {
        return vt_unserialize_vkCmdSetLineRasterizationModeEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetLineStippleEnableEXT) {
        return vt_unserialize_vkCmdSetLineStippleEnableEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetLineStippleKHR) {
        return vt_unserialize_vkCmdSetLineStippleKHR(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetLineWidth) {
        return vt_unserialize_vkCmdSetLineWidth(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetLogicOpEnableEXT) {
        return vt_unserialize_vkCmdSetLogicOpEnableEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetPolygonModeEXT) {
        return vt_unserialize_vkCmdSetPolygonModeEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetPrimitiveRestartEnable) {
        return vt_unserialize_vkCmdSetPrimitiveRestartEnable(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetPrimitiveTopology) {
        return vt_unserialize_vkCmdSetPrimitiveTopology(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetProvokingVertexModeEXT) {
        return vt_unserialize_vkCmdSetProvokingVertexModeEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetRasterizationSamplesEXT) {
        return vt_unserialize_vkCmdSetRasterizationSamplesEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetRasterizationStreamEXT) {
        return vt_unserialize_vkCmdSetRasterizationStreamEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetRasterizerDiscardEnable) {
        return vt_unserialize_vkCmdSetRasterizerDiscardEnable(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetSampleLocationsEXT) {
        return vt_unserialize_vkCmdSetSampleLocationsEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetSampleLocationsEnableEXT) {
        return vt_unserialize_vkCmdSetSampleLocationsEnableEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetSampleMaskEXT) {
        return vt_unserialize_vkCmdSetSampleMaskEXT(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetScissor) {
        return vt_unserialize_vkCmdSetScissor(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetScissorWithCount) {
        return vt_unserialize_vkCmdSetScissorWithCount(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetStencilCompareMask) {
        return vt_unserialize_vkCmdSetStencilCompareMask(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetStencilOp) {
        return vt_unserialize_vkCmdSetStencilOp(0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetStencilReference) {
        return vt_unserialize_vkCmdSetStencilReference(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetStencilTestEnable) {
        return vt_unserialize_vkCmdSetStencilTestEnable(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetStencilWriteMask) {
        return vt_unserialize_vkCmdSetStencilWriteMask(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetTessellationDomainOriginEXT) {
        return vt_unserialize_vkCmdSetTessellationDomainOriginEXT(0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetViewport) {
        return vt_unserialize_vkCmdSetViewport(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdSetViewportWithCount) {
        return vt_unserialize_vkCmdSetViewportWithCount(0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdUpdateBuffer) {
        return vt_unserialize_vkCmdUpdateBuffer(0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdWaitEvents) {
        return vt_unserialize_vkCmdWaitEvents(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdWaitEvents2) {
        return vt_unserialize_vkCmdWaitEvents2(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdWriteTimestamp) {
        return vt_unserialize_vkCmdWriteTimestamp(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    if (handler == vt_handle_vkCmdWriteTimestamp2) {
        return vt_unserialize_vkCmdWriteTimestamp2(0, 0, 0, 0, &_vt_cursor, request->memoryPool) &&
                vt_decode_finished(&_vt_cursor);
    }
    return false;
}
void vt_handle_vkEndCommandBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    const uint8_t* inputBuffer = (const uint8_t*)context->inputBuffer;
    size_t inputSize = (size_t)context->inputBufferSize;
    if (inputSize < VK_HANDLE_BYTE_COUNT) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_TRUNCATED);
        return;
    }

    uint64_t commandBufferId = 0;
    memcpy(&commandBufferId, inputBuffer, sizeof(commandBufferId));
    size_t position = VK_HANDLE_BYTE_COUNT;

    /* Pass one validates every frame and fully decodes every payload. */
    VtRequestBatchChunk chunkInfo;
    VtRequestBatchStep step;
    while ((step = vt_request_batch_next(inputBuffer, inputSize, &position, &chunkInfo))
            == VT_REQUEST_BATCH_CHUNK) {
        HandleRequestFunc handler = getHandleRequestFunc((short)chunkInfo.requestCode);
        VtRequestDecode chunk = _vt_request;
        chunk.data = chunkInfo.data;
        chunk.size = chunkInfo.size;
        if (!handler || !vt_request_preflight_command(handler, &chunk)) {
            vt_request_protocol_error(context, handler
                    ? vt_decode_error(&(VtDecodeCursor){.state = chunk.state})
                    : VT_DECODE_ERROR_ARGUMENT);
            return;
        }
    }
    if (step != VT_REQUEST_BATCH_DONE) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_TRUNCATED);
        return;
    }

    /* Pass two is unreachable until every command payload is known-good. */
    VtRequestDecode* previousBatch = vt_active_batch_request;
    vt_active_batch_request = &_vt_request;
    char* savedBuffer = context->inputBuffer;
    int savedSize = context->inputBufferSize;
    position = VK_HANDLE_BYTE_COUNT;
    while (position < inputSize && !VkContext_isClosing(context)) {
        int32_t requestCode = 0;
        int32_t payloadSize = 0;
        memcpy(&requestCode, inputBuffer + position, sizeof(requestCode));
        memcpy(&payloadSize, inputBuffer + position + sizeof(requestCode), sizeof(payloadSize));
        context->inputBuffer = (char*)inputBuffer + position + HEADER_SIZE;
        context->inputBufferSize = payloadSize;
        getHandleRequestFunc((short)requestCode)(context);
        position += HEADER_SIZE + (size_t)payloadSize;
    }
    context->inputBuffer = savedBuffer;
    context->inputBufferSize = savedSize;
    vt_active_batch_request = previousBatch;
    if (VkContext_isClosing(context)) return;

    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    vulkanWrapper.vkEndCommandBuffer(commandBuffer);
}

void vt_handle_vkResetCommandBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkCommandBufferResetFlags flags;

    VT_REQUEST_DECODE(vt_unserialize_vkResetCommandBuffer((VkCommandBuffer)&commandBufferId, &flags, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkResetCommandBuffer(commandBuffer, flags);
}

void vt_handle_vkCmdBindPipeline(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkPipelineBindPoint pipelineBindPoint;
    uint64_t pipelineId;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBindPipeline((VkCommandBuffer)&commandBufferId, &pipelineBindPoint, (VkPipeline)&pipelineId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkPipeline, pipeline, pipelineId, VK_OBJECT_TYPE_PIPELINE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdBindPipeline(commandBuffer, pipelineBindPoint, pipeline);
}

void vt_handle_vkCmdSetViewport(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstViewport;
    uint32_t viewportCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetViewport((VkCommandBuffer)&commandBufferId, &firstViewport, &viewportCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkViewport, viewports, viewportCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetViewport(VK_NULL_HANDLE, NULL, NULL, viewports, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetViewport(commandBuffer, firstViewport, viewportCount, viewports);
}

void vt_handle_vkCmdSetScissor(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstScissor;
    uint32_t scissorCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetScissor((VkCommandBuffer)&commandBufferId, &firstScissor, &scissorCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkRect2D, scissors, scissorCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetScissor(VK_NULL_HANDLE, NULL, NULL, scissors, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetScissor(commandBuffer, firstScissor, scissorCount, scissors);
}

void vt_handle_vkCmdSetLineWidth(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    float lineWidth;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetLineWidth((VkCommandBuffer)&commandBufferId, &lineWidth, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetLineWidth(commandBuffer, lineWidth);
}

void vt_handle_vkCmdSetDepthBias(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    float depthBiasConstantFactor;
    float depthBiasClamp;
    float depthBiasSlopeFactor;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDepthBias((VkCommandBuffer)&commandBufferId, &depthBiasConstantFactor, &depthBiasClamp, &depthBiasSlopeFactor, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDepthBias(commandBuffer, depthBiasConstantFactor, depthBiasClamp, depthBiasSlopeFactor);
}

void vt_handle_vkCmdSetBlendConstants(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetBlendConstants((VkCommandBuffer)&commandBufferId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    float blendConstants[4];
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetBlendConstants(VK_NULL_HANDLE, blendConstants, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetBlendConstants(commandBuffer, blendConstants);
}

void vt_handle_vkCmdSetDepthBounds(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    float minDepthBounds;
    float maxDepthBounds;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDepthBounds((VkCommandBuffer)&commandBufferId, &minDepthBounds, &maxDepthBounds, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDepthBounds(commandBuffer, minDepthBounds, maxDepthBounds);
}

void vt_handle_vkCmdSetStencilCompareMask(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkStencilFaceFlags faceMask;
    uint32_t compareMask;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetStencilCompareMask((VkCommandBuffer)&commandBufferId, &faceMask, &compareMask, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetStencilCompareMask(commandBuffer, faceMask, compareMask);
}

void vt_handle_vkCmdSetStencilWriteMask(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkStencilFaceFlags faceMask;
    uint32_t writeMask;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetStencilWriteMask((VkCommandBuffer)&commandBufferId, &faceMask, &writeMask, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetStencilWriteMask(commandBuffer, faceMask, writeMask);
}

void vt_handle_vkCmdSetStencilReference(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkStencilFaceFlags faceMask;
    uint32_t reference;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetStencilReference((VkCommandBuffer)&commandBufferId, &faceMask, &reference, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetStencilReference(commandBuffer, faceMask, reference);
}

void vt_handle_vkCmdBindDescriptorSets(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkPipelineBindPoint pipelineBindPoint;
    uint64_t layoutId;
    uint32_t firstSet;
    uint32_t descriptorSetCount;
    uint32_t dynamicOffsetCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBindDescriptorSets((VkCommandBuffer)&commandBufferId, &pipelineBindPoint, (VkPipelineLayout)&layoutId, &firstSet, &descriptorSetCount, VK_NULL_HANDLE, &dynamicOffsetCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkPipelineLayout, layout, layoutId, VK_OBJECT_TYPE_PIPELINE_LAYOUT, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkDescriptorSet, descriptorSets, descriptorSetCount);
    VT_REQUEST_ARRAY(uint32_t, dynamicOffsets, dynamicOffsetCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdBindDescriptorSets(VK_NULL_HANDLE, NULL, VK_NULL_HANDLE, NULL, NULL, descriptorSets, NULL, dynamicOffsets, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdBindDescriptorSets(commandBuffer, pipelineBindPoint, layout, firstSet, descriptorSetCount, descriptorSets, dynamicOffsetCount, dynamicOffsets);
}

void vt_handle_vkCmdBindIndexBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t bufferId;
    VkDeviceSize offset;
    VkIndexType indexType;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBindIndexBuffer((VkCommandBuffer)&commandBufferId, (VkBuffer)&bufferId, &offset, &indexType, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, buffer, bufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdBindIndexBuffer(commandBuffer, buffer, offset, indexType);
}

void vt_handle_vkCmdBindVertexBuffers(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstBinding;
    uint32_t bindingCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBindVertexBuffers((VkCommandBuffer)&commandBufferId, &firstBinding, &bindingCount, VK_NULL_HANDLE, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkBuffer, buffers, bindingCount);
    VT_REQUEST_ARRAY(VkDeviceSize, offsets, bindingCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdBindVertexBuffers(VK_NULL_HANDLE, NULL, NULL, buffers, offsets, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdBindVertexBuffers(commandBuffer, firstBinding, bindingCount, buffers, offsets);
}

void vt_handle_vkCmdDraw(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t vertexCount;
    uint32_t instanceCount;
    uint32_t firstVertex;
    uint32_t firstInstance;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdDraw((VkCommandBuffer)&commandBufferId, &vertexCount, &instanceCount, &firstVertex, &firstInstance, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdDraw(commandBuffer, vertexCount, instanceCount, firstVertex, firstInstance);
}

void vt_handle_vkCmdDrawIndexed(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t indexCount;
    uint32_t instanceCount;
    uint32_t firstIndex;
    int32_t vertexOffset;
    uint32_t firstInstance;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdDrawIndexed((VkCommandBuffer)&commandBufferId, &indexCount, &instanceCount, &firstIndex, &vertexOffset, &firstInstance, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdDrawIndexed(commandBuffer, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
}

void vt_handle_vkCmdDrawIndirect(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t bufferId;
    VkDeviceSize offset;
    uint32_t drawCount;
    uint32_t stride;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdDrawIndirect((VkCommandBuffer)&commandBufferId, (VkBuffer)&bufferId, &offset, &drawCount, &stride, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, buffer, bufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdDrawIndirect(commandBuffer, buffer, offset, drawCount, stride);
}

void vt_handle_vkCmdDrawIndexedIndirect(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t bufferId;
    VkDeviceSize offset;
    uint32_t drawCount;
    uint32_t stride;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdDrawIndexedIndirect((VkCommandBuffer)&commandBufferId, (VkBuffer)&bufferId, &offset, &drawCount, &stride, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, buffer, bufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdDrawIndexedIndirect(commandBuffer, buffer, offset, drawCount, stride);
}

void vt_handle_vkCmdDispatch(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t groupCountX;
    uint32_t groupCountY;
    uint32_t groupCountZ;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdDispatch((VkCommandBuffer)&commandBufferId, &groupCountX, &groupCountY, &groupCountZ, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdDispatch(commandBuffer, groupCountX, groupCountY, groupCountZ);
}

void vt_handle_vkCmdDispatchIndirect(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t bufferId;
    VkDeviceSize offset;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdDispatchIndirect((VkCommandBuffer)&commandBufferId, (VkBuffer)&bufferId, &offset, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, buffer, bufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdDispatchIndirect(commandBuffer, buffer, offset);
}

void vt_handle_vkCmdCopyBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t srcBufferId;
    uint64_t dstBufferId;
    uint32_t regionCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyBuffer((VkCommandBuffer)&commandBufferId, (VkBuffer)&srcBufferId, (VkBuffer)&dstBufferId, &regionCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, srcBuffer, srcBufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, dstBuffer, dstBufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkBufferCopy, regions, regionCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyBuffer(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, regions, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, regionCount, regions);
}

void vt_handle_vkCmdCopyImage(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t srcImageId;
    VkImageLayout srcImageLayout;
    uint64_t dstImageId;
    VkImageLayout dstImageLayout;
    uint32_t regionCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyImage((VkCommandBuffer)&commandBufferId, (VkImage)&srcImageId, &srcImageLayout, (VkImage)&dstImageId, &dstImageLayout, &regionCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkImage, srcImage, srcImageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkImage, dstImage, dstImageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkImageCopy, regions, regionCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyImage(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, VK_NULL_HANDLE, NULL, NULL, regions, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdCopyImage(commandBuffer, srcImage, srcImageLayout, dstImage, dstImageLayout, regionCount, regions);
}

void vt_handle_vkCmdBlitImage(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t srcImageId;
    VkImageLayout srcImageLayout;
    uint64_t dstImageId;
    VkImageLayout dstImageLayout;
    uint32_t regionCount;
    VkFilter filter;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBlitImage((VkCommandBuffer)&commandBufferId, (VkImage)&srcImageId, &srcImageLayout, (VkImage)&dstImageId, &dstImageLayout, &regionCount, NULL, &filter, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkImage, srcImage, srcImageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkImage, dstImage, dstImageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkImageBlit, regions, regionCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdBlitImage(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, VK_NULL_HANDLE, NULL, NULL, regions, NULL, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdBlitImage(commandBuffer, srcImage, srcImageLayout, dstImage, dstImageLayout, regionCount, regions, filter);
}

void vt_handle_vkCmdCopyBufferToImage(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t srcBufferId;
    uint64_t dstImageId;
    VkImageLayout dstImageLayout;
    uint32_t regionCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyBufferToImage((VkCommandBuffer)&commandBufferId, (VkBuffer)&srcBufferId, (VkImage)&dstImageId, &dstImageLayout, &regionCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, srcBuffer, srcBufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkImage, dstImage, dstImageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkBufferImageCopy, regions, regionCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyBufferToImage(VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, NULL, regions, &_vt_cursor, &context->memoryPool));

    if (context->textureDecoder && TextureDecoder_containsImage(context->textureDecoder, dstImage)) {
        if (regions[0].imageSubresource.mipLevel > 0) return;
        TextureDecoder_copyBufferToImage(context->textureDecoder, commandBuffer, srcBuffer, dstImage, dstImageLayout, regions[0].bufferOffset);
    }
    else vulkanWrapper.vkCmdCopyBufferToImage(commandBuffer, srcBuffer, dstImage, dstImageLayout, regionCount, regions);
}

void vt_handle_vkCmdCopyImageToBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t srcImageId;
    VkImageLayout srcImageLayout;
    uint64_t dstBufferId;
    uint32_t regionCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyImageToBuffer((VkCommandBuffer)&commandBufferId, (VkImage)&srcImageId, &srcImageLayout, (VkBuffer)&dstBufferId, &regionCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkImage, srcImage, srcImageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, dstBuffer, dstBufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkBufferImageCopy, regions, regionCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyImageToBuffer(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, VK_NULL_HANDLE, NULL, regions, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdCopyImageToBuffer(commandBuffer, srcImage, srcImageLayout, dstBuffer, regionCount, regions);
}

void vt_handle_vkCmdUpdateBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t dstBufferId;
    VkDeviceSize dstOffset;
    VkDeviceSize dataSize;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdUpdateBuffer((VkCommandBuffer)&commandBufferId, (VkBuffer)&dstBufferId, &dstOffset, &dataSize, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, dstBuffer, dstBufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_BYTES(char, data, dataSize);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdUpdateBuffer(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, NULL, data, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdUpdateBuffer(commandBuffer, dstBuffer, dstOffset, dataSize, data);
}

void vt_handle_vkCmdFillBuffer(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t dstBufferId;
    VkDeviceSize dstOffset;
    VkDeviceSize size;
    uint32_t data;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdFillBuffer((VkCommandBuffer)&commandBufferId, (VkBuffer)&dstBufferId, &dstOffset, &size, &data, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, dstBuffer, dstBufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdFillBuffer(commandBuffer, dstBuffer, dstOffset, size, data);
}

void vt_handle_vkCmdClearColorImage(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t imageId;
    VkImageLayout imageLayout;
    VkClearColorValue color = {0};
    uint32_t rangeCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdClearColorImage((VkCommandBuffer)&commandBufferId, (VkImage)&imageId, &imageLayout, &color, &rangeCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkImage, image, imageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkImageSubresourceRange, ranges, rangeCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdClearColorImage(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, NULL, NULL, ranges, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdClearColorImage(commandBuffer, image, imageLayout, &color, rangeCount, ranges);
}

void vt_handle_vkCmdClearDepthStencilImage(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t imageId;
    VkImageLayout imageLayout;
    VkClearDepthStencilValue depthStencil = {0};
    uint32_t rangeCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdClearDepthStencilImage((VkCommandBuffer)&commandBufferId, (VkImage)&imageId, &imageLayout, &depthStencil, &rangeCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkImage, image, imageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkImageSubresourceRange, ranges, rangeCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdClearDepthStencilImage(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, NULL, NULL, ranges, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdClearDepthStencilImage(commandBuffer, image, imageLayout, &depthStencil, rangeCount, ranges);
}

void vt_handle_vkCmdClearAttachments(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t attachmentCount;
    uint32_t rectCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdClearAttachments((VkCommandBuffer)&commandBufferId, &attachmentCount, NULL, &rectCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkClearAttachment, attachments, attachmentCount);
    VT_REQUEST_ARRAY(VkClearRect, rects, rectCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdClearAttachments(VK_NULL_HANDLE, NULL, attachments, NULL, rects, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdClearAttachments(commandBuffer, attachmentCount, attachments, rectCount, rects);
}

void vt_handle_vkCmdResolveImage(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t srcImageId;
    VkImageLayout srcImageLayout;
    uint64_t dstImageId;
    VkImageLayout dstImageLayout;
    uint32_t regionCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdResolveImage((VkCommandBuffer)&commandBufferId, (VkImage)&srcImageId, &srcImageLayout, (VkImage)&dstImageId, &dstImageLayout, &regionCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkImage, srcImage, srcImageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkImage, dstImage, dstImageId, VK_OBJECT_TYPE_IMAGE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkImageResolve, regions, regionCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdResolveImage(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, VK_NULL_HANDLE, NULL, NULL, regions, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdResolveImage(commandBuffer, srcImage, srcImageLayout, dstImage, dstImageLayout, regionCount, regions);
}

void vt_handle_vkCmdSetEvent(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t eventId;
    VkPipelineStageFlags stageMask;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetEvent((VkCommandBuffer)&commandBufferId, (VkEvent)&eventId, &stageMask, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkEvent, event, eventId, VK_OBJECT_TYPE_EVENT, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetEvent(commandBuffer, event, stageMask);
}

void vt_handle_vkCmdResetEvent(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t eventId;
    VkPipelineStageFlags stageMask;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdResetEvent((VkCommandBuffer)&commandBufferId, (VkEvent)&eventId, &stageMask, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkEvent, event, eventId, VK_OBJECT_TYPE_EVENT, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdResetEvent(commandBuffer, event, stageMask);
}

void vt_handle_vkCmdWaitEvents(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t eventCount;
    VkPipelineStageFlags srcStageMask;
    VkPipelineStageFlags dstStageMask;
    uint32_t memoryBarrierCount;
    uint32_t bufferMemoryBarrierCount;
    uint32_t imageMemoryBarrierCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdWaitEvents((VkCommandBuffer)&commandBufferId, &eventCount, VK_NULL_HANDLE, &srcStageMask, &dstStageMask, &memoryBarrierCount, NULL, &bufferMemoryBarrierCount, NULL, &imageMemoryBarrierCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkEvent, events, eventCount);
    VT_REQUEST_ARRAY(VkMemoryBarrier, memoryBarriers, memoryBarrierCount);
    VT_REQUEST_ARRAY(VkBufferMemoryBarrier, bufferMemoryBarriers, bufferMemoryBarrierCount);
    VT_REQUEST_ARRAY(VkImageMemoryBarrier, imageMemoryBarriers, imageMemoryBarrierCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdWaitEvents(VK_NULL_HANDLE, NULL, events, NULL, NULL, NULL, memoryBarriers, NULL, bufferMemoryBarriers, NULL, imageMemoryBarriers, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdWaitEvents(commandBuffer, eventCount, events, srcStageMask, dstStageMask, memoryBarrierCount, memoryBarriers, bufferMemoryBarrierCount, bufferMemoryBarriers, imageMemoryBarrierCount, imageMemoryBarriers);
}

void vt_handle_vkCmdPipelineBarrier(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkPipelineStageFlags srcStageMask;
    VkPipelineStageFlags dstStageMask;
    VkDependencyFlags dependencyFlags;
    uint32_t memoryBarrierCount;
    uint32_t bufferMemoryBarrierCount;
    uint32_t imageMemoryBarrierCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdPipelineBarrier((VkCommandBuffer)&commandBufferId, &srcStageMask, &dstStageMask, &dependencyFlags, &memoryBarrierCount, NULL, &bufferMemoryBarrierCount, NULL, &imageMemoryBarrierCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkMemoryBarrier, memoryBarriers, memoryBarrierCount);
    VT_REQUEST_ARRAY(VkBufferMemoryBarrier, bufferMemoryBarriers, bufferMemoryBarrierCount);
    VT_REQUEST_ARRAY(VkImageMemoryBarrier, imageMemoryBarriers, imageMemoryBarrierCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdPipelineBarrier(VK_NULL_HANDLE, NULL, NULL, NULL, NULL, memoryBarriers, NULL, bufferMemoryBarriers, NULL, imageMemoryBarriers, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdPipelineBarrier(commandBuffer, srcStageMask, dstStageMask, dependencyFlags, memoryBarrierCount, memoryBarriers, bufferMemoryBarrierCount, bufferMemoryBarriers, imageMemoryBarrierCount, imageMemoryBarriers);
}

void vt_handle_vkCmdBeginQuery(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t queryPoolId;
    uint32_t query;
    VkQueryControlFlags flags;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBeginQuery((VkCommandBuffer)&commandBufferId, (VkQueryPool)&queryPoolId, &query, &flags, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdBeginQuery(commandBuffer, queryPool, query, flags);
}

void vt_handle_vkCmdEndQuery(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t queryPoolId;
    uint32_t query;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdEndQuery((VkCommandBuffer)&commandBufferId, (VkQueryPool)&queryPoolId, &query, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdEndQuery(commandBuffer, queryPool, query);
}

void vt_handle_vkCmdBeginConditionalRenderingEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkConditionalRenderingBeginInfoEXT conditionalRenderingBegin = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBeginConditionalRenderingEXT((VkCommandBuffer)&commandBufferId, &conditionalRenderingBegin, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdBeginConditionalRendering(commandBuffer, &conditionalRenderingBegin);
}

void vt_handle_vkCmdEndConditionalRenderingEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;

    VT_REQUEST_DECODE(vt_unserialize_VkCommandBuffer((VkCommandBuffer)&commandBufferId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdEndConditionalRendering(commandBuffer);
}

void vt_handle_vkCmdResetQueryPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t queryPoolId;
    uint32_t firstQuery;
    uint32_t queryCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdResetQueryPool((VkCommandBuffer)&commandBufferId, (VkQueryPool)&queryPoolId, &firstQuery, &queryCount, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdResetQueryPool(commandBuffer, queryPool, firstQuery, queryCount);
}

void vt_handle_vkCmdWriteTimestamp(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkPipelineStageFlagBits pipelineStage;
    uint64_t queryPoolId;
    uint32_t query;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdWriteTimestamp((VkCommandBuffer)&commandBufferId, &pipelineStage, (VkQueryPool)&queryPoolId, &query, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdWriteTimestamp(commandBuffer, pipelineStage, queryPool, query);
}

void vt_handle_vkCmdCopyQueryPoolResults(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t queryPoolId;
    uint32_t firstQuery;
    uint32_t queryCount;
    uint64_t dstBufferId;
    VkDeviceSize dstOffset;
    VkDeviceSize stride;
    VkQueryResultFlags flags;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyQueryPoolResults((VkCommandBuffer)&commandBufferId, (VkQueryPool)&queryPoolId, &firstQuery, &queryCount, (VkBuffer)&dstBufferId, &dstOffset, &stride, &flags, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, dstBuffer, dstBufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdCopyQueryPoolResults(commandBuffer, queryPool, firstQuery, queryCount, dstBuffer, dstOffset, stride, flags);
}

void vt_handle_vkCmdPushConstants(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t layoutId;
    VkShaderStageFlags stageFlags;
    uint32_t offset;
    uint32_t size;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdPushConstants((VkCommandBuffer)&commandBufferId, (VkPipelineLayout)&layoutId, &stageFlags, &offset, &size, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkPipelineLayout, layout, layoutId, VK_OBJECT_TYPE_PIPELINE_LAYOUT, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_BYTES(char, values, size);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdPushConstants(VK_NULL_HANDLE, VK_NULL_HANDLE, NULL, NULL, NULL, values, &_vt_cursor, &context->memoryPool));

    if (stageFlags & VK_SHADER_STAGE_VERTEX_BIT) stageFlags |= VK_SHADER_STAGE_FRAGMENT_BIT;
    vulkanWrapper.vkCmdPushConstants(commandBuffer, layout, stageFlags, offset, size, values);
}

void vt_handle_vkCmdBeginRenderPass(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkRenderPassBeginInfo renderPassBegin = {0};
    VkSubpassContents contents;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBeginRenderPass((VkCommandBuffer)&commandBufferId, &renderPassBegin, &contents, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdBeginRenderPass(commandBuffer, &renderPassBegin, contents);
}

void vt_handle_vkCmdNextSubpass(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkSubpassContents contents;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdNextSubpass((VkCommandBuffer)&commandBufferId, &contents, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdNextSubpass(commandBuffer, contents);
}

void vt_handle_vkCmdEndRenderPass(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;

    VT_REQUEST_DECODE(vt_unserialize_VkCommandBuffer((VkCommandBuffer)&commandBufferId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdEndRenderPass(commandBuffer);
}

void vt_handle_vkCmdExecuteCommands(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t commandBufferCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdExecuteCommands((VkCommandBuffer)&commandBufferId, &commandBufferCount, VK_NULL_HANDLE, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkCommandBuffer, commandBuffers, commandBufferCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdExecuteCommands(VK_NULL_HANDLE, NULL, commandBuffers, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdExecuteCommands(commandBuffer, commandBufferCount, commandBuffers);
}

void vt_handle_vkGetPhysicalDeviceSurfaceCapabilitiesKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    uint64_t windowId;
    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceSurfaceCapabilitiesKHR((VkPhysicalDevice)&physicalDeviceId, (VkSurfaceKHR)&windowId, NULL, &_vt_cursor, &context->memoryPool));

    VkExtent2D windowSize = {0};
    if (!getWindowExtent(&context->jmethods, context->contextGeneration,
            _vt_request.state->instance_owner, (int)windowId, &windowSize)) {
        VT_REQUEST_SEND(VK_ERROR_SURFACE_LOST_KHR, NULL, 0);
        return;
    }

    VkSurfaceCapabilitiesKHR surfaceCapabilities = {0};
    surfaceCapabilities.minImageCount = getSurfaceMinImageCount();
    surfaceCapabilities.maxImageCount = surfaceCapabilities.minImageCount == 1 ? 2 : 0;
    surfaceCapabilities.currentExtent = windowSize;
    surfaceCapabilities.minImageExtent = windowSize;
    surfaceCapabilities.maxImageExtent = windowSize;
    surfaceCapabilities.maxImageArrayLayers = 1;
    surfaceCapabilities.supportedTransforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    surfaceCapabilities.currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    surfaceCapabilities.supportedCompositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR |
                                                  VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
    surfaceCapabilities.supportedUsageFlags = VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                              VK_IMAGE_USAGE_SAMPLED_BIT |
                                              VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                              VK_IMAGE_USAGE_STORAGE_BIT |
                                              VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                              VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;

    VT_SERIALIZE_CMD(VkSurfaceCapabilitiesKHR, &surfaceCapabilities);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceSurfaceFormatsKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    uint64_t windowId;
    uint32_t surfaceFormatCount;
    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceSurfaceFormatsKHR((VkPhysicalDevice)&physicalDeviceId, (VkSurfaceKHR)&windowId, &surfaceFormatCount, NULL, &_vt_cursor, &context->memoryPool));

    const uint32_t guestCapacity = surfaceFormatCount;
    uint32_t serverActual = 0;
    VkSurfaceFormatKHR* supportedFormats = getSurfaceFormats(&serverActual);
    surfaceFormatCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, serverActual);
    VkResult result = vt_request_query_result(
            VK_SUCCESS, guestCapacity != 0, guestCapacity, serverActual);
    VkSurfaceFormatKHR* surfaceFormats = guestCapacity > 0
            ? supportedFormats : NULL;

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceSurfaceFormatsKHR,
            VK_NULL_HANDLE, NULL, &surfaceFormatCount, surfaceFormats);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceSurfacePresentModesKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    static VkPresentModeKHR supportedPresentModes[] = {
        VK_PRESENT_MODE_IMMEDIATE_KHR,
        VK_PRESENT_MODE_MAILBOX_KHR,
        VK_PRESENT_MODE_FIFO_KHR,
        VK_PRESENT_MODE_FIFO_RELAXED_KHR,
    };
    uint64_t physicalDeviceId;
    uint64_t windowId;
    uint32_t presentModeCount;
    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceSurfacePresentModesKHR((VkPhysicalDevice)&physicalDeviceId, (VkSurfaceKHR)&windowId, &presentModeCount, NULL, &_vt_cursor, &context->memoryPool));

    const uint32_t guestCapacity = presentModeCount;
    const uint32_t serverActual = ARRAY_SIZE(supportedPresentModes);
    presentModeCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, serverActual);
    VkResult result = vt_request_query_result(
            VK_SUCCESS, guestCapacity != 0, guestCapacity, serverActual);
    VkPresentModeKHR* presentModes = guestCapacity > 0
            ? supportedPresentModes : NULL;

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceSurfacePresentModesKHR,
            VK_NULL_HANDLE, NULL, &presentModeCount, presentModes);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkCreateSwapchainKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkSwapchainCreateInfoKHR createInfo = {0};
    uint64_t windowId = 0;
    createInfo.surface = (VkSurfaceKHR)&windowId;

    VT_REQUEST_DECODE(vt_unserialize_vkCreateSwapchainKHR((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkExtent2D windowSize = {0};
    XWindowSwapchain* swapchain = NULL;
    VkResult result = VK_ERROR_SURFACE_LOST_KHR;
    if (getWindowExtent(&context->jmethods, context->contextGeneration,
            _vt_request.state->instance_owner, (int)windowId, &windowSize) &&
            createInfo.imageExtent.width == windowSize.width &&
            createInfo.imageExtent.height == windowSize.height) {
        result = XWindowSwapchain_create(device, context->graphicsQueueIndex,
                &createInfo, &context->jmethods, context->contextGeneration,
                _vt_request.state->instance_owner, (int)windowId, &swapchain);
    }

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN, swapchain, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkSwapchainKHR, (VkSwapchainKHR)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroySwapchainKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t swapchainId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroySwapchainKHR((VkDevice)&deviceId, (VkSwapchainKHR)&swapchainId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(XWindowSwapchain*, swapchain, swapchainId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN, true, true, true);

    VT_REQUEST_AUTHORITY(vt_request_tombstone_children(
            &_vt_request, swapchainId));
    XWindowSwapchain_destroy(device, swapchain);
}

void vt_handle_vkGetSwapchainImagesKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t swapchainId;
    uint32_t swapchainImageCount = 0;

    VT_REQUEST_DECODE(vt_unserialize_vkGetSwapchainImagesKHR((VkDevice)&deviceId, (VkSwapchainKHR)&swapchainId, &swapchainImageCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(XWindowSwapchain*, swapchain, swapchainId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN, true, true, false);

    const uint32_t guestCapacity = swapchainImageCount;
    const uint32_t serverActual = (uint32_t)swapchain->imageCount;
    const uint32_t returnedCount = guestCapacity > 0
            ? vt_request_query_copy_count(guestCapacity, serverActual) : 0;
    VT_REQUEST_ARRAY(VkImage, swapchainImages, returnedCount);
    for (uint32_t i = 0; i < returnedCount; i++)
        swapchainImages[i] = swapchain->images[i].image;
    swapchainImageCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, serverActual);
    VkResult result = vt_request_query_result(
            VK_SUCCESS, guestCapacity != 0, guestCapacity, serverActual);

    if (!vt_request_publish_vulkan_batch(&_vt_request,
            VK_OBJECT_TYPE_IMAGE, swapchainImages, sizeof(*swapchainImages),
            returnedCount, _vt_request.state->instance_owner,
            deviceId, swapchainId,
            VT_REQUEST_PUBLICATION_NONE, 0, 0)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
    VT_SERIALIZE_CMD(vkGetSwapchainImagesKHR, NULL, VK_NULL_HANDLE, &swapchainImageCount, swapchainImages);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkAcquireNextImageKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t swapchainId;
    uint64_t timeout;
    uint64_t semaphoreId;
    uint64_t fenceId;

    VT_REQUEST_DECODE(vt_unserialize_vkAcquireNextImageKHR((VkDevice)&deviceId, (VkSwapchainKHR)&swapchainId, &timeout, (VkSemaphore)&semaphoreId, (VkFence)&fenceId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(XWindowSwapchain*, swapchain, swapchainId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN, true, true, false);
    VT_REQUEST_HANDLE(VkSemaphore, semaphore, semaphoreId, VK_OBJECT_TYPE_SEMAPHORE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);
    VT_REQUEST_HANDLE(VkFence, fence, fenceId, VK_OBJECT_TYPE_FENCE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    uint32_t imageIndex = 0;
    VkResult result = XWindowSwapchain_acquireNextImage(swapchain, timeout, semaphore, fence, &imageIndex);
    if (result == VK_ERROR_DEVICE_LOST) context->status = result;

    VT_REQUEST_SEND(result == VK_SUCCESS ? imageIndex : result, NULL, 0);
}

void vt_handle_vkQueuePresentKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VkPresentInfoKHR presentInfo = {0};
    VT_REQUEST_DECODE(vt_unserialize_VkPresentInfoKHR(&presentInfo, &_vt_cursor, &context->memoryPool));

    for (uint32_t i = 0; i < presentInfo.swapchainCount; i++) {
        if (!XWindowSwapchain_presentImage(
                (XWindowSwapchain*)presentInfo.pSwapchains[i])) {
            vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
            return;
        }
    }
}

void vt_handle_vkGetPhysicalDeviceFeatures2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkPhysicalDeviceFeatures2 features = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceFeatures2((VkPhysicalDevice)&physicalDeviceId, &features, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    vulkanWrapper.vkGetPhysicalDeviceFeatures2(physicalDevice, &features);
    checkDeviceFeatures(&features.features, features.pNext);

    VT_SERIALIZE_CMD(VkPhysicalDeviceFeatures2, &features);
    VT_REQUEST_SEND(0, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceProperties2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkPhysicalDeviceProperties2 properties = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceProperties2((VkPhysicalDevice)&physicalDeviceId, &properties, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    vulkanWrapper.vkGetPhysicalDeviceProperties2(physicalDevice, &properties);
    checkDeviceProperties(context, &properties.properties, properties.pNext);

    VT_SERIALIZE_CMD(VkPhysicalDeviceProperties2, &properties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceFormatProperties2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkFormat format;
    VkFormatProperties2 formatProperties = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceFormatProperties2((VkPhysicalDevice)&physicalDeviceId, &format, &formatProperties, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    vulkanWrapper.vkGetPhysicalDeviceFormatProperties2(physicalDevice, format, &formatProperties);
    checkFormatProperties(physicalDevice, format, &formatProperties.formatProperties);

    VT_SERIALIZE_CMD(VkFormatProperties2, &formatProperties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceImageFormatProperties2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkPhysicalDeviceImageFormatInfo2 imageFormatInfo = {0};
    VkImageFormatProperties2 imageFormatProperties = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceImageFormatProperties2((VkPhysicalDevice)&physicalDeviceId, &imageFormatInfo, &imageFormatProperties, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkResult result = vulkanWrapper.vkGetPhysicalDeviceImageFormatProperties2(physicalDevice, &imageFormatInfo, &imageFormatProperties);
    checkImageFormatProperties(imageFormatInfo.format, imageFormatInfo.type, imageFormatInfo.tiling, imageFormatInfo.usage, imageFormatInfo.flags, &imageFormatProperties.imageFormatProperties, &result);

    VT_SERIALIZE_CMD(VkImageFormatProperties2, &imageFormatProperties);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceQueueFamilyProperties2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    uint32_t queueFamilyPropertyCount;

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceQueueFamilyProperties2((VkPhysicalDevice)&physicalDeviceId, &queueFamilyPropertyCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    const uint32_t guestCapacity = queueFamilyPropertyCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetPhysicalDeviceQueueFamilyProperties2(physicalDevice, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkQueueFamilyProperties2, queueFamilyProperties,
            vt_request_query_storage_count_inline(
                    guestCapacity, serverActual));
    if (queueFamilyProperties) VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceQueueFamilyProperties2(VK_NULL_HANDLE, NULL, queueFamilyProperties, &_vt_cursor, &context->memoryPool));
    if (queueFamilyProperties) {
        for (uint32_t i = guestCapacity; i < serverActual; i++)
            queueFamilyProperties[i].sType = VK_STRUCTURE_TYPE_QUEUE_FAMILY_PROPERTIES_2;
    }
    uint32_t hostCount = serverActual;
    if (queueFamilyProperties) vulkanWrapper.vkGetPhysicalDeviceQueueFamilyProperties2(
            physicalDevice, &hostCount, queueFamilyProperties);
    queueFamilyPropertyCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceQueueFamilyProperties2, VK_NULL_HANDLE, &queueFamilyPropertyCount, queueFamilyProperties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceMemoryProperties2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkPhysicalDeviceMemoryProperties2 memoryProperties = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceMemoryProperties2((VkPhysicalDevice)&physicalDeviceId, &memoryProperties, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    vulkanWrapper.vkGetPhysicalDeviceMemoryProperties2(physicalDevice, &memoryProperties);
    checkDeviceMemoryProperties(context, &memoryProperties.memoryProperties, memoryProperties.pNext);

    VT_SERIALIZE_CMD(VkPhysicalDeviceMemoryProperties2, &memoryProperties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceSparseImageFormatProperties2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkPhysicalDeviceSparseImageFormatInfo2 formatInfo = {0};
    uint32_t propertyCount;

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceSparseImageFormatProperties2((VkPhysicalDevice)&physicalDeviceId, &formatInfo, &propertyCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    const uint32_t guestCapacity = propertyCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetPhysicalDeviceSparseImageFormatProperties2(
            physicalDevice, &formatInfo, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkSparseImageFormatProperties2, properties,
            vt_request_query_storage_count_inline(
                    guestCapacity, serverActual));
    if (properties) VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceSparseImageFormatProperties2(VK_NULL_HANDLE, NULL, NULL, properties, &_vt_cursor, &context->memoryPool));
    if (properties) {
        for (uint32_t i = guestCapacity; i < serverActual; i++)
            properties[i].sType = VK_STRUCTURE_TYPE_SPARSE_IMAGE_FORMAT_PROPERTIES_2;
    }
    uint32_t hostCount = serverActual;
    if (properties) vulkanWrapper.vkGetPhysicalDeviceSparseImageFormatProperties2(
            physicalDevice, &formatInfo, &hostCount, properties);
    propertyCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceSparseImageFormatProperties2, VK_NULL_HANDLE, NULL, &propertyCount, properties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkCmdPushDescriptorSetKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkPipelineBindPoint pipelineBindPoint;
    uint64_t layoutId;
    uint32_t set;
    uint32_t descriptorWriteCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdPushDescriptorSetKHR((VkCommandBuffer)&commandBufferId, &pipelineBindPoint, (VkPipelineLayout)&layoutId, &set, &descriptorWriteCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkPipelineLayout, layout, layoutId, VK_OBJECT_TYPE_PIPELINE_LAYOUT, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkWriteDescriptorSet, descriptorWrites, descriptorWriteCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdPushDescriptorSetKHR(VK_NULL_HANDLE, NULL, VK_NULL_HANDLE, NULL, NULL, descriptorWrites, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdPushDescriptorSet(commandBuffer, pipelineBindPoint, layout, set, descriptorWriteCount, descriptorWrites);
}

void vt_handle_vkTrimCommandPool(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t commandPoolId;
    VkCommandPoolTrimFlags flags;

    VT_REQUEST_DECODE(vt_unserialize_vkTrimCommandPool((VkDevice)&deviceId, (VkCommandPool)&commandPoolId, &flags, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkCommandPool, commandPool, commandPoolId, VK_OBJECT_TYPE_COMMAND_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkTrimCommandPool(device, commandPool, flags);
}

void vt_handle_vkGetPhysicalDeviceExternalBufferProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkPhysicalDeviceExternalBufferInfo bufferInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceExternalBufferProperties((VkPhysicalDevice)&physicalDeviceId, &bufferInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkExternalBufferProperties properties = {0};
    vulkanWrapper.vkGetPhysicalDeviceExternalBufferProperties(physicalDevice, &bufferInfo,  &properties);

    VT_SERIALIZE_CMD(VkExternalBufferProperties, &properties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetMemoryFdKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_RESOURCE_MEMORY);
    uint64_t memoryId;
    VT_REQUEST_DECODE(vt_unserialize_VkDeviceMemory((VkDeviceMemory)&memoryId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(ResourceMemory*, resourceMemory, memoryId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_RESOURCE_MEMORY, true, true, false);

    VkResult result = resourceMemory->fd != -1 ? VK_SUCCESS : VK_ERROR_OUT_OF_HOST_MEMORY;
    if (!vt_request_send_fds_response(&_vt_request,
            &resourceMemory->fd, result == VK_SUCCESS ? 1 : 0,
            &result, sizeof(result))) return;
}

void vt_handle_vkGetPhysicalDeviceExternalSemaphoreProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkPhysicalDeviceExternalSemaphoreInfo semaphoreInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceExternalSemaphoreProperties((VkPhysicalDevice)&physicalDeviceId, &semaphoreInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkExternalSemaphoreProperties properties = {0};
    vulkanWrapper.vkGetPhysicalDeviceExternalSemaphoreProperties(physicalDevice, &semaphoreInfo,  &properties);

    VT_SERIALIZE_CMD(VkExternalSemaphoreProperties, &properties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetSemaphoreFdKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkSemaphoreGetFdInfoKHR getFdInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetSemaphoreFdKHR((VkDevice)&deviceId, &getFdInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    getFdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;

    int fd = -1;
    VkResult result = vulkanWrapper.vkGetSemaphoreFd(device, &getFdInfo, &fd);
    const bool sent = vt_request_send_fds_response(&_vt_request, &fd,
            result == VK_SUCCESS ? 1 : 0, &result, sizeof(result));
    CLOSEFD(fd);
    if (!sent) return;
}

void vt_handle_vkGetPhysicalDeviceExternalFenceProperties(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkPhysicalDeviceExternalFenceInfo fenceInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceExternalFenceProperties((VkPhysicalDevice)&physicalDeviceId, &fenceInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkExternalFenceProperties properties = {0};
    vulkanWrapper.vkGetPhysicalDeviceExternalFenceProperties(physicalDevice, &fenceInfo,  &properties);

    VT_SERIALIZE_CMD(VkExternalFenceProperties, &properties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetFenceFdKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkFenceGetFdInfoKHR getFdInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetFenceFdKHR((VkDevice)&deviceId, &getFdInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    getFdInfo.handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT;

    int fd = -1;
    VkResult result = vulkanWrapper.vkGetFenceFd(device, &getFdInfo, &fd);
    const bool sent = vt_request_send_fds_response(&_vt_request, &fd,
            result == VK_SUCCESS ? 1 : 0, &result, sizeof(result));
    CLOSEFD(fd);
    if (!sent) return;
}

void vt_handle_vkEnumeratePhysicalDeviceGroups(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_INSTANCE, VORTEK_HANDLE_ROLE_VULKAN);
    uint32_t physicalDeviceGroupCount;
    uint64_t instanceId;
    VT_REQUEST_DECODE(vt_unserialize_vkEnumeratePhysicalDeviceGroups((VkInstance)&instanceId, &physicalDeviceGroupCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkInstance, instance, instanceId, VK_OBJECT_TYPE_INSTANCE, VORTEK_HANDLE_ROLE_VULKAN, false, false, false);

    const uint32_t guestCapacity = physicalDeviceGroupCount;
    uint32_t serverActual = 0;
    VkResult result = vulkanWrapper.vkEnumeratePhysicalDeviceGroups(instance, &serverActual, NULL);
    const uint32_t returnedGroupCount = guestCapacity > 0
            ? vt_request_query_copy_count(guestCapacity, serverActual) : 0;
    VT_REQUEST_ARRAY(VkPhysicalDeviceGroupProperties,
            physicalDeviceGroupProperties, returnedGroupCount);
    for (uint32_t i = 0; i < returnedGroupCount; i++)
        physicalDeviceGroupProperties[i].sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_GROUP_PROPERTIES;
    uint32_t hostCount = returnedGroupCount;
    if (physicalDeviceGroupProperties) result = vulkanWrapper.vkEnumeratePhysicalDeviceGroups(
            instance, &hostCount, physicalDeviceGroupProperties);
    physicalDeviceGroupCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);
    result = vt_request_query_result(result, guestCapacity != 0, guestCapacity, serverActual);

    const uint32_t publishedGroupCount =
            result >= 0 && physicalDeviceGroupProperties
            ? physicalDeviceGroupCount : 0;
    size_t totalPhysicalDevices = 0;
    for (uint32_t group = 0; group < publishedGroupCount; group++) {
        VkPhysicalDeviceGroupProperties* properties = &physicalDeviceGroupProperties[group];
        if (properties->physicalDeviceCount > VT_DECODE_MAX_ELEMENTS - totalPhysicalDevices) {
            vt_request_protocol_error(context, VT_DECODE_ERROR_LIMIT);
            return;
        }
        totalPhysicalDevices += properties->physicalDeviceCount;
    }
    VT_REQUEST_ARRAY(VkPhysicalDevice, allPhysicalDevices, totalPhysicalDevices);
    size_t physicalDeviceIndex = 0;
    for (uint32_t group = 0; group < publishedGroupCount; group++) {
        VkPhysicalDeviceGroupProperties* properties = &physicalDeviceGroupProperties[group];
        for (uint32_t i = 0; i < properties->physicalDeviceCount; i++)
            allPhysicalDevices[physicalDeviceIndex++] = properties->physicalDevices[i];
    }
    if (result >= 0 && !vt_request_publish_vulkan_batch(&_vt_request,
            VK_OBJECT_TYPE_PHYSICAL_DEVICE, allPhysicalDevices,
            sizeof(*allPhysicalDevices), totalPhysicalDevices, instanceId, 0, 0,
            VT_REQUEST_PUBLICATION_NONE, 0, 0)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
    physicalDeviceIndex = 0;
    for (uint32_t group = 0; group < publishedGroupCount; group++) {
        VkPhysicalDeviceGroupProperties* properties = &physicalDeviceGroupProperties[group];
        for (uint32_t i = 0; i < properties->physicalDeviceCount; i++)
            properties->physicalDevices[i] = result >= 0
                    ? allPhysicalDevices[physicalDeviceIndex++] : VK_NULL_HANDLE;
    }
    if (result < 0 && physicalDeviceGroupProperties)
        memset(physicalDeviceGroupProperties, 0,
                physicalDeviceGroupCount * sizeof(*physicalDeviceGroupProperties));
    VT_SERIALIZE_CMD(vkEnumeratePhysicalDeviceGroups, VK_NULL_HANDLE, &physicalDeviceGroupCount, physicalDeviceGroupProperties);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkGetDeviceGroupPeerMemoryFeatures(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint32_t heapIndex;
    uint32_t localDeviceIndex;
    uint32_t remoteDeviceIndex;

    VT_REQUEST_DECODE(vt_unserialize_vkGetDeviceGroupPeerMemoryFeatures((VkDevice)&deviceId, &heapIndex, &localDeviceIndex, &remoteDeviceIndex, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkPeerMemoryFeatureFlags featureFlags = 0;
    vulkanWrapper.vkGetDeviceGroupPeerMemoryFeatures(device, heapIndex, localDeviceIndex, remoteDeviceIndex, &featureFlags);

    VT_REQUEST_SEND(VK_SUCCESS, &featureFlags, 4);
}

void vt_handle_vkBindBufferMemory2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint32_t bindInfoCount;

    VT_REQUEST_DECODE(vt_unserialize_vkBindBufferMemory2((VkDevice)&deviceId, &bindInfoCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_ARRAY(VkBindBufferMemoryInfo, bindInfos, bindInfoCount);
    if (context->textureDecoder) {
        vortekSerializerCastVkObject = false;
        VT_REQUEST_DECODE(vt_unserialize_vkBindBufferMemory2(VK_NULL_HANDLE, NULL, bindInfos, &_vt_cursor, &context->memoryPool));
        vortekSerializerCastVkObject = true;

        for (int i = 0; i < bindInfoCount; i++) {
            ResourceMemory* resourceMemory = (ResourceMemory*)bindInfos[i].memory;
            TextureDecoder_addBoundBuffer(context->textureDecoder, resourceMemory, bindInfos[i].buffer, bindInfos[i].memoryOffset);
            bindInfos[i].memory = resourceMemory->memory;
        }
    }
    else VT_REQUEST_DECODE(vt_unserialize_vkBindBufferMemory2(VK_NULL_HANDLE, NULL, bindInfos, &_vt_cursor, &context->memoryPool));

    VkResult result = vulkanWrapper.vkBindBufferMemory2(device, bindInfoCount, bindInfos);

    if (result != VK_SUCCESS && context->textureDecoder) {
        for (int i = 0; i < bindInfoCount; i++) TextureDecoder_removeBoundBuffer(context->textureDecoder, bindInfos[i].buffer);
    }

    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkBindImageMemory2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint32_t bindInfoCount;

    VT_REQUEST_DECODE(vt_unserialize_vkBindImageMemory2((VkDevice)&deviceId, &bindInfoCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_ARRAY(VkBindImageMemoryInfo, bindInfos, bindInfoCount);
    VT_REQUEST_DECODE(vt_unserialize_vkBindImageMemory2(VK_NULL_HANDLE, NULL, bindInfos, &_vt_cursor, &context->memoryPool));

    VkResult result = vulkanWrapper.vkBindImageMemory2(device, bindInfoCount, bindInfos);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkCmdSetDeviceMask(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t deviceMask;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDeviceMask((VkCommandBuffer)&commandBufferId, &deviceMask, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDeviceMask(commandBuffer, deviceMask);
}

void vt_handle_vkAcquireNextImage2KHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VkAcquireNextImageInfoKHR acquireInfo = {0};
    VT_REQUEST_DECODE(vt_unserialize_VkAcquireNextImageInfoKHR(&acquireInfo, &_vt_cursor, &context->memoryPool));
    XWindowSwapchain* swapchain = (XWindowSwapchain*)acquireInfo.swapchain;

    uint32_t imageIndex = 0;
    VkResult result = XWindowSwapchain_acquireNextImage(swapchain, acquireInfo.timeout, acquireInfo.semaphore, acquireInfo.fence, &imageIndex);
    if (result == VK_ERROR_DEVICE_LOST) context->status = result;

    VT_REQUEST_SEND(result == VK_SUCCESS ? imageIndex : result, NULL, 0);
}

void vt_handle_vkCmdDispatchBase(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t baseGroupX;
    uint32_t baseGroupY;
    uint32_t baseGroupZ;
    uint32_t groupCountX;
    uint32_t groupCountY;
    uint32_t groupCountZ;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdDispatchBase((VkCommandBuffer)&commandBufferId, &baseGroupX, &baseGroupY, &baseGroupZ, &groupCountX, &groupCountY, &groupCountZ, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdDispatchBase(commandBuffer, baseGroupX, baseGroupY, baseGroupZ, groupCountX, groupCountY, groupCountZ);
}

void vt_handle_vkGetPhysicalDevicePresentRectanglesKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    uint64_t windowId;
    uint32_t rectCount;
    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDevicePresentRectanglesKHR((VkPhysicalDevice)&physicalDeviceId, (VkSurfaceKHR)&windowId, &rectCount, NULL, &_vt_cursor, &context->memoryPool));

    const uint32_t guestCapacity = rectCount;
    VkRect2D rect = {0};
    if (guestCapacity > 0 && !getWindowExtent(
            &context->jmethods, context->contextGeneration,
            _vt_request.state->instance_owner, (int)windowId, &rect.extent)) {
        VT_REQUEST_SEND(VK_ERROR_SURFACE_LOST_KHR, NULL, 0);
        return;
    }
    rectCount = 1;
    VkRect2D* rects = guestCapacity > 0 ? &rect : NULL;

    VT_SERIALIZE_CMD(vkGetPhysicalDevicePresentRectanglesKHR,
            VK_NULL_HANDLE, VK_NULL_HANDLE, &rectCount, rects);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkCmdSetSampleLocationsEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkSampleLocationsInfoEXT sampleLocationsInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetSampleLocationsEXT((VkCommandBuffer)&commandBufferId, &sampleLocationsInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetSampleLocations(commandBuffer, &sampleLocationsInfo);
}

void vt_handle_vkGetPhysicalDeviceMultisamplePropertiesEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    VkSampleCountFlagBits samples;

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceMultisamplePropertiesEXT((VkPhysicalDevice)&physicalDeviceId, &samples, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkMultisamplePropertiesEXT multisampleProperties = {0};
    vulkanWrapper.vkGetPhysicalDeviceMultisampleProperties(physicalDevice, samples, &multisampleProperties);

    VT_SERIALIZE_CMD(VkMultisamplePropertiesEXT, &multisampleProperties);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetBufferMemoryRequirements2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkBufferMemoryRequirementsInfo2 requirementsInfo = {0};
    VkMemoryRequirements2 memoryRequirements = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetBufferMemoryRequirements2((VkDevice)&deviceId, &requirementsInfo, &memoryRequirements, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    vulkanWrapper.vkGetBufferMemoryRequirements2(device, &requirementsInfo, &memoryRequirements);

    VT_SERIALIZE_CMD(VkMemoryRequirements2, &memoryRequirements);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetImageMemoryRequirements2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkImageMemoryRequirementsInfo2 requirementsInfo = {0};
    VkMemoryRequirements2 memoryRequirements = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetImageMemoryRequirements2((VkDevice)&deviceId, &requirementsInfo, &memoryRequirements, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    vulkanWrapper.vkGetImageMemoryRequirements2(device, &requirementsInfo, &memoryRequirements);

    VT_SERIALIZE_CMD(VkMemoryRequirements2, &memoryRequirements);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetImageSparseMemoryRequirements2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkImageSparseMemoryRequirementsInfo2 requirementsInfo = {0};
    uint32_t requirementCount;

    VT_REQUEST_DECODE(vt_unserialize_vkGetImageSparseMemoryRequirements2((VkDevice)&deviceId, &requirementsInfo, &requirementCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    const uint32_t guestCapacity = requirementCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetImageSparseMemoryRequirements2(device, &requirementsInfo, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkSparseImageMemoryRequirements2, requirements,
            vt_request_query_storage_count_inline(
                    guestCapacity, serverActual));
    if (requirements) VT_REQUEST_DECODE(vt_unserialize_vkGetImageSparseMemoryRequirements2(VK_NULL_HANDLE, NULL, NULL, requirements, &_vt_cursor, &context->memoryPool));
    if (requirements) {
        for (uint32_t i = guestCapacity; i < serverActual; i++)
            requirements[i].sType = VK_STRUCTURE_TYPE_SPARSE_IMAGE_MEMORY_REQUIREMENTS_2;
    }
    uint32_t hostCount = serverActual;
    if (requirements) vulkanWrapper.vkGetImageSparseMemoryRequirements2(device, &requirementsInfo, &hostCount, requirements);
    requirementCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetImageSparseMemoryRequirements2, VK_NULL_HANDLE, NULL, &requirementCount, requirements);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetDeviceBufferMemoryRequirements(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkDeviceBufferMemoryRequirements requirementsInfo = {0};
    VkMemoryRequirements2 memoryRequirements = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetDeviceBufferMemoryRequirements((VkDevice)&deviceId, &requirementsInfo, &memoryRequirements, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    vulkanWrapper.vkGetDeviceBufferMemoryRequirements(device, &requirementsInfo, &memoryRequirements);

    VT_SERIALIZE_CMD(VkMemoryRequirements2, &memoryRequirements);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetDeviceImageMemoryRequirements(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkDeviceImageMemoryRequirements requirementsInfo = {0};
    VkMemoryRequirements2 memoryRequirements = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetDeviceImageMemoryRequirements((VkDevice)&deviceId, &requirementsInfo, &memoryRequirements, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    vulkanWrapper.vkGetDeviceImageMemoryRequirements(device, &requirementsInfo, &memoryRequirements);

    VT_SERIALIZE_CMD(VkMemoryRequirements2, &memoryRequirements);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetDeviceImageSparseMemoryRequirements(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkDeviceImageMemoryRequirements requirementsInfo = {0};
    uint32_t requirementCount;

    VT_REQUEST_DECODE(vt_unserialize_vkGetDeviceImageSparseMemoryRequirements((VkDevice)&deviceId, &requirementsInfo, &requirementCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    const uint32_t guestCapacity = requirementCount;
    uint32_t serverActual = 0;
    vulkanWrapper.vkGetDeviceImageSparseMemoryRequirements(device, &requirementsInfo, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkSparseImageMemoryRequirements2, requirements,
            vt_request_query_storage_count_inline(
                    guestCapacity, serverActual));
    if (requirements) VT_REQUEST_DECODE(vt_unserialize_vkGetDeviceImageSparseMemoryRequirements(VK_NULL_HANDLE, NULL, NULL, requirements, &_vt_cursor, &context->memoryPool));
    if (requirements) {
        for (uint32_t i = guestCapacity; i < serverActual; i++)
            requirements[i].sType = VK_STRUCTURE_TYPE_SPARSE_IMAGE_MEMORY_REQUIREMENTS_2;
    }
    uint32_t hostCount = serverActual;
    if (requirements) vulkanWrapper.vkGetDeviceImageSparseMemoryRequirements(device, &requirementsInfo, &hostCount, requirements);
    requirementCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);

    VT_SERIALIZE_CMD(vkGetDeviceImageSparseMemoryRequirements, VK_NULL_HANDLE, NULL, &requirementCount, requirements);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkCreateSamplerYcbcrConversion(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkSamplerYcbcrConversionCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateSamplerYcbcrConversion((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkSamplerYcbcrConversion ycbcrConversion = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateSamplerYcbcrConversion(device, &createInfo, NULL, &ycbcrConversion);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_SAMPLER_YCBCR_CONVERSION, VORTEK_HANDLE_ROLE_VULKAN, ycbcrConversion, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkSamplerYcbcrConversion, (VkSamplerYcbcrConversion)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkDestroySamplerYcbcrConversion(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t ycbcrConversionId;

    VT_REQUEST_DECODE(vt_unserialize_vkDestroySamplerYcbcrConversion((VkDevice)&deviceId, (VkSamplerYcbcrConversion)&ycbcrConversionId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_RETIRED_HANDLE(VkSamplerYcbcrConversion, ycbcrConversion, ycbcrConversionId, VK_OBJECT_TYPE_SAMPLER_YCBCR_CONVERSION, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    vulkanWrapper.vkDestroySamplerYcbcrConversion(device, ycbcrConversion, NULL);
}

void vt_handle_vkGetDeviceQueue2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkDeviceQueueInfo2 queueInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetDeviceQueue2((VkDevice)&deviceId, &queueInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkQueue queue = VK_NULL_HANDLE;
    vulkanWrapper.vkGetDeviceQueue2(device, &queueInfo, &queue);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH(VK_OBJECT_TYPE_QUEUE, VORTEK_HANDLE_ROLE_VULKAN, queue, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkQueue, (VkQueue)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetDescriptorSetLayoutSupport(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkDescriptorSetLayoutCreateInfo createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetDescriptorSetLayoutSupport((VkDevice)&deviceId, &createInfo, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkDescriptorSetLayoutSupport support = {0};
    vulkanWrapper.vkGetDescriptorSetLayoutSupport(device, &createInfo, &support);

    VT_SERIALIZE_CMD(VkDescriptorSetLayoutSupport, &support);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetPhysicalDeviceCalibrateableTimeDomainsKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t physicalDeviceId;
    uint32_t timeDomainCount;

    VT_REQUEST_DECODE(vt_unserialize_vkGetPhysicalDeviceCalibrateableTimeDomainsKHR((VkPhysicalDevice)&physicalDeviceId, &timeDomainCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkPhysicalDevice, physicalDevice, physicalDeviceId, VK_OBJECT_TYPE_PHYSICAL_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    const uint32_t guestCapacity = timeDomainCount;
    uint32_t serverActual = 0;
    VkResult result = vulkanWrapper.vkGetPhysicalDeviceCalibrateableTimeDomains(
            physicalDevice, &serverActual, NULL);
    VT_REQUEST_ARRAY(VkTimeDomainKHR, timeDomains, guestCapacity > 0 ? serverActual : 0);
    uint32_t hostCount = serverActual;
    if (timeDomains) result = vulkanWrapper.vkGetPhysicalDeviceCalibrateableTimeDomains(
            physicalDevice, &hostCount, timeDomains);
    timeDomainCount = guestCapacity == 0 ? serverActual :
            vt_request_query_copy_count(guestCapacity, hostCount);
    result = vt_request_query_result(result, guestCapacity != 0, guestCapacity, serverActual);

    VT_SERIALIZE_CMD(vkGetPhysicalDeviceCalibrateableTimeDomainsKHR, VK_NULL_HANDLE, &timeDomainCount, timeDomains);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkGetCalibratedTimestampsKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint32_t timestampCount;

    VT_REQUEST_DECODE(vt_unserialize_vkGetCalibratedTimestampsKHR((VkDevice)&deviceId, &timestampCount, NULL, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VT_REQUEST_ARRAY(VkCalibratedTimestampInfoKHR, timestampInfos, timestampCount);
    VT_REQUEST_DECODE(vt_unserialize_vkGetCalibratedTimestampsKHR(VK_NULL_HANDLE, NULL, timestampInfos, NULL, NULL, &_vt_cursor, &context->memoryPool));

    VT_REQUEST_ARRAY(uint64_t, timestamps, timestampCount);
    uint64_t maxDeviation;
    VkResult result = vulkanWrapper.vkGetCalibratedTimestamps(device, timestampCount, timestampInfos, timestamps, &maxDeviation);

    VT_SERIALIZE_CMD(vkGetCalibratedTimestampsKHR, VK_NULL_HANDLE, timestampCount, NULL, timestamps, &maxDeviation);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkCreateRenderPass2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkRenderPassCreateInfo2 createInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCreateRenderPass2((VkDevice)&deviceId, &createInfo, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateRenderPass2(device, &createInfo, NULL, &renderPass);

    uint64_t _vt_wire_output = 0;

    VT_REQUEST_PUBLISH_RESULT(result, VK_OBJECT_TYPE_RENDER_PASS, VORTEK_HANDLE_ROLE_VULKAN, renderPass, _vt_request.state->instance_owner, deviceId, device, _vt_wire_output);

    VT_SERIALIZE_CMD(VkRenderPass, (VkRenderPass)(uintptr_t)_vt_wire_output);
    VT_REQUEST_SEND(result, outputBuffer, bufferSize);
}

void vt_handle_vkCmdBeginRenderPass2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkRenderPassBeginInfo renderPassBegin = {0};
    VkSubpassBeginInfo subpassBeginInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBeginRenderPass2((VkCommandBuffer)&commandBufferId, &renderPassBegin, &subpassBeginInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdBeginRenderPass2(commandBuffer, &renderPassBegin, &subpassBeginInfo);
}

void vt_handle_vkCmdNextSubpass2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkSubpassBeginInfo subpassBeginInfo = {0};
    VkSubpassEndInfo subpassEndInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdNextSubpass2((VkCommandBuffer)&commandBufferId, &subpassBeginInfo, &subpassEndInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdNextSubpass2(commandBuffer, &subpassBeginInfo, &subpassEndInfo);
}

void vt_handle_vkCmdEndRenderPass2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkSubpassEndInfo subpassEndInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdEndRenderPass2((VkCommandBuffer)&commandBufferId, &subpassEndInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdEndRenderPass2(commandBuffer, &subpassEndInfo);
}

void vt_handle_vkGetSemaphoreCounterValue(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t semaphoreId;

    VT_REQUEST_DECODE(vt_unserialize_vkGetSemaphoreCounterValue((VkDevice)&deviceId, (VkSemaphore)&semaphoreId, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(VkSemaphore, semaphore, semaphoreId, VK_OBJECT_TYPE_SEMAPHORE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    uint64_t value = 0;
    VkResult result = vulkanWrapper.vkGetSemaphoreCounterValue(device, semaphore, &value);
    VT_REQUEST_SEND(result, &value, sizeof(value));
}

void vt_handle_vkWaitSemaphores(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId = 0;
    uint64_t timeout = 0;
    VT_REQUEST_DECODE(vt_unserialize_vkWaitSemaphores((VkDevice)&deviceId, NULL, &timeout, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    if (!TimelineSemaphore_asyncWait(
            context, &_vt_request, deviceId, device, timeout)) {
        vt_request_protocol_error(context, VT_DECODE_ERROR_HANDLE_REJECTED);
        return;
    }
}

void vt_handle_vkSignalSemaphore(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkSemaphoreSignalInfo signalInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkSignalSemaphore((VkDevice)&deviceId, &signalInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkResult result = vulkanWrapper.vkSignalSemaphore(device, &signalInfo);
    VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkCmdDrawIndirectCount(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t bufferId;
    VkDeviceSize offset;
    uint64_t countBufferId;
    VkDeviceSize countBufferOffset;
    uint32_t maxDrawCount;
    uint32_t stride;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdDrawIndirectCount((VkCommandBuffer)&commandBufferId, (VkBuffer)&bufferId, &offset, (VkBuffer)&countBufferId, &countBufferOffset, &maxDrawCount, &stride, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, buffer, bufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, countBuffer, countBufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdDrawIndirectCount(commandBuffer, buffer, offset, countBuffer, countBufferOffset, maxDrawCount, stride);
}

void vt_handle_vkCmdDrawIndexedIndirectCount(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t bufferId;
    VkDeviceSize offset;
    uint64_t countBufferId;
    VkDeviceSize countBufferOffset;
    uint32_t maxDrawCount;
    uint32_t stride;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdDrawIndexedIndirectCount((VkCommandBuffer)&commandBufferId, (VkBuffer)&bufferId, &offset, (VkBuffer)&countBufferId, &countBufferOffset, &maxDrawCount, &stride, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, buffer, bufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, countBuffer, countBufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdDrawIndexedIndirectCount(commandBuffer, buffer, offset, countBuffer, countBufferOffset, maxDrawCount, stride);
}

void vt_handle_vkCmdBindTransformFeedbackBuffersEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstBinding;
    uint32_t bindingCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBindTransformFeedbackBuffersEXT((VkCommandBuffer)&commandBufferId, &firstBinding, &bindingCount, VK_NULL_HANDLE, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkBuffer, buffers, bindingCount);
    VT_REQUEST_ARRAY(VkDeviceSize, offsets, bindingCount);
    VT_REQUEST_ARRAY(VkDeviceSize, sizes, bindingCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdBindTransformFeedbackBuffersEXT(VK_NULL_HANDLE, NULL, NULL, buffers, offsets, sizes, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdBindTransformFeedbackBuffers(commandBuffer, firstBinding, bindingCount, buffers, offsets, sizes);
}

void vt_handle_vkCmdBeginTransformFeedbackEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstCounterBuffer;
    uint32_t counterBufferCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBeginTransformFeedbackEXT((VkCommandBuffer)&commandBufferId, &firstCounterBuffer, &counterBufferCount, VK_NULL_HANDLE, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkBuffer, counterBuffers, counterBufferCount);
    VT_REQUEST_ARRAY(VkDeviceSize, counterBufferOffsets, counterBufferCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdBeginTransformFeedbackEXT(VK_NULL_HANDLE, NULL, NULL, counterBuffers, counterBufferOffsets, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdBeginTransformFeedback(commandBuffer, firstCounterBuffer, counterBufferCount, counterBuffers, counterBufferOffsets);
}

void vt_handle_vkCmdEndTransformFeedbackEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstCounterBuffer;
    uint32_t counterBufferCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdEndTransformFeedbackEXT((VkCommandBuffer)&commandBufferId, &firstCounterBuffer, &counterBufferCount, VK_NULL_HANDLE, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkBuffer, counterBuffers, counterBufferCount);
    VT_REQUEST_ARRAY(VkDeviceSize, counterBufferOffsets, counterBufferCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdEndTransformFeedbackEXT(VK_NULL_HANDLE, NULL, NULL, counterBuffers, counterBufferOffsets, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdEndTransformFeedback(commandBuffer, firstCounterBuffer, counterBufferCount, counterBuffers, counterBufferOffsets);
}

void vt_handle_vkCmdBeginQueryIndexedEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t queryPoolId;
    uint32_t query;
    VkQueryControlFlags flags;
    uint32_t index;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBeginQueryIndexedEXT((VkCommandBuffer)&commandBufferId, (VkQueryPool)&queryPoolId, &query, &flags, &index, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdBeginQueryIndexed(commandBuffer, queryPool, query, flags, index);
}

void vt_handle_vkCmdEndQueryIndexedEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t queryPoolId;
    uint32_t query;
    uint32_t index;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdEndQueryIndexedEXT((VkCommandBuffer)&commandBufferId, (VkQueryPool)&queryPoolId, &query, &index, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdEndQueryIndexed(commandBuffer, queryPool, query, index);
}

void vt_handle_vkCmdDrawIndirectByteCountEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t instanceCount;
    uint32_t firstInstance;
    uint64_t counterBufferId;
    VkDeviceSize counterBufferOffset;
    uint32_t counterOffset;
    uint32_t vertexStride;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdDrawIndirectByteCountEXT((VkCommandBuffer)&commandBufferId, &instanceCount, &firstInstance, (VkBuffer)&counterBufferId, &counterBufferOffset, &counterOffset, &vertexStride, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkBuffer, counterBuffer, counterBufferId, VK_OBJECT_TYPE_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdDrawIndirectByteCount(commandBuffer, instanceCount, firstInstance, counterBuffer, counterBufferOffset, counterOffset, vertexStride);
}

void vt_handle_vkGetBufferOpaqueCaptureAddress(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkBufferDeviceAddressInfo info = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetBufferOpaqueCaptureAddress((VkDevice)&deviceId, &info, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    uint64_t value = vulkanWrapper.vkGetBufferOpaqueCaptureAddress(device, &info);
    VT_REQUEST_SEND(VK_SUCCESS, &value, sizeof(uint64_t));
}

void vt_handle_vkGetBufferDeviceAddress(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkBufferDeviceAddressInfo info = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetBufferDeviceAddress((VkDevice)&deviceId, &info, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    VkDeviceAddress value = vulkanWrapper.vkGetBufferDeviceAddress(device, &info);
    VT_REQUEST_SEND(VK_SUCCESS, &value, sizeof(VkDeviceAddress));
}

void vt_handle_vkGetDeviceMemoryOpaqueCaptureAddress(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkDeviceMemoryOpaqueCaptureAddressInfo info = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetDeviceMemoryOpaqueCaptureAddress((VkDevice)&deviceId, &info, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    uint64_t value = vulkanWrapper.vkGetDeviceMemoryOpaqueCaptureAddress(device, &info);
    VT_REQUEST_SEND(VK_SUCCESS, &value, sizeof(uint64_t));
}

void vt_handle_vkCmdSetLineStippleKHR(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t lineStippleFactor;
    uint16_t lineStipplePattern;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetLineStippleKHR((VkCommandBuffer)&commandBufferId, &lineStippleFactor, &lineStipplePattern, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetLineStipple(commandBuffer, lineStippleFactor, lineStipplePattern);
}

void vt_handle_vkCmdSetCullMode(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkCullModeFlags cullMode;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetCullMode((VkCommandBuffer)&commandBufferId, &cullMode, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetCullMode(commandBuffer, cullMode);
}

void vt_handle_vkCmdSetFrontFace(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkFrontFace frontFace;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetFrontFace((VkCommandBuffer)&commandBufferId, &frontFace, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetFrontFace(commandBuffer, frontFace);
}

void vt_handle_vkCmdSetPrimitiveTopology(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkPrimitiveTopology primitiveTopology;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetPrimitiveTopology((VkCommandBuffer)&commandBufferId, &primitiveTopology, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetPrimitiveTopology(commandBuffer, primitiveTopology);
}

void vt_handle_vkCmdSetViewportWithCount(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t viewportCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetViewportWithCount((VkCommandBuffer)&commandBufferId, &viewportCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkViewport, viewports, viewportCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetViewportWithCount(VK_NULL_HANDLE, NULL, viewports, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetViewportWithCount(commandBuffer, viewportCount, viewports);
}

void vt_handle_vkCmdSetScissorWithCount(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t scissorCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetScissorWithCount((VkCommandBuffer)&commandBufferId, &scissorCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkRect2D, scissors, scissorCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetScissorWithCount(VK_NULL_HANDLE, NULL, scissors, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetScissorWithCount(commandBuffer, scissorCount, scissors);
}

void vt_handle_vkCmdBindVertexBuffers2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstBinding;
    uint32_t bindingCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBindVertexBuffers2((VkCommandBuffer)&commandBufferId, &firstBinding, &bindingCount, VK_NULL_HANDLE, NULL, NULL, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkBuffer, buffers, bindingCount);
    VT_REQUEST_ARRAY(VkDeviceSize, offsets, bindingCount);
    VT_REQUEST_ARRAY(VkDeviceSize, sizes, bindingCount);
    VT_REQUEST_ARRAY(VkDeviceSize, strides, bindingCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdBindVertexBuffers2(VK_NULL_HANDLE, NULL, NULL, buffers, offsets, sizes, strides, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdBindVertexBuffers2(commandBuffer, firstBinding, bindingCount, buffers, offsets, sizes, strides);
}

void vt_handle_vkCmdSetDepthTestEnable(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 depthTestEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDepthTestEnable((VkCommandBuffer)&commandBufferId, &depthTestEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDepthTestEnable(commandBuffer, depthTestEnable);
}

void vt_handle_vkCmdSetDepthWriteEnable(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 depthWriteEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDepthWriteEnable((VkCommandBuffer)&commandBufferId, &depthWriteEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDepthWriteEnable(commandBuffer, depthWriteEnable);
}

void vt_handle_vkCmdSetDepthCompareOp(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkCompareOp depthCompareOp;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDepthCompareOp((VkCommandBuffer)&commandBufferId, &depthCompareOp, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDepthCompareOp(commandBuffer, depthCompareOp);
}

void vt_handle_vkCmdSetDepthBoundsTestEnable(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 depthBoundsTestEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDepthBoundsTestEnable((VkCommandBuffer)&commandBufferId, &depthBoundsTestEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDepthBoundsTestEnable(commandBuffer, depthBoundsTestEnable);
}

void vt_handle_vkCmdSetStencilTestEnable(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 stencilTestEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetStencilTestEnable((VkCommandBuffer)&commandBufferId, &stencilTestEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetStencilTestEnable(commandBuffer, stencilTestEnable);
}

void vt_handle_vkCmdSetStencilOp(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkStencilFaceFlags faceMask;
    VkStencilOp failOp;
    VkStencilOp passOp;
    VkStencilOp depthFailOp;
    VkCompareOp compareOp;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetStencilOp((VkCommandBuffer)&commandBufferId, &faceMask, &failOp, &passOp, &depthFailOp, &compareOp, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetStencilOp(commandBuffer, faceMask, failOp, passOp, depthFailOp, compareOp);
}

void vt_handle_vkCmdSetRasterizerDiscardEnable(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 rasterizerDiscardEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetRasterizerDiscardEnable((VkCommandBuffer)&commandBufferId, &rasterizerDiscardEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetRasterizerDiscardEnable(commandBuffer, rasterizerDiscardEnable);
}

void vt_handle_vkCmdSetDepthBiasEnable(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 depthBiasEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDepthBiasEnable((VkCommandBuffer)&commandBufferId, &depthBiasEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDepthBiasEnable(commandBuffer, depthBiasEnable);
}

void vt_handle_vkCmdSetPrimitiveRestartEnable(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 primitiveRestartEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetPrimitiveRestartEnable((VkCommandBuffer)&commandBufferId, &primitiveRestartEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetPrimitiveRestartEnable(commandBuffer, primitiveRestartEnable);
}

void vt_handle_vkCmdSetTessellationDomainOriginEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkTessellationDomainOrigin domainOrigin;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetTessellationDomainOriginEXT((VkCommandBuffer)&commandBufferId, &domainOrigin, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetTessellationDomainOrigin(commandBuffer, domainOrigin);
}

void vt_handle_vkCmdSetDepthClampEnableEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 depthClampEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDepthClampEnableEXT((VkCommandBuffer)&commandBufferId, &depthClampEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDepthClampEnable(commandBuffer, depthClampEnable);
}

void vt_handle_vkCmdSetPolygonModeEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkPolygonMode polygonMode;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetPolygonModeEXT((VkCommandBuffer)&commandBufferId, &polygonMode, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetPolygonMode(commandBuffer, polygonMode);
}

void vt_handle_vkCmdSetRasterizationSamplesEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkSampleCountFlagBits rasterizationSamples;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetRasterizationSamplesEXT((VkCommandBuffer)&commandBufferId, &rasterizationSamples, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetRasterizationSamples(commandBuffer, rasterizationSamples);
}

void vt_handle_vkCmdSetSampleMaskEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkSampleCountFlagBits samples;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetSampleMaskEXT((VkCommandBuffer)&commandBufferId, &samples, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkSampleMask, sampleMask, (((uint32_t)samples + 31u) / 32u));
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetSampleMaskEXT(VK_NULL_HANDLE, NULL, sampleMask, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetSampleMask(commandBuffer, samples, sampleMask);
}

void vt_handle_vkCmdSetAlphaToCoverageEnableEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 alphaToCoverageEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetAlphaToCoverageEnableEXT((VkCommandBuffer)&commandBufferId, &alphaToCoverageEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetAlphaToCoverageEnable(commandBuffer, alphaToCoverageEnable);
}

void vt_handle_vkCmdSetAlphaToOneEnableEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 alphaToOneEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetAlphaToOneEnableEXT((VkCommandBuffer)&commandBufferId, &alphaToOneEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetAlphaToOneEnable(commandBuffer, alphaToOneEnable);
}

void vt_handle_vkCmdSetLogicOpEnableEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 logicOpEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetLogicOpEnableEXT((VkCommandBuffer)&commandBufferId, &logicOpEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetLogicOpEnable(commandBuffer, logicOpEnable);
}

void vt_handle_vkCmdSetColorBlendEnableEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstAttachment;
    uint32_t attachmentCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetColorBlendEnableEXT((VkCommandBuffer)&commandBufferId, &firstAttachment, &attachmentCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkBool32, colorBlendEnables, attachmentCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetColorBlendEnableEXT(VK_NULL_HANDLE, NULL, NULL, colorBlendEnables, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetColorBlendEnable(commandBuffer, firstAttachment, attachmentCount, colorBlendEnables);
}

void vt_handle_vkCmdSetColorBlendEquationEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstAttachment;
    uint32_t attachmentCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetColorBlendEquationEXT((VkCommandBuffer)&commandBufferId, &firstAttachment, &attachmentCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkColorBlendEquationEXT, colorBlendEquations, attachmentCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetColorBlendEquationEXT(VK_NULL_HANDLE, NULL, NULL, colorBlendEquations, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetColorBlendEquation(commandBuffer, firstAttachment, attachmentCount, colorBlendEquations);
}

void vt_handle_vkCmdSetColorWriteMaskEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstAttachment;
    uint32_t attachmentCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetColorWriteMaskEXT((VkCommandBuffer)&commandBufferId, &firstAttachment, &attachmentCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkColorComponentFlags, colorWriteMasks, attachmentCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetColorWriteMaskEXT(VK_NULL_HANDLE, NULL, NULL, colorWriteMasks, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetColorWriteMask(commandBuffer, firstAttachment, attachmentCount, colorWriteMasks);
}

void vt_handle_vkCmdSetRasterizationStreamEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t rasterizationStream;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetRasterizationStreamEXT((VkCommandBuffer)&commandBufferId, &rasterizationStream, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetRasterizationStream(commandBuffer, rasterizationStream);
}

void vt_handle_vkCmdSetConservativeRasterizationModeEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkConservativeRasterizationModeEXT conservativeRasterizationMode;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetConservativeRasterizationModeEXT((VkCommandBuffer)&commandBufferId, &conservativeRasterizationMode, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetConservativeRasterizationMode(commandBuffer, conservativeRasterizationMode);
}

void vt_handle_vkCmdSetExtraPrimitiveOverestimationSizeEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    float extraPrimitiveOverestimationSize;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetExtraPrimitiveOverestimationSizeEXT((VkCommandBuffer)&commandBufferId, &extraPrimitiveOverestimationSize, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetExtraPrimitiveOverestimationSize(commandBuffer, extraPrimitiveOverestimationSize);
}

void vt_handle_vkCmdSetDepthClipEnableEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 depthClipEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDepthClipEnableEXT((VkCommandBuffer)&commandBufferId, &depthClipEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDepthClipEnable(commandBuffer, depthClipEnable);
}

void vt_handle_vkCmdSetSampleLocationsEnableEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 sampleLocationsEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetSampleLocationsEnableEXT((VkCommandBuffer)&commandBufferId, &sampleLocationsEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetSampleLocationsEnable(commandBuffer, sampleLocationsEnable);
}

void vt_handle_vkCmdSetColorBlendAdvancedEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t firstAttachment;
    uint32_t attachmentCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetColorBlendAdvancedEXT((VkCommandBuffer)&commandBufferId, &firstAttachment, &attachmentCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkColorBlendAdvancedEXT, colorBlendAdvanced, attachmentCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetColorBlendAdvancedEXT(VK_NULL_HANDLE, NULL, NULL, colorBlendAdvanced, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetColorBlendAdvanced(commandBuffer, firstAttachment, attachmentCount, colorBlendAdvanced);
}

void vt_handle_vkCmdSetProvokingVertexModeEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkProvokingVertexModeEXT provokingVertexMode;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetProvokingVertexModeEXT((VkCommandBuffer)&commandBufferId, &provokingVertexMode, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetProvokingVertexMode(commandBuffer, provokingVertexMode);
}

void vt_handle_vkCmdSetLineRasterizationModeEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkLineRasterizationModeEXT lineRasterizationMode;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetLineRasterizationModeEXT((VkCommandBuffer)&commandBufferId, &lineRasterizationMode, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetLineRasterizationMode(commandBuffer, lineRasterizationMode);
}

void vt_handle_vkCmdSetLineStippleEnableEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 stippledLineEnable;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetLineStippleEnableEXT((VkCommandBuffer)&commandBufferId, &stippledLineEnable, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetLineStippleEnable(commandBuffer, stippledLineEnable);
}

void vt_handle_vkCmdSetDepthClipNegativeOneToOneEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBool32 negativeOneToOne;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetDepthClipNegativeOneToOneEXT((VkCommandBuffer)&commandBufferId, &negativeOneToOne, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetDepthClipNegativeOneToOne(commandBuffer, negativeOneToOne);
}

void vt_handle_vkCmdCopyBuffer2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkCopyBufferInfo2 copyBufferInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyBuffer2((VkCommandBuffer)&commandBufferId, &copyBufferInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdCopyBuffer2(commandBuffer, &copyBufferInfo);
}

void vt_handle_vkCmdCopyImage2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkCopyImageInfo2 copyImageInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyImage2((VkCommandBuffer)&commandBufferId, &copyImageInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdCopyImage2(commandBuffer, &copyImageInfo);
}

void vt_handle_vkCmdBlitImage2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkBlitImageInfo2 blitImageInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBlitImage2((VkCommandBuffer)&commandBufferId, &blitImageInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdBlitImage2(commandBuffer, &blitImageInfo);
}

void vt_handle_vkCmdCopyBufferToImage2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkCopyBufferToImageInfo2 copyBufferToImageInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyBufferToImage2((VkCommandBuffer)&commandBufferId, &copyBufferToImageInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    if (context->textureDecoder && TextureDecoder_containsImage(context->textureDecoder, copyBufferToImageInfo.dstImage)) {
        if (copyBufferToImageInfo.pRegions[0].imageSubresource.mipLevel > 0) return;
        TextureDecoder_copyBufferToImage(context->textureDecoder, commandBuffer, copyBufferToImageInfo.srcBuffer, copyBufferToImageInfo.dstImage, copyBufferToImageInfo.dstImageLayout, copyBufferToImageInfo.pRegions[0].bufferOffset);
    }
    else vulkanWrapper.vkCmdCopyBufferToImage2(commandBuffer, &copyBufferToImageInfo);
}

void vt_handle_vkCmdCopyImageToBuffer2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkCopyImageToBufferInfo2 copyImageToBufferInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdCopyImageToBuffer2((VkCommandBuffer)&commandBufferId, &copyImageToBufferInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdCopyImageToBuffer2(commandBuffer, &copyImageToBufferInfo);
}

void vt_handle_vkCmdResolveImage2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkResolveImageInfo2 resolveImageInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdResolveImage2((VkCommandBuffer)&commandBufferId, &resolveImageInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdResolveImage2(commandBuffer, &resolveImageInfo);
}

void vt_handle_vkCmdSetColorWriteEnableEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t attachmentCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetColorWriteEnableEXT((VkCommandBuffer)&commandBufferId, &attachmentCount, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkBool32, colorWriteEnables, attachmentCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetColorWriteEnableEXT(VK_NULL_HANDLE, NULL, colorWriteEnables, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdSetColorWriteEnable(commandBuffer, attachmentCount, colorWriteEnables);
}

void vt_handle_vkCmdSetEvent2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t eventId;
    VkDependencyInfo dependencyInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdSetEvent2((VkCommandBuffer)&commandBufferId, (VkEvent)&eventId, &dependencyInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkEvent, event, eventId, VK_OBJECT_TYPE_EVENT, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdSetEvent2(commandBuffer, event, &dependencyInfo);
}

void vt_handle_vkCmdResetEvent2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint64_t eventId;
    VkPipelineStageFlags2 stageMask;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdResetEvent2((VkCommandBuffer)&commandBufferId, (VkEvent)&eventId, &stageMask, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkEvent, event, eventId, VK_OBJECT_TYPE_EVENT, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdResetEvent2(commandBuffer, event, stageMask);
}

void vt_handle_vkCmdWaitEvents2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    uint32_t eventCount;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdWaitEvents2((VkCommandBuffer)&commandBufferId, &eventCount, VK_NULL_HANDLE, NULL, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    VT_REQUEST_ARRAY(VkEvent, events, eventCount);
    VT_REQUEST_ARRAY(VkDependencyInfo, dependencyInfos, eventCount);
    VT_REQUEST_DECODE(vt_unserialize_vkCmdWaitEvents2(VK_NULL_HANDLE, NULL, events, dependencyInfos, &_vt_cursor, &context->memoryPool));

    vulkanWrapper.vkCmdWaitEvents2(commandBuffer, eventCount, events, dependencyInfos);
}

void vt_handle_vkCmdPipelineBarrier2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkDependencyInfo dependencyInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdPipelineBarrier2((VkCommandBuffer)&commandBufferId, &dependencyInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdPipelineBarrier2(commandBuffer, &dependencyInfo);
}

void vt_handle_vkQueueSubmit2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_QUEUE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t queueId;
    uint32_t submitCount;
    uint64_t fenceId;

    VT_REQUEST_DECODE(vt_unserialize_vkQueueSubmit2((VkQueue)&queueId, &submitCount, NULL, (VkFence)&fenceId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkQueue, queue, queueId, VK_OBJECT_TYPE_QUEUE, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkFence, fence, fenceId, VK_OBJECT_TYPE_FENCE, VORTEK_HANDLE_ROLE_VULKAN, true, true, true);

    bool clientWaiting = RingBuffer_hasStatus(context->clientRing, RING_STATUS_WAIT);
    if (context->textureDecoder) TextureDecoder_decodeAll(context->textureDecoder);

    VT_REQUEST_ARRAY(VkSubmitInfo2, submits, submitCount);
    VT_REQUEST_DECODE(vt_unserialize_vkQueueSubmit2(VK_NULL_HANDLE, NULL, submits, VK_NULL_HANDLE, &_vt_cursor, &context->memoryPool));

    VkResult result = vulkanWrapper.vkQueueSubmit2(queue, submitCount, submits, fence);
    if (result == VK_ERROR_DEVICE_LOST) context->status = result;

    if (clientWaiting) VT_REQUEST_SEND(result, NULL, 0);
}

void vt_handle_vkCmdWriteTimestamp2(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkPipelineStageFlags2 stage;
    uint64_t queryPoolId;
    uint32_t query;

    VT_REQUEST_DECODE(vt_unserialize_vkCmdWriteTimestamp2((VkCommandBuffer)&commandBufferId, &stage, (VkQueryPool)&queryPoolId, &query, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);
    VT_REQUEST_HANDLE(VkQueryPool, queryPool, queryPoolId, VK_OBJECT_TYPE_QUERY_POOL, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdWriteTimestamp2(commandBuffer, stage, queryPool, query);
}

void vt_handle_vkCmdBeginRendering(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;
    VkRenderingInfo renderingInfo = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkCmdBeginRendering((VkCommandBuffer)&commandBufferId, &renderingInfo, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdBeginRendering(commandBuffer, &renderingInfo);
}

void vt_handle_vkCmdEndRendering(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_SIMPLE(VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t commandBufferId;

    VT_REQUEST_DECODE(vt_unserialize_VkCommandBuffer((VkCommandBuffer)&commandBufferId, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkCommandBuffer, commandBuffer, commandBufferId, VK_OBJECT_TYPE_COMMAND_BUFFER, VORTEK_HANDLE_ROLE_VULKAN, true, true, false);

    vulkanWrapper.vkCmdEndRendering(commandBuffer);
}

void vt_handle_vkGetShaderModuleIdentifierEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    uint64_t shaderModuleId;
    VkShaderModuleIdentifierEXT identifier = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetShaderModuleIdentifierEXT((VkDevice)&deviceId, (VkShaderModule)&shaderModuleId, &identifier, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);
    VT_REQUEST_HANDLE(ShaderModule*, shaderModule, shaderModuleId, VK_OBJECT_TYPE_UNKNOWN, VORTEK_HANDLE_ROLE_SHADER_MODULE, true, true, false);

    vulkanWrapper.vkGetShaderModuleIdentifier(device, shaderModule->module, &identifier);

    VT_SERIALIZE_CMD(VkShaderModuleIdentifierEXT, &identifier);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

void vt_handle_vkGetShaderModuleCreateInfoIdentifierEXT(VkContext* context) {
    VT_REQUEST_BEGIN(context);
    VT_REQUEST_SCOPE_COMMAND(VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN);
    uint64_t deviceId;
    VkShaderModuleCreateInfo createInfo = {0};
    VkShaderModuleIdentifierEXT identifier = {0};

    VT_REQUEST_DECODE(vt_unserialize_vkGetShaderModuleCreateInfoIdentifierEXT((VkDevice)&deviceId, &createInfo, &identifier, &_vt_cursor, &context->memoryPool));
    VT_REQUEST_HANDLE(VkDevice, device, deviceId, VK_OBJECT_TYPE_DEVICE, VORTEK_HANDLE_ROLE_VULKAN, true, false, false);

    vulkanWrapper.vkGetShaderModuleCreateInfoIdentifier(device, &createInfo, &identifier);

    VT_SERIALIZE_CMD(VkShaderModuleIdentifierEXT, &identifier);
    VT_REQUEST_SEND(VK_SUCCESS, outputBuffer, bufferSize);
}

HandleRequestFunc handleRequestFuncs[] = {
    vt_handle_vkCreateInstance,
    vt_handle_vkDestroyInstance,
    vt_handle_vkEnumeratePhysicalDevices,
    vt_handle_vkGetPhysicalDeviceProperties,
    vt_handle_vkGetPhysicalDeviceQueueFamilyProperties,
    vt_handle_vkGetPhysicalDeviceMemoryProperties,
    vt_handle_vkGetPhysicalDeviceFeatures,
    vt_handle_vkGetPhysicalDeviceFormatProperties,
    vt_handle_vkGetPhysicalDeviceImageFormatProperties,
    vt_handle_vkCreateDevice,
    vt_handle_vkDestroyDevice,
    vt_handle_vkEnumerateInstanceVersion,
    vt_handle_vkEnumerateInstanceExtensionProperties,
    vt_handle_vkEnumerateDeviceExtensionProperties,
    vt_handle_vkGetDeviceQueue,
    vt_handle_vkQueueSubmit,
    vt_handle_vkQueueWaitIdle,
    vt_handle_vkDeviceWaitIdle,
    vt_handle_vkAllocateMemory,
    vt_handle_vkFreeMemory,
    vt_handle_vkMapMemory,
    vt_handle_vkUnmapMemory,
    vt_handle_vkFlushMappedMemoryRanges,
    vt_handle_vkInvalidateMappedMemoryRanges,
    vt_handle_vkGetDeviceMemoryCommitment,
    vt_handle_vkGetBufferMemoryRequirements,
    vt_handle_vkBindBufferMemory,
    vt_handle_vkGetImageMemoryRequirements,
    vt_handle_vkBindImageMemory,
    vt_handle_vkGetImageSparseMemoryRequirements,
    vt_handle_vkGetPhysicalDeviceSparseImageFormatProperties,
    vt_handle_vkQueueBindSparse,
    vt_handle_vkCreateFence,
    vt_handle_vkDestroyFence,
    vt_handle_vkResetFences,
    vt_handle_vkGetFenceStatus,
    vt_handle_vkWaitForFences,
    vt_handle_vkCreateSemaphore,
    vt_handle_vkDestroySemaphore,
    vt_handle_vkCreateEvent,
    vt_handle_vkDestroyEvent,
    vt_handle_vkGetEventStatus,
    vt_handle_vkSetEvent,
    vt_handle_vkResetEvent,
    vt_handle_vkCreateQueryPool,
    vt_handle_vkDestroyQueryPool,
    vt_handle_vkGetQueryPoolResults,
    vt_handle_vkResetQueryPool,
    vt_handle_vkCreateBuffer,
    vt_handle_vkDestroyBuffer,
    vt_handle_vkCreateBufferView,
    vt_handle_vkDestroyBufferView,
    vt_handle_vkCreateImage,
    vt_handle_vkDestroyImage,
    vt_handle_vkGetImageSubresourceLayout,
    vt_handle_vkCreateImageView,
    vt_handle_vkDestroyImageView,
    vt_handle_vkCreateShaderModule,
    vt_handle_vkDestroyShaderModule,
    vt_handle_vkCreatePipelineCache,
    vt_handle_vkDestroyPipelineCache,
    vt_handle_vkGetPipelineCacheData,
    vt_handle_vkMergePipelineCaches,
    vt_handle_vkCreateGraphicsPipelines,
    vt_handle_vkCreateComputePipelines,
    vt_handle_vkDestroyPipeline,
    vt_handle_vkCreatePipelineLayout,
    vt_handle_vkDestroyPipelineLayout,
    vt_handle_vkCreateSampler,
    vt_handle_vkDestroySampler,
    vt_handle_vkCreateDescriptorSetLayout,
    vt_handle_vkDestroyDescriptorSetLayout,
    vt_handle_vkCreateDescriptorPool,
    vt_handle_vkDestroyDescriptorPool,
    vt_handle_vkResetDescriptorPool,
    vt_handle_vkAllocateDescriptorSets,
    vt_handle_vkFreeDescriptorSets,
    vt_handle_vkUpdateDescriptorSets,
    vt_handle_vkCreateFramebuffer,
    vt_handle_vkDestroyFramebuffer,
    vt_handle_vkCreateRenderPass,
    vt_handle_vkDestroyRenderPass,
    vt_handle_vkGetRenderAreaGranularity,
    vt_handle_vkCreateCommandPool,
    vt_handle_vkDestroyCommandPool,
    vt_handle_vkResetCommandPool,
    vt_handle_vkAllocateCommandBuffers,
    vt_handle_vkFreeCommandBuffers,
    vt_handle_vkBeginCommandBuffer,
    vt_handle_vkEndCommandBuffer,
    vt_handle_vkResetCommandBuffer,
    vt_handle_vkCmdBindPipeline,
    vt_handle_vkCmdSetViewport,
    vt_handle_vkCmdSetScissor,
    vt_handle_vkCmdSetLineWidth,
    vt_handle_vkCmdSetDepthBias,
    vt_handle_vkCmdSetBlendConstants,
    vt_handle_vkCmdSetDepthBounds,
    vt_handle_vkCmdSetStencilCompareMask,
    vt_handle_vkCmdSetStencilWriteMask,
    vt_handle_vkCmdSetStencilReference,
    vt_handle_vkCmdBindDescriptorSets,
    vt_handle_vkCmdBindIndexBuffer,
    vt_handle_vkCmdBindVertexBuffers,
    vt_handle_vkCmdDraw,
    vt_handle_vkCmdDrawIndexed,
    vt_handle_vkCmdDrawIndirect,
    vt_handle_vkCmdDrawIndexedIndirect,
    vt_handle_vkCmdDispatch,
    vt_handle_vkCmdDispatchIndirect,
    vt_handle_vkCmdCopyBuffer,
    vt_handle_vkCmdCopyImage,
    vt_handle_vkCmdBlitImage,
    vt_handle_vkCmdCopyBufferToImage,
    vt_handle_vkCmdCopyImageToBuffer,
    vt_handle_vkCmdUpdateBuffer,
    vt_handle_vkCmdFillBuffer,
    vt_handle_vkCmdClearColorImage,
    vt_handle_vkCmdClearDepthStencilImage,
    vt_handle_vkCmdClearAttachments,
    vt_handle_vkCmdResolveImage,
    vt_handle_vkCmdSetEvent,
    vt_handle_vkCmdResetEvent,
    vt_handle_vkCmdWaitEvents,
    vt_handle_vkCmdPipelineBarrier,
    vt_handle_vkCmdBeginQuery,
    vt_handle_vkCmdEndQuery,
    vt_handle_vkCmdBeginConditionalRenderingEXT,
    vt_handle_vkCmdEndConditionalRenderingEXT,
    vt_handle_vkCmdResetQueryPool,
    vt_handle_vkCmdWriteTimestamp,
    vt_handle_vkCmdCopyQueryPoolResults,
    vt_handle_vkCmdPushConstants,
    vt_handle_vkCmdBeginRenderPass,
    vt_handle_vkCmdNextSubpass,
    vt_handle_vkCmdEndRenderPass,
    vt_handle_vkCmdExecuteCommands,
    vt_handle_vkGetPhysicalDeviceSurfaceCapabilitiesKHR,
    vt_handle_vkGetPhysicalDeviceSurfaceFormatsKHR,
    vt_handle_vkGetPhysicalDeviceSurfacePresentModesKHR,
    vt_handle_vkCreateSwapchainKHR,
    vt_handle_vkDestroySwapchainKHR,
    vt_handle_vkGetSwapchainImagesKHR,
    vt_handle_vkAcquireNextImageKHR,
    vt_handle_vkQueuePresentKHR,
    vt_handle_vkGetPhysicalDeviceFeatures2,
    vt_handle_vkGetPhysicalDeviceProperties2,
    vt_handle_vkGetPhysicalDeviceFormatProperties2,
    vt_handle_vkGetPhysicalDeviceImageFormatProperties2,
    vt_handle_vkGetPhysicalDeviceQueueFamilyProperties2,
    vt_handle_vkGetPhysicalDeviceMemoryProperties2,
    vt_handle_vkGetPhysicalDeviceSparseImageFormatProperties2,
    vt_handle_vkCmdPushDescriptorSetKHR,
    vt_handle_vkTrimCommandPool,
    vt_handle_vkGetPhysicalDeviceExternalBufferProperties,
    vt_handle_vkGetMemoryFdKHR,
    vt_handle_vkGetPhysicalDeviceExternalSemaphoreProperties,
    vt_handle_vkGetSemaphoreFdKHR,
    vt_handle_vkGetPhysicalDeviceExternalFenceProperties,
    vt_handle_vkGetFenceFdKHR,
    vt_handle_vkEnumeratePhysicalDeviceGroups,
    vt_handle_vkGetDeviceGroupPeerMemoryFeatures,
    vt_handle_vkBindBufferMemory2,
    vt_handle_vkBindImageMemory2,
    vt_handle_vkCmdSetDeviceMask,
    vt_handle_vkAcquireNextImage2KHR,
    vt_handle_vkCmdDispatchBase,
    vt_handle_vkGetPhysicalDevicePresentRectanglesKHR,
    vt_handle_vkCmdSetSampleLocationsEXT,
    vt_handle_vkGetPhysicalDeviceMultisamplePropertiesEXT,
    vt_handle_vkGetBufferMemoryRequirements2,
    vt_handle_vkGetImageMemoryRequirements2,
    vt_handle_vkGetImageSparseMemoryRequirements2,
    vt_handle_vkGetDeviceBufferMemoryRequirements,
    vt_handle_vkGetDeviceImageMemoryRequirements,
    vt_handle_vkGetDeviceImageSparseMemoryRequirements,
    vt_handle_vkCreateSamplerYcbcrConversion,
    vt_handle_vkDestroySamplerYcbcrConversion,
    vt_handle_vkGetDeviceQueue2,
    vt_handle_vkGetDescriptorSetLayoutSupport,
    vt_handle_vkGetPhysicalDeviceCalibrateableTimeDomainsKHR,
    vt_handle_vkGetCalibratedTimestampsKHR,
    vt_handle_vkCreateRenderPass2,
    vt_handle_vkCmdBeginRenderPass2,
    vt_handle_vkCmdNextSubpass2,
    vt_handle_vkCmdEndRenderPass2,
    vt_handle_vkGetSemaphoreCounterValue,
    vt_handle_vkWaitSemaphores,
    vt_handle_vkSignalSemaphore,
    vt_handle_vkCmdDrawIndirectCount,
    vt_handle_vkCmdDrawIndexedIndirectCount,
    vt_handle_vkCmdBindTransformFeedbackBuffersEXT,
    vt_handle_vkCmdBeginTransformFeedbackEXT,
    vt_handle_vkCmdEndTransformFeedbackEXT,
    vt_handle_vkCmdBeginQueryIndexedEXT,
    vt_handle_vkCmdEndQueryIndexedEXT,
    vt_handle_vkCmdDrawIndirectByteCountEXT,
    vt_handle_vkGetBufferOpaqueCaptureAddress,
    vt_handle_vkGetBufferDeviceAddress,
    vt_handle_vkGetDeviceMemoryOpaqueCaptureAddress,
    vt_handle_vkCmdSetLineStippleKHR,
    vt_handle_vkCmdSetCullMode,
    vt_handle_vkCmdSetFrontFace,
    vt_handle_vkCmdSetPrimitiveTopology,
    vt_handle_vkCmdSetViewportWithCount,
    vt_handle_vkCmdSetScissorWithCount,
    vt_handle_vkCmdBindVertexBuffers2,
    vt_handle_vkCmdSetDepthTestEnable,
    vt_handle_vkCmdSetDepthWriteEnable,
    vt_handle_vkCmdSetDepthCompareOp,
    vt_handle_vkCmdSetDepthBoundsTestEnable,
    vt_handle_vkCmdSetStencilTestEnable,
    vt_handle_vkCmdSetStencilOp,
    vt_handle_vkCmdSetRasterizerDiscardEnable,
    vt_handle_vkCmdSetDepthBiasEnable,
    vt_handle_vkCmdSetPrimitiveRestartEnable,
    vt_handle_vkCmdSetTessellationDomainOriginEXT,
    vt_handle_vkCmdSetDepthClampEnableEXT,
    vt_handle_vkCmdSetPolygonModeEXT,
    vt_handle_vkCmdSetRasterizationSamplesEXT,
    vt_handle_vkCmdSetSampleMaskEXT,
    vt_handle_vkCmdSetAlphaToCoverageEnableEXT,
    vt_handle_vkCmdSetAlphaToOneEnableEXT,
    vt_handle_vkCmdSetLogicOpEnableEXT,
    vt_handle_vkCmdSetColorBlendEnableEXT,
    vt_handle_vkCmdSetColorBlendEquationEXT,
    vt_handle_vkCmdSetColorWriteMaskEXT,
    vt_handle_vkCmdSetRasterizationStreamEXT,
    vt_handle_vkCmdSetConservativeRasterizationModeEXT,
    vt_handle_vkCmdSetExtraPrimitiveOverestimationSizeEXT,
    vt_handle_vkCmdSetDepthClipEnableEXT,
    vt_handle_vkCmdSetSampleLocationsEnableEXT,
    vt_handle_vkCmdSetColorBlendAdvancedEXT,
    vt_handle_vkCmdSetProvokingVertexModeEXT,
    vt_handle_vkCmdSetLineRasterizationModeEXT,
    vt_handle_vkCmdSetLineStippleEnableEXT,
    vt_handle_vkCmdSetDepthClipNegativeOneToOneEXT,
    vt_handle_vkCmdCopyBuffer2,
    vt_handle_vkCmdCopyImage2,
    vt_handle_vkCmdBlitImage2,
    vt_handle_vkCmdCopyBufferToImage2,
    vt_handle_vkCmdCopyImageToBuffer2,
    vt_handle_vkCmdResolveImage2,
    vt_handle_vkCmdSetColorWriteEnableEXT,
    vt_handle_vkCmdSetEvent2,
    vt_handle_vkCmdResetEvent2,
    vt_handle_vkCmdWaitEvents2,
    vt_handle_vkCmdPipelineBarrier2,
    vt_handle_vkQueueSubmit2,
    vt_handle_vkCmdWriteTimestamp2,
    vt_handle_vkCmdBeginRendering,
    vt_handle_vkCmdEndRendering,
    vt_handle_vkGetShaderModuleIdentifierEXT,
    vt_handle_vkGetShaderModuleCreateInfoIdentifierEXT,
};

HandleRequestFunc getHandleRequestFunc(short requestCode) {
#if !VORTEK_REQUEST_HANDLE_AUTHORITY_COMPLETE
    (void)requestCode;
    return vt_handle_authority_incomplete;
#else
    int index = requestCode - REQUEST_CODE_VK_CALL_START;
    return index >= 0 && index < REQUEST_CODE_VK_CALL_COUNT ? handleRequestFuncs[index] : NULL;
#endif
}
