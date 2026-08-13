#ifndef VK_OBJECT_H
#define VK_OBJECT_H

#include "vortek.h"
#include "handle_registry.h"

#define VKOBJECT_NULL_ID 0
#define VKOBJECT_IS_NULL(obj) (obj == NULL || obj->id == VKOBJECT_NULL_ID)

typedef struct VkObject {
    uint64_t id;
    VkObjectType type;
    void* tag;
    void* handle;
} VkObject;

extern VkObject* VkObject_create(VkObjectType type, uint64_t id);
extern void VkObject_free(VkObject* object);
extern void* VkObject_toHandle(VkObject* object);
extern void* VkObject_fromId(uint64_t id);
extern VkObject* VkObject_fromHandle(void* handle);
extern VkObject vkNullObject;

/*
 * Server-side authority facade.  The legacy VkObject functions above are part
 * of the pinned guest serializer ABI; new server integration must use these
 * routines and must never cast a guest's 64-bit wire value to a host handle.
 * One authority is owned by exactly one VkContext generation.
 */
typedef VortekHandleRegistry VkObjectAuthority;

extern VkObjectAuthority* VkObjectAuthority_create(
    uint64_t contextGeneration,
    size_t capacity);
extern void VkObjectAuthority_close(VkObjectAuthority* authority);
extern void VkObjectAuthority_beginClose(VkObjectAuthority* authority);
extern bool VkObjectAuthority_destroy(VkObjectAuthority* authority);
extern VortekHandleStatus VkObjectAuthority_drainNext(
    VkObjectAuthority* authority,
    VortekHandleDrainScope scope,
    uint64_t scopeToken,
    VortekHandleDrainValue* valueOut);

extern VortekHandleStatus VkObjectAuthority_publishVulkan(
    VkObjectAuthority* authority,
    VkObjectType type,
    uint64_t hostHandleBits,
    VortekHandleOwner owner,
    uint64_t* wireTokenOut);
extern VortekHandleStatus VkObjectAuthority_publishVulkanBatch(
    VkObjectAuthority* authority,
    VkObjectType type,
    const uint64_t* hostHandleBits,
    size_t count,
    VortekHandleOwner owner,
    uint64_t* wireTokensOut);
extern VortekHandleStatus VkObjectAuthority_setDeviceNullDescriptor(
    VkObjectAuthority* authority,
    uint64_t deviceToken,
    bool enabled);
extern VortekHandleStatus VkObjectAuthority_acquireDeviceLease(
    VkObjectAuthority* authority,
    uint64_t deviceToken,
    uint64_t contextGeneration,
    VortekDeviceLease* leaseOut);
extern VortekHandleStatus VkObjectAuthority_releaseDeviceLease(
    VortekDeviceLease* lease);
extern VortekHandleStatus VkObjectAuthority_beginDeviceRetirement(
    VkObjectAuthority* authority,
    uint64_t deviceToken,
    uint64_t contextGeneration);
extern VortekHandleStatus VkObjectAuthority_beginInstanceRetirement(
    VkObjectAuthority* authority,
    uint64_t instanceToken,
    uint64_t contextGeneration);
extern VortekHandleStatus VkObjectAuthority_publishWrapper(
    VkObjectAuthority* authority,
    VortekHandleRole role,
    const void* wrapper,
    VortekHandleOwner owner,
    uint64_t* wireTokenOut);
extern VortekHandleStatus VkObjectAuthority_resolve(
    VkObjectAuthority* authority,
    uint64_t wireToken,
    const VortekHandleExpectation* expectation,
    VortekHandleValue* valueOut);
extern VortekHandleStatus VkObjectAuthority_tombstone(
    VkObjectAuthority* authority,
    uint64_t wireToken,
    const VortekHandleExpectation* expectation,
    VortekHandleValue* retiredValueOut);
extern VortekHandleStatus VkObjectAuthority_tombstoneBatch(
    VkObjectAuthority* authority,
    const uint64_t* wireTokens,
    size_t count,
    const VortekHandleExpectation* expectation);
extern VortekHandleStatus VkObjectAuthority_rollbackBatchWithLease(
    VkObjectAuthority* authority,
    const uint64_t* wireTokens,
    size_t count,
    const VortekHandleExpectation* expectation,
    const VortekDeviceLease* lease);
extern VortekHandleStatus VkObjectAuthority_validateBatch(
    VkObjectAuthority* authority,
    const uint64_t* wireTokens,
    size_t count,
    const VortekHandleExpectation* expectation);
extern VortekHandleStatus VkObjectAuthority_tombstoneChildren(
    VkObjectAuthority* authority, uint64_t parent);

#endif
