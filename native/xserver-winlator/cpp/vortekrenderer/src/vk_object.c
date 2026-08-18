#include "vk_object.h"

static ArrayList cachedObjects = {0};

VkObject vkNullObject = {
    .id = VKOBJECT_NULL_ID,
    .type = VK_OBJECT_TYPE_UNKNOWN,
    .tag = NULL,
    .handle = NULL
};

static VkObject* internalCreate(VkObjectType type, uint64_t id) {
    VkObject* object = calloc(1, sizeof(VkObject));
    object->type = type;
    object->id = id;
    object->handle = VK_NULL_HANDLE;
    return object;
}

static VkObject* getCachedObject(uint64_t id) {
    for (int i = 0; i < cachedObjects.size; i++) {
        VkObject* object = cachedObjects.elements[i];
        if (object->id == id) return object;
    }

    return &vkNullObject;
}

VkObject* VkObject_create(VkObjectType type, uint64_t id) {
    if (type == VK_OBJECT_TYPE_PHYSICAL_DEVICE) {
        VkObject* physicalDeviceObject = getCachedObject(id);
        if (!VKOBJECT_IS_NULL(physicalDeviceObject)) return physicalDeviceObject;

        physicalDeviceObject = internalCreate(type, id);
        ArrayList_add(&cachedObjects, physicalDeviceObject);
        return physicalDeviceObject;
    }
    else if (type == VK_OBJECT_TYPE_QUEUE) {
        VkObject* queueObject = getCachedObject(id);
        if (!VKOBJECT_IS_NULL(queueObject)) return queueObject;

        queueObject = internalCreate(type, id);
        ArrayList_add(&cachedObjects, queueObject);
        return queueObject;
    }

    return internalCreate(type, id);
}

void* VkObject_toHandle(VkObject* object) {
    if (object->handle) return object->handle;

    char* handle = calloc(2, sizeof(uint64_t));
    *(uint64_t*)(handle + sizeof(uint64_t)) = (uint64_t)object;
    object->handle = handle;
    return handle;
}

void* VkObject_fromId(uint64_t id) {
    return id != VKOBJECT_NULL_ID ? (void*)id : VK_NULL_HANDLE;
}

VkObject* VkObject_fromHandle(void* handle) {
    if (handle) {
        uint64_t ptr = *(uint64_t*)((char*)handle + sizeof(uint64_t));
        if (ptr) return (VkObject*)ptr;
    }
    return &vkNullObject;
}

void VkObject_free(VkObject* object) {
    if (!VKOBJECT_IS_NULL(object)) {
        MEMFREE(object->handle);
        MEMFREE(object);
    }
}

VkObjectAuthority* VkObjectAuthority_create(
    uint64_t contextGeneration,
    size_t capacity) {
    return VortekHandleRegistry_create(contextGeneration, capacity);
}

void VkObjectAuthority_close(VkObjectAuthority* authority) {
    VortekHandleRegistry_close(authority);
}

void VkObjectAuthority_beginClose(VkObjectAuthority* authority) {
    VortekHandleRegistry_beginClose(authority);
}

bool VkObjectAuthority_destroy(VkObjectAuthority* authority) {
    return VortekHandleRegistry_destroy(authority);
}

VortekHandleStatus VkObjectAuthority_drainNext(
    VkObjectAuthority* authority,
    VortekHandleDrainScope scope,
    uint64_t scopeToken,
    VortekHandleDrainValue* valueOut) {
    return VortekHandleRegistry_drainNext(
        authority, scope, scopeToken, valueOut);
}

VortekHandleStatus VkObjectAuthority_publishVulkan(
    VkObjectAuthority* authority,
    VkObjectType type,
    uint64_t hostHandleBits,
    VortekHandleOwner owner,
    uint64_t* wireTokenOut) {
    return VortekHandleRegistry_registerVulkan(
        authority, type, hostHandleBits, owner, wireTokenOut);
}

VortekHandleStatus VkObjectAuthority_publishVulkanBatch(
    VkObjectAuthority* authority,
    VkObjectType type,
    const uint64_t* hostHandleBits,
    size_t count,
    VortekHandleOwner owner,
    uint64_t* wireTokensOut) {
    return VortekHandleRegistry_registerVulkanBatch(
        authority, type, hostHandleBits, count, owner, wireTokensOut);
}

VortekHandleStatus VkObjectAuthority_setDeviceNullDescriptor(
    VkObjectAuthority* authority,
    uint64_t deviceToken,
    bool enabled) {
    return VortekHandleRegistry_setDeviceNullDescriptor(
        authority, deviceToken, enabled);
}

VortekHandleStatus VkObjectAuthority_acquireDeviceLease(
    VkObjectAuthority* authority,
    uint64_t deviceToken,
    uint64_t contextGeneration,
    VortekDeviceLease* leaseOut) {
    return VortekHandleRegistry_acquireDeviceLease(
        authority, deviceToken, contextGeneration, leaseOut);
}

VortekHandleStatus VkObjectAuthority_releaseDeviceLease(
    VortekDeviceLease* lease) {
    return VortekHandleRegistry_releaseDeviceLease(lease);
}

VortekHandleStatus VkObjectAuthority_beginDeviceRetirement(
    VkObjectAuthority* authority,
    uint64_t deviceToken,
    uint64_t contextGeneration) {
    return VortekHandleRegistry_beginDeviceRetirement(
        authority, deviceToken, contextGeneration);
}

VortekHandleStatus VkObjectAuthority_beginInstanceRetirement(
    VkObjectAuthority* authority,
    uint64_t instanceToken,
    uint64_t contextGeneration) {
    return VortekHandleRegistry_beginInstanceRetirement(
        authority, instanceToken, contextGeneration);
}

VortekHandleStatus VkObjectAuthority_publishWrapper(
    VkObjectAuthority* authority,
    VortekHandleRole role,
    const void* wrapper,
    VortekHandleOwner owner,
    uint64_t* wireTokenOut) {
    return VortekHandleRegistry_registerWrapper(
        authority, role, wrapper, owner, wireTokenOut);
}

VortekHandleStatus VkObjectAuthority_resolve(
    VkObjectAuthority* authority,
    uint64_t wireToken,
    const VortekHandleExpectation* expectation,
    VortekHandleValue* valueOut) {
    return VortekHandleRegistry_lookup(
        authority, wireToken, expectation, valueOut);
}

VortekHandleStatus VkObjectAuthority_tombstone(
    VkObjectAuthority* authority,
    uint64_t wireToken,
    const VortekHandleExpectation* expectation,
    VortekHandleValue* retiredValueOut) {
    return VortekHandleRegistry_unregister(
        authority, wireToken, expectation, retiredValueOut);
}

VortekHandleStatus VkObjectAuthority_tombstoneBatch(
    VkObjectAuthority* authority,
    const uint64_t* wireTokens,
    size_t count,
    const VortekHandleExpectation* expectation) {
    return VortekHandleRegistry_unregisterBatch(
        authority, wireTokens, count, expectation);
}

VortekHandleStatus VkObjectAuthority_rollbackBatchWithLease(
    VkObjectAuthority* authority,
    const uint64_t* wireTokens,
    size_t count,
    const VortekHandleExpectation* expectation,
    const VortekDeviceLease* lease) {
    return VortekHandleRegistry_rollbackBatchWithLease(
        authority, wireTokens, count, expectation, lease);
}

VortekHandleStatus VkObjectAuthority_validateBatch(
    VkObjectAuthority* authority,
    const uint64_t* wireTokens,
    size_t count,
    const VortekHandleExpectation* expectation) {
    return VortekHandleRegistry_validateBatch(
        authority, wireTokens, count, expectation);
}

VortekHandleStatus VkObjectAuthority_tombstoneChildren(
    VkObjectAuthority* authority,
    uint64_t parent) {
    return VortekHandleRegistry_unregisterChildren(authority, parent);
}
