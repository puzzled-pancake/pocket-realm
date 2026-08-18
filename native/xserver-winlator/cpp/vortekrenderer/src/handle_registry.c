#include "handle_registry.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
typedef SRWLOCK pthread_mutex_t;
typedef CONDITION_VARIABLE pthread_cond_t;
#define pthread_mutex_init(mutex_, attr_) \
    ((void)(attr_), InitializeSRWLock((mutex_)), 0)
#define pthread_mutex_lock(mutex_) AcquireSRWLockExclusive((mutex_))
#define pthread_mutex_unlock(mutex_) ReleaseSRWLockExclusive((mutex_))
#define pthread_mutex_destroy(mutex_) ((void)(mutex_))
#define pthread_cond_init(cond_, attr_) \
    ((void)(attr_), InitializeConditionVariable((cond_)), 0)
#define pthread_cond_wait(cond_, mutex_) \
    (SleepConditionVariableSRW((cond_), (mutex_), INFINITE, 0) ? 0 : -1)
#define pthread_cond_broadcast(cond_) WakeAllConditionVariable((cond_))
#define pthread_cond_destroy(cond_) ((void)(cond_))
typedef volatile LONG64 VortekAtomicU64;
#define VORTEK_ATOMIC_U64_INIT(value_) ((LONG64)(value_))
static uint64_t atomicLoadU64(VortekAtomicU64* value) {
    return (uint64_t)InterlockedCompareExchange64(value, 0, 0);
}
static bool atomicCompareExchangeU64(
        VortekAtomicU64* value, uint64_t* expected, uint64_t desired) {
    const LONG64 previous = InterlockedCompareExchange64(
            value, (LONG64)desired, (LONG64)*expected);
    if ((uint64_t)previous == *expected) return true;
    *expected = (uint64_t)previous;
    return false;
}
#else
#include <pthread.h>
#include <stdatomic.h>
typedef atomic_uint_fast64_t VortekAtomicU64;
#define VORTEK_ATOMIC_U64_INIT(value_) ATOMIC_VAR_INIT(value_)
static uint64_t atomicLoadU64(VortekAtomicU64* value) {
    return (uint64_t)atomic_load_explicit(value, memory_order_relaxed);
}
static bool atomicCompareExchangeU64(
        VortekAtomicU64* value, uint64_t* expected, uint64_t desired) {
    uint_fast64_t current = (uint_fast64_t)*expected;
    const bool exchanged = atomic_compare_exchange_weak_explicit(
            value, &current, (uint_fast64_t)desired,
            memory_order_relaxed, memory_order_relaxed);
    *expected = (uint64_t)current;
    return exchanged;
}
#endif
#include <limits.h>
#include <stdlib.h>
#include <string.h>

/* Guest tokens are process-wide identities.  Context-local counters would
 * allow a token captured from a destroyed context to alias the first object
 * created by its replacement.  UINT64_MAX is a permanent exhaustion latch. */
static VortekAtomicU64 nextProcessToken = VORTEK_ATOMIC_U64_INIT(1);

static VortekHandleStatus allocateProcessToken(VortekHandleToken* tokenOut) {
    if (!tokenOut) return VORTEK_HANDLE_INVALID_ARGUMENT;
    *tokenOut = 0;
    uint64_t current = atomicLoadU64(&nextProcessToken);
    for (;;) {
        if (current == 0 || current == UINT64_MAX)
            return VORTEK_HANDLE_EXHAUSTED;
        const uint64_t next = current + 1u;
        if (atomicCompareExchangeU64(&nextProcessToken, &current, next)) {
            *tokenOut = (VortekHandleToken)current;
            return VORTEK_HANDLE_OK;
        }
    }
}

typedef struct VortekHandleEntry {
    VortekHandleToken token;
    uint64_t contextGeneration;
    VortekHandleRole role;
    VkObjectType vulkanType;
    VortekHandleOwner owner;
    uint64_t hostValue;
    bool nullDescriptorEnabled;
    uint32_t activeCalls;
    bool retiring;
    bool idleWaitClaimed;
    bool live;
    size_t nextFree;
} VortekHandleEntry;

typedef struct VortekWindowBinding {
    VortekHandleToken instanceOwner;
    uint64_t lifetime;
    uint32_t windowId;
} VortekWindowBinding;

struct VortekHandleRegistry {
    pthread_mutex_t mutex;
    pthread_cond_t leaseCond;
    uint64_t generation;
    size_t capacity;
    size_t indexCapacity;
    size_t freeHead;
    size_t liveCount;
    bool closed;
    VortekHandleEntry* entries;
    /* 0 is empty, SIZE_MAX is a tombstone, otherwise entry index + 1. */
    size_t* tokenIndex;
    /* Compact, bounded authority bindings.  A wire XID is deliberately not
     * itself a lifetime: reuse of the same XID must not revive an old surface
     * within an instance authority. */
    VortekWindowBinding* windowBindings;
    size_t windowBindingCount;
    size_t windowBindingCapacity;
    VortekWindowIdValidator windowValidator;
    void* windowValidatorUserdata;
};

static uint64_t hashToken(VortekHandleToken token) {
    token ^= token >> 30u;
    token *= UINT64_C(0xbf58476d1ce4e5b9);
    token ^= token >> 27u;
    token *= UINT64_C(0x94d049bb133111eb);
    return token ^ (token >> 31u);
}

static size_t indexCapacityFor(size_t capacity) {
    size_t result = 2;
    const size_t required = capacity * 2u;
    while (result < required) result <<= 1u;
    return result;
}

static size_t findIndexSlotLocked(
    VortekHandleRegistry* registry,
    VortekHandleToken token) {
    const size_t mask = registry->indexCapacity - 1u;
    size_t slot = (size_t)hashToken(token) & mask;
    for (size_t probe = 0; probe < registry->indexCapacity; ++probe) {
        const size_t encoded = registry->tokenIndex[slot];
        if (encoded == 0) return SIZE_MAX;
        if (encoded != SIZE_MAX) {
            const size_t entryIndex = encoded - 1u;
            const VortekHandleEntry* entry = &registry->entries[entryIndex];
            if (entry->live && entry->token == token) return slot;
        }
        slot = (slot + 1u) & mask;
    }
    return SIZE_MAX;
}

static bool insertIndexLocked(
    VortekHandleRegistry* registry,
    VortekHandleToken token,
    size_t entryIndex) {
    const size_t mask = registry->indexCapacity - 1u;
    size_t slot = (size_t)hashToken(token) & mask;
    size_t firstTombstone = SIZE_MAX;
    for (size_t probe = 0; probe < registry->indexCapacity; ++probe) {
        const size_t encoded = registry->tokenIndex[slot];
        if (encoded == SIZE_MAX && firstTombstone == SIZE_MAX) firstTombstone = slot;
        else if (encoded == 0) {
            if (firstTombstone != SIZE_MAX) slot = firstTombstone;
            registry->tokenIndex[slot] = entryIndex + 1u;
            return true;
        }
        slot = (slot + 1u) & mask;
    }
    if (firstTombstone != SIZE_MAX) {
        registry->tokenIndex[firstTombstone] = entryIndex + 1u;
        return true;
    }
    return false;
}

static VortekHandleEntry* findLiveLocked(
    VortekHandleRegistry* registry,
    VortekHandleToken token) {
    const size_t slot = findIndexSlotLocked(registry, token);
    if (slot == SIZE_MAX) return NULL;
    return &registry->entries[registry->tokenIndex[slot] - 1u];
}

static VortekHandleStatus missingStatusLocked(
    VortekHandleRegistry* registry,
    VortekHandleToken token) {
    if (token == 0) return VORTEK_HANDLE_NULL;
    (void)registry;
    const uint64_t next = atomicLoadU64(&nextProcessToken);
    return token < next ? VORTEK_HANDLE_STALE : VORTEK_HANDLE_UNKNOWN;
}

static bool isLiveVulkanTypeLocked(
    VortekHandleRegistry* registry,
    VortekHandleToken token,
    VkObjectType type,
    VortekHandleEntry** entryOut) {
    VortekHandleEntry* entry = findLiveLocked(registry, token);
    if (!entry || entry->role != VORTEK_HANDLE_ROLE_VULKAN ||
        entry->vulkanType != type) return false;
    if (entryOut) *entryOut = entry;
    return true;
}

static VortekHandleStatus validateOwnersLocked(
    VortekHandleRegistry* registry,
    VortekHandleOwner owner) {
    VortekHandleEntry* instance = NULL;
    VortekHandleEntry* device = NULL;
    VortekHandleEntry* parent = NULL;
    if (owner.instance != 0 && !isLiveVulkanTypeLocked(
            registry, owner.instance, VK_OBJECT_TYPE_INSTANCE, &instance)) {
        return VORTEK_HANDLE_STALE_OWNER;
    }
    if (owner.device != 0 && !isLiveVulkanTypeLocked(
            registry, owner.device, VK_OBJECT_TYPE_DEVICE, &device)) {
        return VORTEK_HANDLE_STALE_OWNER;
    }
    if (device && owner.instance != 0 && device->owner.instance != owner.instance) {
        return VORTEK_HANDLE_WRONG_OWNER;
    }
    if (owner.parent != 0) {
        parent = findLiveLocked(registry, owner.parent);
        if (!parent) return VORTEK_HANDLE_STALE_OWNER;
        if ((parent->owner.instance != 0 &&
             parent->owner.instance != owner.instance) ||
            (parent->owner.device != 0 &&
             parent->owner.device != owner.device)) {
            return VORTEK_HANDLE_WRONG_OWNER;
        }
    }
    return VORTEK_HANDLE_OK;
}

static VortekHandleStatus validateExpectationLockedEx(
    VortekHandleRegistry* registry,
    VortekHandleToken token,
    const VortekHandleExpectation* expectation,
    VortekHandleEntry** entryOut,
    bool allowClosed) {
    if (!expectation) return VORTEK_HANDLE_INVALID_ARGUMENT;
    if (registry->closed && !allowClosed) return VORTEK_HANDLE_CLOSED;
    if (expectation->contextGeneration != registry->generation) {
        return VORTEK_HANDLE_WRONG_GENERATION;
    }
    if (token == 0) return expectation->allowNull ? VORTEK_HANDLE_OK : VORTEK_HANDLE_NULL;

    VortekHandleEntry* entry = findLiveLocked(registry, token);
    if (!entry) return missingStatusLocked(registry, token);
    if (entry->contextGeneration != expectation->contextGeneration) {
        return VORTEK_HANDLE_WRONG_GENERATION;
    }
    if (entry->role != expectation->role) return VORTEK_HANDLE_WRONG_ROLE;
    if (entry->role == VORTEK_HANDLE_ROLE_VULKAN &&
        entry->vulkanType != expectation->vulkanType) {
        return VORTEK_HANDLE_WRONG_TYPE;
    }
    if (expectation->requireInstanceOwner &&
        entry->owner.instance != expectation->owner.instance) {
        return VORTEK_HANDLE_WRONG_OWNER;
    }
    if (expectation->requireDeviceOwner &&
        entry->owner.device != expectation->owner.device) {
        return VORTEK_HANDLE_WRONG_OWNER;
    }
    if (expectation->requireParentOwner &&
        entry->owner.parent != expectation->owner.parent) {
        return VORTEK_HANDLE_WRONG_OWNER;
    }
    VortekHandleStatus ownerStatus = validateOwnersLocked(registry, entry->owner);
    if (ownerStatus != VORTEK_HANDLE_OK) return ownerStatus;
    if (entryOut) *entryOut = entry;
    return VORTEK_HANDLE_OK;
}

static VortekHandleStatus validateExpectationLocked(
    VortekHandleRegistry* registry,
    VortekHandleToken token,
    const VortekHandleExpectation* expectation,
    VortekHandleEntry** entryOut) {
    return validateExpectationLockedEx(
        registry, token, expectation, entryOut, false);
}

static VortekHandleStatus registerValueLocked(
    VortekHandleRegistry* registry,
    VortekHandleRole role,
    VkObjectType type,
    uint64_t hostValue,
    VortekHandleOwner owner,
    VortekHandleToken* tokenOut) {
    if (registry->closed) return VORTEK_HANDLE_CLOSED;
    if (!tokenOut || hostValue == 0) return VORTEK_HANDLE_INVALID_ARGUMENT;
    *tokenOut = 0;

    VortekHandleStatus ownerStatus = validateOwnersLocked(registry, owner);
    if (ownerStatus != VORTEK_HANDLE_OK) return ownerStatus;

    /* Stable Vulkan identity matters for repeated physical-device, queue and
     * swapchain-image queries, and prevents those queries exhausting tokens.
     * Do not scan ordinary creation types: that would turn large allocation
     * workloads into O(n^2), and distinct live objects must never alias merely
     * because a driver reused bits outside the protocol's lifetime rules. */
    const bool reuseIdentity = role == VORTEK_HANDLE_ROLE_VULKAN &&
            (type == VK_OBJECT_TYPE_PHYSICAL_DEVICE ||
             type == VK_OBJECT_TYPE_QUEUE ||
             (type == VK_OBJECT_TYPE_IMAGE && owner.parent != 0));
    if (reuseIdentity) {
        for (size_t index = 0; index < registry->capacity; ++index) {
            const VortekHandleEntry* entry = &registry->entries[index];
            if (entry->live && entry->role == role && entry->vulkanType == type &&
                    entry->hostValue == hostValue &&
                    entry->owner.instance == owner.instance &&
                    entry->owner.device == owner.device &&
                    entry->owner.parent == owner.parent) {
                *tokenOut = entry->token;
                return VORTEK_HANDLE_OK;
            }
        }
    }

    if (registry->liveCount >= registry->capacity) return VORTEK_HANDLE_CAPACITY;
    if (registry->freeHead == SIZE_MAX) return VORTEK_HANDLE_CAPACITY;
    const size_t entryIndex = registry->freeHead;
    VortekHandleEntry* slot = &registry->entries[entryIndex];
    registry->freeHead = slot->nextFree;

    VortekHandleToken token = 0;
    VortekHandleStatus tokenStatus = allocateProcessToken(&token);
    if (tokenStatus != VORTEK_HANDLE_OK) {
        slot->nextFree = registry->freeHead;
        registry->freeHead = entryIndex;
        return tokenStatus;
    }
    if (!insertIndexLocked(registry, token, entryIndex)) {
        slot->nextFree = registry->freeHead;
        registry->freeHead = entryIndex;
        return VORTEK_HANDLE_CAPACITY;
    }
    *slot = (VortekHandleEntry) {
        .token = token,
        .contextGeneration = registry->generation,
        .role = role,
        .vulkanType = type,
        .owner = owner,
        .hostValue = hostValue,
        .live = true,
    };
    ++registry->liveCount;
    *tokenOut = token;
    return VORTEK_HANDLE_OK;
}

static void unregisterEntryLocked(
    VortekHandleRegistry* registry,
    VortekHandleEntry* entry) {
    const size_t indexSlot = findIndexSlotLocked(registry, entry->token);
    if (indexSlot != SIZE_MAX) registry->tokenIndex[indexSlot] = SIZE_MAX;
    const size_t entryIndex = (size_t)(entry - registry->entries);
    entry->live = false;
    entry->hostValue = 0;
    entry->nullDescriptorEnabled = false;
    entry->activeCalls = 0;
    entry->retiring = false;
    entry->nextFree = registry->freeHead;
    registry->freeHead = entryIndex;
    --registry->liveCount;
}

static bool entryDeviceHasActiveCallsLocked(
    VortekHandleRegistry* registry,
    const VortekHandleEntry* entry) {
    if (!registry || !entry) return false;
    VortekHandleToken device = entry->owner.device;
    if (entry->role == VORTEK_HANDLE_ROLE_VULKAN &&
            entry->vulkanType == VK_OBJECT_TYPE_DEVICE) {
        device = entry->token;
    }
    if (device == 0) return false;
    VortekHandleEntry* deviceEntry = findLiveLocked(registry, device);
    return deviceEntry && deviceEntry->role == VORTEK_HANDLE_ROLE_VULKAN &&
            deviceEntry->vulkanType == VK_OBJECT_TYPE_DEVICE &&
            deviceEntry->activeCalls != 0;
}

static bool entryHasLiveDependentsLocked(
        VortekHandleRegistry* registry, const VortekHandleEntry* entry) {
    if (!registry || !entry || entry->role != VORTEK_HANDLE_ROLE_VULKAN)
        return false;
    const bool isDevice = entry->vulkanType == VK_OBJECT_TYPE_DEVICE;
    const bool isInstance = entry->vulkanType == VK_OBJECT_TYPE_INSTANCE;
    if (!isDevice && !isInstance) return false;
    for (size_t index = 0; index < registry->capacity; ++index) {
        const VortekHandleEntry* candidate = &registry->entries[index];
        if (!candidate->live || candidate == entry) continue;
        if ((isDevice && candidate->owner.device == entry->token) ||
                (isInstance && candidate->owner.instance == entry->token)) {
            return true;
        }
    }
    return false;
}

typedef bool (*VortekEntryPredicate)(
    const VortekHandleEntry* entry, VortekHandleToken token);

static VortekHandleStatus unregisterMatchingLocked(
    VortekHandleRegistry* registry,
    VortekHandleToken token,
    VortekEntryPredicate predicate) {
    if (registry->closed) return VORTEK_HANDLE_CLOSED;
    if (token == 0 || !predicate) return VORTEK_HANDLE_INVALID_ARGUMENT;
    /* Preflight the whole cascade before changing liveness.  Async workers
     * retain decoded child pointers under a device lease, so no matching
     * device-owned entry may disappear until the final lease is released. */
    for (size_t index = 0; index < registry->capacity; ++index) {
        VortekHandleEntry* entry = &registry->entries[index];
        if (entry->live && predicate(entry, token) &&
                entryDeviceHasActiveCallsLocked(registry, entry)) {
            return VORTEK_HANDLE_RETIRING;
        }
    }
    for (size_t index = 0; index < registry->capacity; ++index) {
        VortekHandleEntry* entry = &registry->entries[index];
        if (entry->live && predicate(entry, token)) unregisterEntryLocked(registry, entry);
    }
    return VORTEK_HANDLE_OK;
}

static bool hasParent(
    const VortekHandleEntry* entry, VortekHandleToken token) {
    return entry->owner.parent == token;
}

static void purgeWindowBindingsLocked(
    VortekHandleRegistry* registry,
    VortekHandleToken instanceOwner) {
    if (!registry || instanceOwner == 0) return;
    size_t index = 0;
    while (index < registry->windowBindingCount) {
        if (registry->windowBindings[index].instanceOwner != instanceOwner) {
            ++index;
            continue;
        }
        const size_t last = --registry->windowBindingCount;
        if (index != last) {
            registry->windowBindings[index] = registry->windowBindings[last];
        }
        memset(&registry->windowBindings[last], 0,
               sizeof(registry->windowBindings[last]));
    }
}

static VortekHandleStatus bindWindowLifetimeLocked(
    VortekHandleRegistry* registry,
    VortekHandleToken instanceOwner,
    uint32_t windowId,
    uint64_t lifetime) {
    if (!registry || instanceOwner == 0 || windowId == 0 || lifetime == 0)
        return VORTEK_HANDLE_INVALID_ARGUMENT;
    for (size_t index = 0; index < registry->windowBindingCount; ++index) {
        const VortekWindowBinding* binding = &registry->windowBindings[index];
        if (binding->instanceOwner == instanceOwner &&
                binding->windowId == windowId) {
            return binding->lifetime == lifetime
                    ? VORTEK_HANDLE_OK : VORTEK_HANDLE_WINDOW_REJECTED;
        }
    }
    if (registry->windowBindingCount >= registry->windowBindingCapacity)
        return VORTEK_HANDLE_CAPACITY;
    registry->windowBindings[registry->windowBindingCount++] =
            (VortekWindowBinding) {
                .instanceOwner = instanceOwner,
                .lifetime = lifetime,
                .windowId = windowId,
            };
    return VORTEK_HANDLE_OK;
}

static int compareTokens(const void* left, const void* right) {
    const VortekHandleToken a = *(const VortekHandleToken*)left;
    const VortekHandleToken b = *(const VortekHandleToken*)right;
    return a < b ? -1 : a > b ? 1 : 0;
}

static bool hasActiveDeviceCallsLocked(VortekHandleRegistry* registry) {
    for (size_t index = 0; index < registry->capacity; ++index) {
        const VortekHandleEntry* entry = &registry->entries[index];
        if (entry->live && entry->role == VORTEK_HANDLE_ROLE_VULKAN &&
                entry->vulkanType == VK_OBJECT_TYPE_DEVICE &&
                entry->activeCalls != 0) return true;
    }
    return false;
}

VortekHandleRegistry* VortekHandleRegistry_create(
    uint64_t contextGeneration,
    size_t capacity) {
    if (contextGeneration == 0 || capacity == 0 ||
        capacity > VORTEK_HANDLE_REGISTRY_MAX_CAPACITY) return NULL;
    VortekHandleRegistry* registry = calloc(1, sizeof(*registry));
    if (!registry) return NULL;
    registry->entries = calloc(capacity, sizeof(*registry->entries));
    if (!registry->entries) {
        free(registry);
        return NULL;
    }
    registry->indexCapacity = indexCapacityFor(capacity);
    registry->tokenIndex = calloc(registry->indexCapacity, sizeof(*registry->tokenIndex));
    if (!registry->tokenIndex) {
        free(registry->entries);
        free(registry);
        return NULL;
    }
    registry->windowBindings = calloc(capacity, sizeof(*registry->windowBindings));
    if (!registry->windowBindings) {
        free(registry->tokenIndex);
        free(registry->entries);
        free(registry);
        return NULL;
    }
    if (pthread_mutex_init(&registry->mutex, NULL) != 0) {
        free(registry->windowBindings);
        free(registry->tokenIndex);
        free(registry->entries);
        free(registry);
        return NULL;
    }
    if (pthread_cond_init(&registry->leaseCond, NULL) != 0) {
        pthread_mutex_destroy(&registry->mutex);
        free(registry->windowBindings);
        free(registry->tokenIndex);
        free(registry->entries);
        free(registry);
        return NULL;
    }
    registry->generation = contextGeneration;
    registry->capacity = capacity;
    registry->windowBindingCapacity = capacity;
    registry->freeHead = 0;
    for (size_t index = 0; index < capacity; ++index) {
        registry->entries[index].nextFree =
            index + 1u < capacity ? index + 1u : SIZE_MAX;
    }
    return registry;
}

void VortekHandleRegistry_beginClose(VortekHandleRegistry* registry) {
    if (!registry) return;
    pthread_mutex_lock(&registry->mutex);
    registry->closed = true;
    for (size_t index = 0; index < registry->capacity; ++index) {
        VortekHandleEntry* entry = &registry->entries[index];
        if (entry->live && entry->role == VORTEK_HANDLE_ROLE_VULKAN &&
                entry->vulkanType == VK_OBJECT_TYPE_DEVICE) entry->retiring = true;
    }
    pthread_mutex_unlock(&registry->mutex);
}

void VortekHandleRegistry_close(VortekHandleRegistry* registry) {
    if (!registry) return;
    VortekHandleRegistry_beginClose(registry);
    pthread_mutex_lock(&registry->mutex);
    while (hasActiveDeviceCallsLocked(registry))
        (void)pthread_cond_wait(&registry->leaseCond, &registry->mutex);
    registry->windowValidator = NULL;
    registry->windowValidatorUserdata = NULL;
    memset(registry->windowBindings, 0,
           registry->windowBindingCapacity * sizeof(*registry->windowBindings));
    registry->windowBindingCount = 0;
    pthread_mutex_unlock(&registry->mutex);
}

bool VortekHandleRegistry_destroy(VortekHandleRegistry* registry) {
    if (!registry) return true;
    VortekHandleRegistry_close(registry);
    pthread_mutex_lock(&registry->mutex);
    const bool drained = registry->liveCount == 0;
    pthread_mutex_unlock(&registry->mutex);
    if (!drained) return false;
    pthread_cond_destroy(&registry->leaseCond);
    pthread_mutex_destroy(&registry->mutex);
    free(registry->tokenIndex);
    free(registry->entries);
    free(registry->windowBindings);
    memset(registry, 0, sizeof(*registry));
    free(registry);
    return true;
}

uint64_t VortekHandleRegistry_generation(const VortekHandleRegistry* registry) {
    return registry ? registry->generation : 0;
}

size_t VortekHandleRegistry_liveCount(VortekHandleRegistry* registry) {
    if (!registry) return 0;
    pthread_mutex_lock(&registry->mutex);
    const size_t result = registry->liveCount;
    pthread_mutex_unlock(&registry->mutex);
    return result;
}

static bool drainScopeMatches(
    const VortekHandleEntry* entry,
    VortekHandleDrainScope scope,
    VortekHandleToken token) {
    if (!entry || !entry->live) return false;
    switch (scope) {
        case VORTEK_HANDLE_DRAIN_ALL:
            return true;
        case VORTEK_HANDLE_DRAIN_DEVICE:
            return entry->owner.device == token ||
                (entry->token == token &&
                 entry->role == VORTEK_HANDLE_ROLE_VULKAN &&
                 entry->vulkanType == VK_OBJECT_TYPE_DEVICE);
        case VORTEK_HANDLE_DRAIN_INSTANCE:
            return entry->owner.instance == token ||
                (entry->token == token &&
                 entry->role == VORTEK_HANDLE_ROLE_VULKAN &&
                 entry->vulkanType == VK_OBJECT_TYPE_INSTANCE);
    }
    return false;
}

static unsigned drainPriority(const VortekHandleEntry* entry) {
    if (!entry) return UINT_MAX;
    if (entry->role == VORTEK_HANDLE_ROLE_VULKAN) {
        if (entry->vulkanType == VK_OBJECT_TYPE_COMMAND_BUFFER ||
                entry->vulkanType == VK_OBJECT_TYPE_DESCRIPTOR_SET ||
                (entry->vulkanType == VK_OBJECT_TYPE_IMAGE &&
                 entry->owner.parent != 0)) return 0u;
        switch (entry->vulkanType) {
            case VK_OBJECT_TYPE_PIPELINE: return 10u;
            case VK_OBJECT_TYPE_FRAMEBUFFER: return 11u;
            case VK_OBJECT_TYPE_DESCRIPTOR_POOL: return 12u;
            case VK_OBJECT_TYPE_COMMAND_POOL: return 13u;
            case VK_OBJECT_TYPE_IMAGE_VIEW:
            case VK_OBJECT_TYPE_BUFFER_VIEW: return 14u;
            case VK_OBJECT_TYPE_SHADER_MODULE: return 15u;
            case VK_OBJECT_TYPE_PIPELINE_LAYOUT: return 16u;
            case VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT: return 17u;
            case VK_OBJECT_TYPE_RENDER_PASS: return 18u;
            case VK_OBJECT_TYPE_SAMPLER: return 19u;
            /* A sampler may retain the conversion it was created with. */
            case VK_OBJECT_TYPE_SAMPLER_YCBCR_CONVERSION: return 20u;
            case VK_OBJECT_TYPE_QUERY_POOL:
            case VK_OBJECT_TYPE_EVENT:
            case VK_OBJECT_TYPE_FENCE:
            case VK_OBJECT_TYPE_SEMAPHORE: return 20u;
            case VK_OBJECT_TYPE_BUFFER:
            case VK_OBJECT_TYPE_IMAGE: return 30u;
            case VK_OBJECT_TYPE_QUEUE:
            case VK_OBJECT_TYPE_PHYSICAL_DEVICE: return 50u;
            case VK_OBJECT_TYPE_DEVICE: return 60u;
            case VK_OBJECT_TYPE_INSTANCE: return 70u;
            default: return 40u;
        }
    }
    /* The wrapper owns its images and memory.  Image views/framebuffers that
     * refer to those images must be destroyed before the wrapper. */
    if (entry->role == VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN) return 35u;
    if (entry->role == VORTEK_HANDLE_ROLE_SHADER_MODULE) return 15u;
    if (entry->role == VORTEK_HANDLE_ROLE_RESOURCE_MEMORY) return 40u;
    return 45u;
}

VortekHandleStatus VortekHandleRegistry_drainNext(
    VortekHandleRegistry* registry,
    VortekHandleDrainScope scope,
    VortekHandleToken scopeToken,
    VortekHandleDrainValue* valueOut) {
    if (!registry || !valueOut ||
            (scope != VORTEK_HANDLE_DRAIN_ALL && scopeToken == 0) ||
            scope < VORTEK_HANDLE_DRAIN_ALL ||
            scope > VORTEK_HANDLE_DRAIN_INSTANCE) {
        return VORTEK_HANDLE_INVALID_ARGUMENT;
    }
    memset(valueOut, 0, sizeof(*valueOut));
    pthread_mutex_lock(&registry->mutex);
    VortekHandleEntry* selected = NULL;
    unsigned selectedPriority = UINT_MAX;
    for (size_t index = 0; index < registry->capacity; ++index) {
        VortekHandleEntry* entry = &registry->entries[index];
        if (!drainScopeMatches(entry, scope, scopeToken)) continue;
        if (entryDeviceHasActiveCallsLocked(registry, entry)) {
            pthread_mutex_unlock(&registry->mutex);
            return VORTEK_HANDLE_RETIRING;
        }
        const unsigned priority = drainPriority(entry);
        if (!selected || priority < selectedPriority ||
                (priority == selectedPriority && entry->token < selected->token)) {
            selected = entry;
            selectedPriority = priority;
        }
    }
    if (!selected) {
        if (scope == VORTEK_HANDLE_DRAIN_INSTANCE)
            purgeWindowBindingsLocked(registry, scopeToken);
        pthread_mutex_unlock(&registry->mutex);
        return VORTEK_HANDLE_OK;
    }

    VortekHandleEntry* deviceEntry = NULL;
    if (selected->role == VORTEK_HANDLE_ROLE_VULKAN &&
            selected->vulkanType == VK_OBJECT_TYPE_DEVICE) {
        deviceEntry = selected;
    }
    else if (selected->owner.device != 0) {
        if (!isLiveVulkanTypeLocked(registry, selected->owner.device,
                VK_OBJECT_TYPE_DEVICE, &deviceEntry)) {
            pthread_mutex_unlock(&registry->mutex);
            return VORTEK_HANDLE_STALE_OWNER;
        }
    }
    valueOut->value = (VortekHandleValue) {
        .token = selected->token,
        .contextGeneration = selected->contextGeneration,
        .role = selected->role,
        .vulkanType = selected->vulkanType,
        .owner = selected->owner,
        .hostValue = selected->hostValue,
        .nullDescriptorEnabled = selected->nullDescriptorEnabled,
    };
    if (deviceEntry) {
        valueOut->hostDeviceValue = deviceEntry->hostValue;
        valueOut->waitDevice = !deviceEntry->idleWaitClaimed;
        deviceEntry->idleWaitClaimed = true;
    }
    if (selected->role == VORTEK_HANDLE_ROLE_VULKAN &&
            selected->vulkanType == VK_OBJECT_TYPE_INSTANCE) {
        purgeWindowBindingsLocked(registry, selected->token);
    }
    unregisterEntryLocked(registry, selected);
    pthread_mutex_unlock(&registry->mutex);
    return VORTEK_HANDLE_OK;
}

VortekHandleStatus VortekHandleRegistry_registerVulkan(
    VortekHandleRegistry* registry,
    VkObjectType type,
    uint64_t hostHandleBits,
    VortekHandleOwner owner,
    VortekHandleToken* tokenOut) {
    if (!registry || type == VK_OBJECT_TYPE_UNKNOWN) return VORTEK_HANDLE_INVALID_ARGUMENT;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = registerValueLocked(
        registry, VORTEK_HANDLE_ROLE_VULKAN, type, hostHandleBits, owner, tokenOut);
    pthread_mutex_unlock(&registry->mutex);
    return status;
}

VortekHandleStatus VortekHandleRegistry_registerVulkanBatch(
    VortekHandleRegistry* registry,
    VkObjectType type,
    const uint64_t* hostHandleBits,
    size_t count,
    VortekHandleOwner owner,
    VortekHandleToken* tokensOut) {
    if (!registry || type == VK_OBJECT_TYPE_UNKNOWN ||
            count > SIZE_MAX / sizeof(*tokensOut) ||
            (count != 0 && (!hostHandleBits || !tokensOut))) {
        return VORTEK_HANDLE_INVALID_ARGUMENT;
    }
    if (count == 0) return VORTEK_HANDLE_OK;
    memset(tokensOut, 0, count * sizeof(*tokensOut));

    VortekHandleToken* newlyCreated = calloc(count, sizeof(*newlyCreated));
    if (!newlyCreated) return VORTEK_HANDLE_CAPACITY;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = VORTEK_HANDLE_OK;
    for (size_t index = 0; index < count; ++index) {
        const uint64_t before = atomicLoadU64(&nextProcessToken);
        status = registerValueLocked(registry, VORTEK_HANDLE_ROLE_VULKAN,
                type, hostHandleBits[index], owner, &tokensOut[index]);
        if (status != VORTEK_HANDLE_OK) break;
        if (tokensOut[index] >= before) newlyCreated[index] = tokensOut[index];
    }
    if (status != VORTEK_HANDLE_OK) {
        /* Stable identities may predate this batch.  Only retire entries that
         * this call itself inserted; process tokens are intentionally never
         * rewound, including failed publication batches. */
        for (size_t index = 0; index < count; ++index) {
            if (newlyCreated[index] == 0) continue;
            VortekHandleEntry* entry = findLiveLocked(
                    registry, newlyCreated[index]);
            if (entry) unregisterEntryLocked(registry, entry);
        }
        memset(tokensOut, 0, count * sizeof(*tokensOut));
    }
    pthread_mutex_unlock(&registry->mutex);
    free(newlyCreated);
    return status;
}

VortekHandleStatus VortekHandleRegistry_setDeviceNullDescriptor(
    VortekHandleRegistry* registry,
    VortekHandleToken device,
    bool enabled) {
    if (!registry || device == 0) return VORTEK_HANDLE_INVALID_ARGUMENT;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = VORTEK_HANDLE_OK;
    if (registry->closed) status = VORTEK_HANDLE_CLOSED;
    else {
        VortekHandleEntry* entry = findLiveLocked(registry, device);
        if (!entry) status = missingStatusLocked(registry, device);
        else if (entry->role != VORTEK_HANDLE_ROLE_VULKAN)
            status = VORTEK_HANDLE_WRONG_ROLE;
        else if (entry->vulkanType != VK_OBJECT_TYPE_DEVICE)
            status = VORTEK_HANDLE_WRONG_TYPE;
        else entry->nullDescriptorEnabled = enabled;
    }
    pthread_mutex_unlock(&registry->mutex);
    return status;
}

VortekHandleStatus VortekHandleRegistry_acquireDeviceLease(
    VortekHandleRegistry* registry,
    VortekHandleToken device,
    uint64_t contextGeneration,
    VortekDeviceLease* leaseOut) {
    if (!registry || !leaseOut || device == 0 || contextGeneration == 0)
        return VORTEK_HANDLE_INVALID_ARGUMENT;
    memset(leaseOut, 0, sizeof(*leaseOut));
    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = VORTEK_HANDLE_OK;
    VortekHandleEntry* entry = NULL;
    if (registry->closed) status = VORTEK_HANDLE_CLOSED;
    else if (contextGeneration != registry->generation)
        status = VORTEK_HANDLE_WRONG_GENERATION;
    else if (!isLiveVulkanTypeLocked(
            registry, device, VK_OBJECT_TYPE_DEVICE, &entry)) {
        VortekHandleEntry* any = findLiveLocked(registry, device);
        status = !any ? missingStatusLocked(registry, device) :
                any->role != VORTEK_HANDLE_ROLE_VULKAN
                ? VORTEK_HANDLE_WRONG_ROLE : VORTEK_HANDLE_WRONG_TYPE;
    }
    else if (entry->retiring) status = VORTEK_HANDLE_RETIRING;
    else if (entry->activeCalls == UINT32_MAX) status = VORTEK_HANDLE_EXHAUSTED;
    else {
        ++entry->activeCalls;
        *leaseOut = (VortekDeviceLease) {
            .registry = registry,
            .device = device,
            .contextGeneration = contextGeneration,
            .hostDeviceBits = entry->hostValue,
            .active = true,
        };
    }
    pthread_mutex_unlock(&registry->mutex);
    return status;
}

VortekHandleStatus VortekHandleRegistry_releaseDeviceLease(
    VortekDeviceLease* lease) {
    if (!lease || !lease->active || !lease->registry || lease->device == 0)
        return VORTEK_HANDLE_INVALID_ARGUMENT;
    VortekHandleRegistry* registry = lease->registry;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleEntry* entry = findLiveLocked(registry, lease->device);
    VortekHandleStatus status = VORTEK_HANDLE_OK;
    if (!entry || entry->contextGeneration != lease->contextGeneration ||
            entry->role != VORTEK_HANDLE_ROLE_VULKAN ||
            entry->vulkanType != VK_OBJECT_TYPE_DEVICE ||
            entry->activeCalls == 0) {
        status = VORTEK_HANDLE_STALE;
    }
    else {
        --entry->activeCalls;
        if (entry->activeCalls == 0) pthread_cond_broadcast(&registry->leaseCond);
    }
    pthread_mutex_unlock(&registry->mutex);
    if (status == VORTEK_HANDLE_OK) memset(lease, 0, sizeof(*lease));
    return status;
}

bool VortekHandleRegistry_deviceLeaseShouldCancel(
    const VortekDeviceLease* lease) {
    if (!lease || !lease->active || !lease->registry || lease->device == 0)
        return true;
    VortekHandleRegistry* registry = lease->registry;
    pthread_mutex_lock(&registry->mutex);
    const VortekHandleEntry* entry = findLiveLocked(registry, lease->device);
    const bool cancel = registry->closed || !entry ||
            entry->contextGeneration != lease->contextGeneration ||
            entry->role != VORTEK_HANDLE_ROLE_VULKAN ||
            entry->vulkanType != VK_OBJECT_TYPE_DEVICE || entry->retiring;
    pthread_mutex_unlock(&registry->mutex);
    return cancel;
}

VortekHandleStatus VortekHandleRegistry_beginDeviceRetirement(
    VortekHandleRegistry* registry,
    VortekHandleToken device,
    uint64_t contextGeneration) {
    if (!registry || device == 0 || contextGeneration == 0)
        return VORTEK_HANDLE_INVALID_ARGUMENT;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = VORTEK_HANDLE_OK;
    VortekHandleEntry* entry = NULL;
    if (registry->closed) status = VORTEK_HANDLE_CLOSED;
    else if (contextGeneration != registry->generation)
        status = VORTEK_HANDLE_WRONG_GENERATION;
    else if (!isLiveVulkanTypeLocked(
            registry, device, VK_OBJECT_TYPE_DEVICE, &entry)) {
        VortekHandleEntry* any = findLiveLocked(registry, device);
        status = !any ? missingStatusLocked(registry, device) :
                any->role != VORTEK_HANDLE_ROLE_VULKAN
                ? VORTEK_HANDLE_WRONG_ROLE : VORTEK_HANDLE_WRONG_TYPE;
    }
    else {
        entry->retiring = true;
        while (entry->activeCalls != 0)
            (void)pthread_cond_wait(&registry->leaseCond, &registry->mutex);
    }
    pthread_mutex_unlock(&registry->mutex);
    return status;
}

VortekHandleStatus VortekHandleRegistry_beginInstanceRetirement(
    VortekHandleRegistry* registry,
    VortekHandleToken instance,
    uint64_t contextGeneration) {
    if (!registry || instance == 0 || contextGeneration == 0)
        return VORTEK_HANDLE_INVALID_ARGUMENT;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = VORTEK_HANDLE_OK;
    if (registry->closed) status = VORTEK_HANDLE_CLOSED;
    else if (contextGeneration != registry->generation)
        status = VORTEK_HANDLE_WRONG_GENERATION;
    else if (!isLiveVulkanTypeLocked(
            registry, instance, VK_OBJECT_TYPE_INSTANCE, NULL)) {
        VortekHandleEntry* any = findLiveLocked(registry, instance);
        status = !any ? missingStatusLocked(registry, instance) :
                any->role != VORTEK_HANDLE_ROLE_VULKAN
                ? VORTEK_HANDLE_WRONG_ROLE : VORTEK_HANDLE_WRONG_TYPE;
    }
    else {
        for (size_t index = 0; index < registry->capacity; ++index) {
            VortekHandleEntry* entry = &registry->entries[index];
            if (entry->live && entry->role == VORTEK_HANDLE_ROLE_VULKAN &&
                    entry->vulkanType == VK_OBJECT_TYPE_DEVICE &&
                    entry->owner.instance == instance) entry->retiring = true;
        }
        bool active;
        do {
            active = false;
            for (size_t index = 0; index < registry->capacity; ++index) {
                const VortekHandleEntry* entry = &registry->entries[index];
                if (entry->live && entry->role == VORTEK_HANDLE_ROLE_VULKAN &&
                        entry->vulkanType == VK_OBJECT_TYPE_DEVICE &&
                        entry->owner.instance == instance &&
                        entry->activeCalls != 0) {
                    active = true;
                    break;
                }
            }
            if (active) (void)pthread_cond_wait(
                    &registry->leaseCond, &registry->mutex);
        } while (active);
    }
    pthread_mutex_unlock(&registry->mutex);
    return status;
}

VortekHandleStatus VortekHandleRegistry_registerWrapper(
    VortekHandleRegistry* registry,
    VortekHandleRole role,
    const void* wrapper,
    VortekHandleOwner owner,
    VortekHandleToken* tokenOut) {
    if (!registry || !wrapper || role == VORTEK_HANDLE_ROLE_VULKAN ||
        role == VORTEK_HANDLE_ROLE_WINDOW_ID) return VORTEK_HANDLE_INVALID_ARGUMENT;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = registerValueLocked(
        registry, role, VK_OBJECT_TYPE_UNKNOWN, (uint64_t)(uintptr_t)wrapper, owner, tokenOut);
    pthread_mutex_unlock(&registry->mutex);
    return status;
}

VortekHandleStatus VortekHandleRegistry_lookup(
    VortekHandleRegistry* registry,
    VortekHandleToken token,
    const VortekHandleExpectation* expectation,
    VortekHandleValue* valueOut) {
    if (!registry || !expectation || !valueOut) return VORTEK_HANDLE_INVALID_ARGUMENT;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleEntry* entry = NULL;
    VortekHandleStatus status = validateExpectationLocked(
        registry, token, expectation, &entry);
    if (status == VORTEK_HANDLE_OK) {
        if (token == 0) {
            memset(valueOut, 0, sizeof(*valueOut));
            valueOut->contextGeneration = registry->generation;
            valueOut->role = expectation->role;
            valueOut->vulkanType = expectation->vulkanType;
        } else {
            *valueOut = (VortekHandleValue) {
                .token = entry->token,
                .contextGeneration = registry->generation,
                .role = entry->role,
                .vulkanType = entry->vulkanType,
                .owner = entry->owner,
                .hostValue = entry->hostValue,
                .nullDescriptorEnabled = entry->nullDescriptorEnabled,
            };
        }
    }
    pthread_mutex_unlock(&registry->mutex);
    return status;
}

VortekHandleStatus VortekHandleRegistry_unregister(
    VortekHandleRegistry* registry,
    VortekHandleToken token,
    const VortekHandleExpectation* expectation,
    VortekHandleValue* retiredValueOut) {
    if (!registry || !expectation || token == 0) return VORTEK_HANDLE_INVALID_ARGUMENT;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleEntry* entry = NULL;
    VortekHandleStatus status = validateExpectationLocked(
        registry, token, expectation, &entry);
    if (status == VORTEK_HANDLE_OK) {
        /* A root authority may not be tombstoned while it is still needed to
         * reclaim live children.  Scoped drain is the teardown path that
         * removes children in dependency order and retires the root last. */
        if (entryDeviceHasActiveCallsLocked(registry, entry) ||
                entryHasLiveDependentsLocked(registry, entry)) {
            pthread_mutex_unlock(&registry->mutex);
            return VORTEK_HANDLE_RETIRING;
        }
        if (retiredValueOut) {
            *retiredValueOut = (VortekHandleValue) {
                .token = entry->token,
                .contextGeneration = registry->generation,
                .role = entry->role,
                .vulkanType = entry->vulkanType,
                .owner = entry->owner,
                .hostValue = entry->hostValue,
                .nullDescriptorEnabled = entry->nullDescriptorEnabled,
            };
        }
        unregisterEntryLocked(registry, entry);
    }
    pthread_mutex_unlock(&registry->mutex);
    return status;
}

VortekHandleStatus VortekHandleRegistry_unregisterBatch(
    VortekHandleRegistry* registry,
    const VortekHandleToken* tokens,
    size_t count,
    const VortekHandleExpectation* expectation) {
    if (!registry || !expectation || (count != 0 && !tokens) ||
            count > SIZE_MAX / sizeof(*tokens)) {
        return VORTEK_HANDLE_INVALID_ARGUMENT;
    }
    if (count == 0) return VORTEK_HANDLE_OK;

    VortekHandleToken* sorted = malloc(count * sizeof(*sorted));
    if (!sorted) return VORTEK_HANDLE_CAPACITY;
    memcpy(sorted, tokens, count * sizeof(*sorted));
    qsort(sorted, count, sizeof(*sorted), compareTokens);
    for (size_t index = 0; index < count; ++index) {
        if (sorted[index] == 0 ||
                (index != 0 && sorted[index] == sorted[index - 1])) {
            free(sorted);
            return VORTEK_HANDLE_INVALID_ARGUMENT;
        }
    }

    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = VORTEK_HANDLE_OK;
    for (size_t index = 0; index < count; ++index) {
        status = validateExpectationLocked(
                registry, sorted[index], expectation, NULL);
        if (status != VORTEK_HANDLE_OK) break;
    }
    if (status == VORTEK_HANDLE_OK) {
        for (size_t index = 0; index < count; ++index) {
            VortekHandleEntry* entry = findLiveLocked(registry, sorted[index]);
            if (entry && entryDeviceHasActiveCallsLocked(registry, entry)) {
                status = VORTEK_HANDLE_RETIRING;
                break;
            }
        }
    }
    if (status == VORTEK_HANDLE_OK) {
        for (size_t index = 0; index < count; ++index) {
            VortekHandleEntry* entry = findLiveLocked(registry, sorted[index]);
            /* Every entry was proven live above under this same lock. */
            if (entry) unregisterEntryLocked(registry, entry);
        }
    }
    pthread_mutex_unlock(&registry->mutex);
    free(sorted);
    return status;
}

VortekHandleStatus VortekHandleRegistry_rollbackBatchWithLease(
    VortekHandleRegistry* registry,
    const VortekHandleToken* tokens,
    size_t count,
    const VortekHandleExpectation* expectation,
    const VortekDeviceLease* lease) {
    if (!registry || !expectation || !lease || !lease->active ||
            lease->registry != registry || lease->device == 0 ||
            lease->contextGeneration == 0 ||
            expectation->contextGeneration != lease->contextGeneration ||
            expectation->owner.device != lease->device ||
            !expectation->requireDeviceOwner ||
            (count != 0 && !tokens) || count > SIZE_MAX / sizeof(*tokens)) {
        return VORTEK_HANDLE_INVALID_ARGUMENT;
    }
    if (count == 0) return VORTEK_HANDLE_OK;

    VortekHandleToken* sorted = malloc(count * sizeof(*sorted));
    if (!sorted) return VORTEK_HANDLE_CAPACITY;
    memcpy(sorted, tokens, count * sizeof(*sorted));
    qsort(sorted, count, sizeof(*sorted), compareTokens);
    for (size_t index = 0; index < count; ++index) {
        if (sorted[index] == 0 ||
                (index != 0 && sorted[index] == sorted[index - 1])) {
            free(sorted);
            return VORTEK_HANDLE_INVALID_ARGUMENT;
        }
    }

    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = VORTEK_HANDLE_OK;
    VortekHandleEntry* deviceEntry = findLiveLocked(registry, lease->device);
    if (!deviceEntry || deviceEntry->contextGeneration !=
            lease->contextGeneration ||
            deviceEntry->role != VORTEK_HANDLE_ROLE_VULKAN ||
            deviceEntry->vulkanType != VK_OBJECT_TYPE_DEVICE ||
            deviceEntry->activeCalls == 0) {
        status = VORTEK_HANDLE_STALE;
    }
    for (size_t index = 0; status == VORTEK_HANDLE_OK && index < count; ++index) {
        status = validateExpectationLockedEx(
                registry, sorted[index], expectation, NULL, true);
    }
    if (status == VORTEK_HANDLE_OK) {
        for (size_t index = 0; index < count; ++index) {
            VortekHandleEntry* entry = findLiveLocked(registry, sorted[index]);
            if (entry) unregisterEntryLocked(registry, entry);
        }
    }
    pthread_mutex_unlock(&registry->mutex);
    free(sorted);
    return status;
}

VortekHandleStatus VortekHandleRegistry_validateBatch(
    VortekHandleRegistry* registry,
    const VortekHandleToken* tokens,
    size_t count,
    const VortekHandleExpectation* expectation) {
    if (!registry || !expectation || (count != 0 && !tokens) ||
            count > SIZE_MAX / sizeof(*tokens)) {
        return VORTEK_HANDLE_INVALID_ARGUMENT;
    }
    if (count == 0) return VORTEK_HANDLE_OK;

    VortekHandleToken* sorted = malloc(count * sizeof(*sorted));
    if (!sorted) return VORTEK_HANDLE_CAPACITY;
    memcpy(sorted, tokens, count * sizeof(*sorted));
    qsort(sorted, count, sizeof(*sorted), compareTokens);
    for (size_t index = 0; index < count; ++index) {
        if (sorted[index] == 0 ||
                (index != 0 && sorted[index] == sorted[index - 1])) {
            free(sorted);
            return VORTEK_HANDLE_INVALID_ARGUMENT;
        }
    }

    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = VORTEK_HANDLE_OK;
    for (size_t index = 0; index < count; ++index) {
        status = validateExpectationLocked(
                registry, sorted[index], expectation, NULL);
        if (status != VORTEK_HANDLE_OK) break;
    }
    pthread_mutex_unlock(&registry->mutex);
    free(sorted);
    return status;
}

VortekHandleStatus VortekHandleRegistry_unregisterChildren(
    VortekHandleRegistry* registry,
    VortekHandleToken parent) {
    if (!registry) return VORTEK_HANDLE_INVALID_ARGUMENT;
    if (parent == 0) return VORTEK_HANDLE_OK;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = unregisterMatchingLocked(registry, parent, hasParent);
    pthread_mutex_unlock(&registry->mutex);
    return status;
}

void VortekHandleRegistry_setWindowValidator(
    VortekHandleRegistry* registry,
    VortekWindowIdValidator validator,
    void* userdata) {
    if (!registry) return;
    pthread_mutex_lock(&registry->mutex);
    if (!registry->closed) {
        registry->windowValidator = validator;
        registry->windowValidatorUserdata = userdata;
    }
    pthread_mutex_unlock(&registry->mutex);
}

VortekHandleStatus VortekHandleRegistry_validateWindowId(
    VortekHandleRegistry* registry,
    uint64_t wireValue,
    uint64_t expectedGeneration,
    VortekHandleToken instanceOwner,
    bool allowNull,
    uint32_t* windowIdOut) {
    if (!registry || !windowIdOut) return VORTEK_HANDLE_INVALID_ARGUMENT;
    pthread_mutex_lock(&registry->mutex);
    VortekHandleStatus status = VORTEK_HANDLE_OK;
    if (registry->closed) status = VORTEK_HANDLE_CLOSED;
    else if (expectedGeneration != registry->generation) status = VORTEK_HANDLE_WRONG_GENERATION;
    else if (wireValue == 0) status = allowNull ? VORTEK_HANDLE_OK : VORTEK_HANDLE_NULL;
    else if (wireValue > INT32_MAX || instanceOwner == 0 ||
        !isLiveVulkanTypeLocked(registry, instanceOwner, VK_OBJECT_TYPE_INSTANCE, NULL)) {
        status = VORTEK_HANDLE_WINDOW_REJECTED;
    } else if (!registry->windowValidator) {
        status = VORTEK_HANDLE_WINDOW_REJECTED;
    } else {
        /* Lock order: registry -> JNI -> Java WINDOW_MANAGER -> component
         * authority map.  The callback must never call back into registry. */
        const uint64_t lifetime = registry->windowValidator(
                registry->windowValidatorUserdata, registry->generation,
                instanceOwner, (uint32_t)wireValue);
        status = lifetime == 0 ? VORTEK_HANDLE_WINDOW_REJECTED :
                bindWindowLifetimeLocked(registry, instanceOwner,
                                         (uint32_t)wireValue, lifetime);
    }
    if (status == VORTEK_HANDLE_OK) *windowIdOut = (uint32_t)wireValue;
    pthread_mutex_unlock(&registry->mutex);
    return status;
}

const char* VortekHandleStatus_name(VortekHandleStatus status) {
    switch (status) {
        case VORTEK_HANDLE_OK: return "ok";
        case VORTEK_HANDLE_NULL: return "null";
        case VORTEK_HANDLE_UNKNOWN: return "unknown";
        case VORTEK_HANDLE_STALE: return "stale";
        case VORTEK_HANDLE_WRONG_GENERATION: return "wrong-generation";
        case VORTEK_HANDLE_WRONG_ROLE: return "wrong-role";
        case VORTEK_HANDLE_WRONG_TYPE: return "wrong-type";
        case VORTEK_HANDLE_WRONG_OWNER: return "wrong-owner";
        case VORTEK_HANDLE_STALE_OWNER: return "stale-owner";
        case VORTEK_HANDLE_INVALID_ARGUMENT: return "invalid-argument";
        case VORTEK_HANDLE_CAPACITY: return "capacity";
        case VORTEK_HANDLE_EXHAUSTED: return "exhausted";
        case VORTEK_HANDLE_CLOSED: return "closed";
        case VORTEK_HANDLE_RETIRING: return "retiring";
        case VORTEK_HANDLE_WINDOW_REJECTED: return "window-rejected";
    }
    return "invalid-status";
}
