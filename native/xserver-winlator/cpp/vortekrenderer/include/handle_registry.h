#ifndef POCKETREALM_VORTEK_HANDLE_REGISTRY_H
#define POCKETREALM_VORTEK_HANDLE_REGISTRY_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "vulkan/vulkan.h"

/*
 * A guest-visible handle remains exactly one 64-bit wire field, but it is an
 * opaque authority token.  It is never a host pointer or Vulkan handle cast.
 */
typedef uint64_t VortekHandleToken;

#if defined(__cplusplus)
static_assert(sizeof(VortekHandleToken) == 8, "Vortek handle wire field must remain 64-bit");
#else
_Static_assert(sizeof(VortekHandleToken) == 8, "Vortek handle wire field must remain 64-bit");
#endif

typedef enum VortekHandleRole {
    VORTEK_HANDLE_ROLE_VULKAN = 1,
    VORTEK_HANDLE_ROLE_RESOURCE_MEMORY = 2,
    VORTEK_HANDLE_ROLE_SHADER_MODULE = 3,
    VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN = 4,
    /* VkSurfaceKHR is an X11 window id on this protocol, not a Vulkan handle. */
    VORTEK_HANDLE_ROLE_WINDOW_ID = 5,
} VortekHandleRole;

typedef enum VortekHandleStatus {
    VORTEK_HANDLE_OK = 0,
    VORTEK_HANDLE_NULL,
    VORTEK_HANDLE_UNKNOWN,
    VORTEK_HANDLE_STALE,
    VORTEK_HANDLE_WRONG_GENERATION,
    VORTEK_HANDLE_WRONG_ROLE,
    VORTEK_HANDLE_WRONG_TYPE,
    VORTEK_HANDLE_WRONG_OWNER,
    VORTEK_HANDLE_STALE_OWNER,
    VORTEK_HANDLE_INVALID_ARGUMENT,
    VORTEK_HANDLE_CAPACITY,
    VORTEK_HANDLE_EXHAUSTED,
    VORTEK_HANDLE_CLOSED,
    VORTEK_HANDLE_RETIRING,
    VORTEK_HANDLE_WINDOW_REJECTED,
} VortekHandleStatus;

typedef struct VortekHandleRegistry VortekHandleRegistry;

typedef struct VortekHandleOwner {
    VortekHandleToken instance;
    VortekHandleToken device;
    /* Optional lifetime parent (descriptor pool, command pool or swapchain).
     * Parent retirement immediately makes every child stale. */
    VortekHandleToken parent;
} VortekHandleOwner;

typedef struct VortekHandleExpectation {
    uint64_t contextGeneration;
    VortekHandleRole role;
    VkObjectType vulkanType;
    VortekHandleOwner owner;
    bool requireInstanceOwner;
    bool requireDeviceOwner;
    bool requireParentOwner;
    bool allowNull;
} VortekHandleExpectation;

typedef struct VortekHandleValue {
    VortekHandleToken token;
    uint64_t contextGeneration;
    VortekHandleRole role;
    VkObjectType vulkanType;
    VortekHandleOwner owner;
    /* Host Vulkan handle bits or an app-owned wrapper pointer represented as
     * uintptr_t.  This value is never placed on the guest wire. */
    uint64_t hostValue;
    /* Meaningful only for a live VK_OBJECT_TYPE_DEVICE authority entry. */
    bool nullDescriptorEnabled;
} VortekHandleValue;

typedef enum VortekHandleDrainScope {
    VORTEK_HANDLE_DRAIN_ALL = 0,
    VORTEK_HANDLE_DRAIN_DEVICE = 1,
    VORTEK_HANDLE_DRAIN_INSTANCE = 2,
} VortekHandleDrainScope;

typedef struct VortekHandleDrainValue {
    VortekHandleValue value;
    /* Resolved while the owning device entry is still live. */
    uint64_t hostDeviceValue;
    /* Exactly one drained value per device claims the pre-destroy idle wait. */
    bool waitDevice;
} VortekHandleDrainValue;

/* A lease pins one exact device token/generation across an asynchronous host
 * call.  Callers must release every active lease on every completion/cancel
 * path; leases are never serialized to the guest. */
typedef struct VortekDeviceLease {
    VortekHandleRegistry* registry;
    VortekHandleToken device;
    uint64_t contextGeneration;
    uint64_t hostDeviceBits;
    bool active;
} VortekDeviceLease;

/* Invoked for the distinct WINDOW_ID role after numeric/range validation.
 * The callback must prove that the X window is live in this display/context
 * generation and belongs to the supplied instance authority.  It runs while
 * the authority is locked and therefore must not call back into the registry. */
/* Returns the window's stable, process-wide lifetime identity, or zero when
 * the tuple is not currently authoritative. */
typedef uint64_t (*VortekWindowIdValidator)(
    void* userdata,
    uint64_t contextGeneration,
    VortekHandleToken instanceOwner,
    uint32_t windowId);

#define VORTEK_HANDLE_REGISTRY_MAX_CAPACITY (1u << 20)

VortekHandleRegistry* VortekHandleRegistry_create(
    uint64_t contextGeneration,
    size_t capacity);
void VortekHandleRegistry_close(VortekHandleRegistry* registry);
void VortekHandleRegistry_beginClose(VortekHandleRegistry* registry);
/* All registry users must be quiesced before destroy; close is the thread-safe
 * operation that starts teardown and rejects subsequent authority requests. */
/* Returns false and keeps the closed registry intact if live host authority
 * was not drained.  Destruction may never silently discard host values. */
bool VortekHandleRegistry_destroy(VortekHandleRegistry* registry);

uint64_t VortekHandleRegistry_generation(const VortekHandleRegistry* registry);
size_t VortekHandleRegistry_liveCount(VortekHandleRegistry* registry);

/* Atomically removes one value in dependency-safe teardown order.  A zeroed
 * value with VORTEK_HANDLE_OK means the selected scope is fully drained.
 * This is the sole authority path allowed after beginClose/close. */
VortekHandleStatus VortekHandleRegistry_drainNext(
    VortekHandleRegistry* registry,
    VortekHandleDrainScope scope,
    VortekHandleToken scopeToken,
    VortekHandleDrainValue* valueOut);

VortekHandleStatus VortekHandleRegistry_registerVulkan(
    VortekHandleRegistry* registry,
    VkObjectType type,
    uint64_t hostHandleBits,
    VortekHandleOwner owner,
    VortekHandleToken* tokenOut);

/* Publishes one uniform Vulkan handle array atomically.  On any failure none
 * of the newly-created tokens remain live and every tokenOut is cleared. */
VortekHandleStatus VortekHandleRegistry_registerVulkanBatch(
    VortekHandleRegistry* registry,
    VkObjectType type,
    const uint64_t* hostHandleBits,
    size_t count,
    VortekHandleOwner owner,
    VortekHandleToken* tokensOut);

VortekHandleStatus VortekHandleRegistry_setDeviceNullDescriptor(
    VortekHandleRegistry* registry,
    VortekHandleToken device,
    bool enabled);

VortekHandleStatus VortekHandleRegistry_acquireDeviceLease(
    VortekHandleRegistry* registry,
    VortekHandleToken device,
    uint64_t contextGeneration,
    VortekDeviceLease* leaseOut);
VortekHandleStatus VortekHandleRegistry_releaseDeviceLease(
    VortekDeviceLease* lease);
/* Pollable cancellation edge for bounded asynchronous waits.  Returns true
 * once close or device/instance retirement has made the lease non-runnable. */
bool VortekHandleRegistry_deviceLeaseShouldCancel(
    const VortekDeviceLease* lease);
VortekHandleStatus VortekHandleRegistry_beginDeviceRetirement(
    VortekHandleRegistry* registry,
    VortekHandleToken device,
    uint64_t contextGeneration);
VortekHandleStatus VortekHandleRegistry_beginInstanceRetirement(
    VortekHandleRegistry* registry,
    VortekHandleToken instance,
    uint64_t contextGeneration);

VortekHandleStatus VortekHandleRegistry_registerWrapper(
    VortekHandleRegistry* registry,
    VortekHandleRole role,
    const void* wrapper,
    VortekHandleOwner owner,
    VortekHandleToken* tokenOut);

VortekHandleStatus VortekHandleRegistry_lookup(
    VortekHandleRegistry* registry,
    VortekHandleToken token,
    const VortekHandleExpectation* expectation,
    VortekHandleValue* valueOut);

VortekHandleStatus VortekHandleRegistry_unregister(
    VortekHandleRegistry* registry,
    VortekHandleToken token,
    const VortekHandleExpectation* expectation,
    VortekHandleValue* retiredValueOut);

/* Atomically validates and retires a uniform token list.  Duplicate, stale,
 * wrong-type or wrong-owner tokens retire nothing. */
VortekHandleStatus VortekHandleRegistry_unregisterBatch(
    VortekHandleRegistry* registry,
    const VortekHandleToken* tokens,
    size_t count,
    const VortekHandleExpectation* expectation);

/* Roll back handles published by an in-flight asynchronous device call.  The
 * active lease is the authority to bypass the normal device-wide mutation
 * gate, and only exact device-owned entries matching expectation are removed. */
VortekHandleStatus VortekHandleRegistry_rollbackBatchWithLease(
    VortekHandleRegistry* registry,
    const VortekHandleToken* tokens,
    size_t count,
    const VortekHandleExpectation* expectation,
    const VortekDeviceLease* lease);

/* Atomically validates a uniform token list without changing liveness. */
VortekHandleStatus VortekHandleRegistry_validateBatch(
    VortekHandleRegistry* registry,
    const VortekHandleToken* tokens,
    size_t count,
    const VortekHandleExpectation* expectation);

VortekHandleStatus VortekHandleRegistry_unregisterChildren(
    VortekHandleRegistry* registry,
    VortekHandleToken parent);

void VortekHandleRegistry_setWindowValidator(
    VortekHandleRegistry* registry,
    VortekWindowIdValidator validator,
    void* userdata);

VortekHandleStatus VortekHandleRegistry_validateWindowId(
    VortekHandleRegistry* registry,
    uint64_t wireValue,
    uint64_t expectedGeneration,
    VortekHandleToken instanceOwner,
    bool allowNull,
    uint32_t* windowIdOut);

const char* VortekHandleStatus_name(VortekHandleStatus status);

#endif
