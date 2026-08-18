#include "handle_registry.h"

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
typedef volatile LONG TestAtomicInt;
#define testAtomicIncrement(value_) ((void)InterlockedIncrement((value_)))
#define testAtomicLoad(value_) ((int)InterlockedCompareExchange((value_), 0, 0))
#else
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
typedef atomic_int TestAtomicInt;
#define testAtomicIncrement(value_) ((void)atomic_fetch_add((value_), 1))
#define testAtomicLoad(value_) atomic_load((value_))
#endif
#include <stdio.h>
#include <stdlib.h>

#define TEST_GENERATION 0x101u
#define THREAD_COUNT 8u
#define TOKENS_PER_THREAD 500u
#define HIGH_CARDINALITY 32768u

static int expect(bool condition, const char* label) {
    if (condition) return 0;
    fprintf(stderr, "FAIL: %s\n", label);
    return 1;
}

static int drainAndDestroy(VortekHandleRegistry* registry) {
    int failures = 0;
    if (!registry) return 0;
    VortekHandleRegistry_close(registry);
    for (;;) {
        VortekHandleDrainValue drained = {0};
        const VortekHandleStatus status = VortekHandleRegistry_drainNext(
            registry, VORTEK_HANDLE_DRAIN_ALL, 0, &drained);
        failures += expect(status == VORTEK_HANDLE_OK,
            "drain registry before destruction");
        if (status != VORTEK_HANDLE_OK || drained.value.token == 0) break;
    }
    failures += expect(VortekHandleRegistry_liveCount(registry) == 0,
        "registry drain removes every live authority");
    failures += expect(VortekHandleRegistry_destroy(registry),
        "destroy drained registry");
    return failures;
}

static VortekHandleExpectation vulkanExpectation(
    uint64_t generation,
    VkObjectType type,
    VortekHandleOwner owner,
    bool requireInstance,
    bool requireDevice,
    bool allowNull) {
    return (VortekHandleExpectation) {
        .contextGeneration = generation,
        .role = VORTEK_HANDLE_ROLE_VULKAN,
        .vulkanType = type,
        .owner = owner,
        .requireInstanceOwner = requireInstance,
        .requireDeviceOwner = requireDevice,
        .allowNull = allowNull,
    };
}

typedef struct WindowValidatorState {
    uint64_t generation;
    VortekHandleToken instance;
    uint64_t lifetime;
    uint32_t liveWindow;
} WindowValidatorState;

static uint64_t validateWindow(
    void* userdata,
    uint64_t generation,
    VortekHandleToken instance,
    uint32_t windowId) {
    const WindowValidatorState* state = userdata;
    return generation == state->generation && instance == state->instance &&
        windowId == state->liveWindow ? state->lifetime : 0;
}

static int basicAuthorityTest(void) {
    int failures = 0;
    VortekHandleRegistry* registry = VortekHandleRegistry_create(TEST_GENERATION, 32);
    failures += expect(registry != NULL, "create registry");
    if (!registry) return failures;

    VortekHandleToken instanceA = 0, instanceB = 0, deviceA = 0, deviceB = 0;
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_INSTANCE, 0x10001u,
        (VortekHandleOwner){.instance = 0, .device = 0, .parent = 0}, &instanceA) ==
        VORTEK_HANDLE_OK, "register instance A");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_INSTANCE, 0x10002u,
        (VortekHandleOwner){.instance = 0, .device = 0, .parent = 0}, &instanceB) ==
        VORTEK_HANDLE_OK, "register instance B");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_DEVICE, 0x20001u,
        (VortekHandleOwner){.instance = instanceA, .device = 0, .parent = 0}, &deviceA) ==
        VORTEK_HANDLE_OK, "register device A");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_DEVICE, 0x20002u,
        (VortekHandleOwner){.instance = instanceB, .device = 0, .parent = 0}, &deviceB) ==
        VORTEK_HANDLE_OK, "register device B");

    VortekHandleToken buffer = 0;
    VortekHandleOwner ownerA = {
        .instance = instanceA, .device = deviceA, .parent = 0};
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_BUFFER, 0x30001u, ownerA, &buffer) == VORTEK_HANDLE_OK,
        "register buffer");
    failures += expect(instanceA != 0 && instanceB > instanceA && deviceA > instanceB &&
        deviceB > deviceA && buffer > deviceB, "monotonic nonzero tokens");

    VortekHandleExpectation bufferExpected = vulkanExpectation(
        TEST_GENERATION, VK_OBJECT_TYPE_BUFFER, ownerA, true, true, false);
    VortekHandleValue value = {0};
    failures += expect(VortekHandleRegistry_lookup(registry, buffer, &bufferExpected, &value) ==
        VORTEK_HANDLE_OK && value.hostValue == 0x30001u && value.token == buffer,
        "valid typed lookup");
    failures += expect(VortekHandleRegistry_lookup(registry, buffer + 1000u,
        &bufferExpected, &value) == VORTEK_HANDLE_UNKNOWN, "unknown token rejected");

    VortekHandleExpectation wrongType = bufferExpected;
    wrongType.vulkanType = VK_OBJECT_TYPE_IMAGE;
    failures += expect(VortekHandleRegistry_lookup(registry, buffer, &wrongType, &value) ==
        VORTEK_HANDLE_WRONG_TYPE, "wrong Vulkan type rejected");
    VortekHandleExpectation wrongRole = bufferExpected;
    wrongRole.role = VORTEK_HANDLE_ROLE_RESOURCE_MEMORY;
    wrongRole.vulkanType = VK_OBJECT_TYPE_UNKNOWN;
    failures += expect(VortekHandleRegistry_lookup(registry, buffer, &wrongRole, &value) ==
        VORTEK_HANDLE_WRONG_ROLE, "wrong wrapper role rejected");
    VortekHandleExpectation wrongOwner = bufferExpected;
    wrongOwner.owner = (VortekHandleOwner){
        .instance = instanceB, .device = deviceB, .parent = 0};
    failures += expect(VortekHandleRegistry_lookup(registry, buffer, &wrongOwner, &value) ==
        VORTEK_HANDLE_WRONG_OWNER, "cross-owner lookup rejected");
    VortekHandleExpectation wrongGeneration = bufferExpected;
    wrongGeneration.contextGeneration++;
    failures += expect(VortekHandleRegistry_lookup(registry, buffer, &wrongGeneration, &value) ==
        VORTEK_HANDLE_WRONG_GENERATION, "cross-generation lookup rejected");
    failures += expect(VortekHandleRegistry_lookup(registry, 0, &bufferExpected, &value) ==
        VORTEK_HANDLE_NULL, "null rejected by default");
    VortekHandleExpectation nullable = bufferExpected;
    nullable.allowNull = true;
    failures += expect(VortekHandleRegistry_lookup(registry, 0, &nullable, &value) ==
        VORTEK_HANDLE_OK && value.hostValue == 0, "explicit nullable lookup");

    int wrapperPayload = 7;
    VortekHandleToken wrapper = 0;
    failures += expect(VortekHandleRegistry_registerWrapper(registry,
        VORTEK_HANDLE_ROLE_RESOURCE_MEMORY, &wrapperPayload, ownerA, &wrapper) ==
        VORTEK_HANDLE_OK, "register exact wrapper class");
    VortekHandleExpectation wrapperExpected = {
        .contextGeneration = TEST_GENERATION,
        .role = VORTEK_HANDLE_ROLE_RESOURCE_MEMORY,
        .owner = ownerA,
        .requireInstanceOwner = true,
        .requireDeviceOwner = true,
    };
    failures += expect(VortekHandleRegistry_lookup(registry, wrapper, &wrapperExpected, &value) ==
        VORTEK_HANDLE_OK && value.hostValue == (uint64_t)(uintptr_t)&wrapperPayload,
        "wrapper lookup preserves host pointer privately");

    failures += expect(VortekHandleRegistry_unregister(registry, buffer,
        &bufferExpected, NULL) == VORTEK_HANDLE_OK, "tombstone live token");
    failures += expect(VortekHandleRegistry_lookup(registry, buffer, &bufferExpected, &value) ==
        VORTEK_HANDLE_STALE, "tombstoned token rejected as stale");
    VortekHandleToken replacement = 0;
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_BUFFER, 0x30002u, ownerA, &replacement) == VORTEK_HANDLE_OK &&
        replacement > wrapper && replacement != buffer, "slot reuse never reuses token");

    VortekHandleExpectation deviceExpected = vulkanExpectation(
        TEST_GENERATION, VK_OBJECT_TYPE_DEVICE,
        (VortekHandleOwner){.instance = instanceA, .device = 0, .parent = 0},
        true, false, false);
    failures += expect(VortekHandleRegistry_unregister(registry, deviceA,
        &deviceExpected, NULL) == VORTEK_HANDLE_RETIRING,
        "device root cannot retire before its live children");
    failures += expect(VortekHandleRegistry_lookup(registry, replacement,
        &bufferExpected, &value) == VORTEK_HANDLE_OK,
        "refused root retirement preserves child authority");

    WindowValidatorState windowState = {
        .generation = TEST_GENERATION,
        .instance = instanceA,
        .lifetime = UINT64_C(0x9001),
        .liveWindow = 42u,
    };
    VortekHandleRegistry_setWindowValidator(registry, validateWindow, &windowState);
    uint32_t windowId = 0;
    failures += expect(VortekHandleRegistry_validateWindowId(registry, 42u,
        TEST_GENERATION, instanceA, false, &windowId) == VORTEK_HANDLE_OK && windowId == 42u,
        "live WINDOW_ID accepted separately");
    failures += expect(VortekHandleRegistry_validateWindowId(registry, 42u,
        TEST_GENERATION, instanceA, false, &windowId) == VORTEK_HANDLE_OK,
        "same WINDOW_ID lifetime remains accepted");
    windowState.lifetime = UINT64_C(0x9002);
    failures += expect(VortekHandleRegistry_validateWindowId(registry, 42u,
        TEST_GENERATION, instanceA, false, &windowId) == VORTEK_HANDLE_WINDOW_REJECTED,
        "reused WINDOW_ID lifetime is rejected for same instance");
    windowState.lifetime = UINT64_C(0x9001);
    failures += expect(VortekHandleRegistry_validateWindowId(registry, 43u,
        TEST_GENERATION, instanceA, false, &windowId) == VORTEK_HANDLE_WINDOW_REJECTED,
        "unknown WINDOW_ID rejected");
    failures += expect(VortekHandleRegistry_validateWindowId(registry, UINT64_MAX,
        TEST_GENERATION, instanceA, false, &windowId) == VORTEK_HANDLE_WINDOW_REJECTED,
        "wide surface value cannot truncate into WINDOW_ID");
    failures += expect(VortekHandleRegistry_validateWindowId(registry, 42u,
        TEST_GENERATION, instanceB, false, &windowId) == VORTEK_HANDLE_WINDOW_REJECTED,
        "cross-owner WINDOW_ID rejected");
    windowState.instance = instanceB;
    windowState.lifetime = UINT64_C(0xa001);
    failures += expect(VortekHandleRegistry_validateWindowId(registry, 42u,
        TEST_GENERATION, instanceB, false, &windowId) == VORTEK_HANDLE_OK,
        "new instance may bind the same live WINDOW_ID");
    VortekHandleDrainValue retiredInstanceValue = {0};
    failures += expect(VortekHandleRegistry_drainNext(registry,
        VORTEK_HANDLE_DRAIN_INSTANCE, instanceB, &retiredInstanceValue) ==
        VORTEK_HANDLE_OK && retiredInstanceValue.value.token == deviceB,
        "instance retirement drains its device first");
    failures += expect(VortekHandleRegistry_drainNext(registry,
        VORTEK_HANDLE_DRAIN_INSTANCE, instanceB, &retiredInstanceValue) ==
        VORTEK_HANDLE_OK && retiredInstanceValue.value.token == instanceB,
        "instance retirement drains the root and purges window bindings");
    windowState.lifetime = UINT64_C(0xa002);
    failures += expect(VortekHandleRegistry_validateWindowId(registry, 42u,
        TEST_GENERATION, instanceB, false, &windowId) ==
        VORTEK_HANDLE_WINDOW_REJECTED,
        "retired instance cannot re-establish a window binding");

    VortekHandleRegistry_close(registry);
    failures += expect(VortekHandleRegistry_lookup(registry, instanceA,
        &(VortekHandleExpectation){
            .contextGeneration = TEST_GENERATION,
            .role = VORTEK_HANDLE_ROLE_VULKAN,
            .vulkanType = VK_OBJECT_TYPE_INSTANCE,
        }, &value) == VORTEK_HANDLE_CLOSED, "closed registry rejects lookup");
    failures += drainAndDestroy(registry);

    VortekHandleRegistry* recreated = VortekHandleRegistry_create(TEST_GENERATION + 1u, 4);
    VortekHandleToken recreatedInstance = 0;
    failures += expect(recreated != NULL && VortekHandleRegistry_registerVulkan(recreated,
        VK_OBJECT_TYPE_INSTANCE, 0x50001u,
        (VortekHandleOwner){.instance = 0, .device = 0, .parent = 0},
        &recreatedInstance) ==
        VORTEK_HANDLE_OK, "recreate context authority");
    failures += expect(recreatedInstance != instanceA,
        "recreated context never reuses an old wire token");
    failures += expect(VortekHandleRegistry_lookup(recreated, instanceA,
        &(VortekHandleExpectation){
            .contextGeneration = TEST_GENERATION + 1u,
            .role = VORTEK_HANDLE_ROLE_VULKAN,
            .vulkanType = VK_OBJECT_TYPE_INSTANCE,
        }, &value) != VORTEK_HANDLE_OK,
        "old wire token replay is rejected under the new generation");
    failures += expect(VortekHandleRegistry_lookup(recreated, recreatedInstance,
        &(VortekHandleExpectation){
            .contextGeneration = TEST_GENERATION,
            .role = VORTEK_HANDLE_ROLE_VULKAN,
            .vulkanType = VK_OBJECT_TYPE_INSTANCE,
        }, &value) == VORTEK_HANDLE_WRONG_GENERATION,
        "destroy/recreate rejects prior generation");
    failures += drainAndDestroy(recreated);
    return failures;
}

typedef struct Worker {
    VortekHandleRegistry* registry;
    VortekHandleOwner owner;
    VortekHandleToken* tokens;
    unsigned index;
    TestAtomicInt* failures;
} Worker;

static void* runWorker(void* opaque) {
    Worker* worker = opaque;
    VortekHandleExpectation expected = vulkanExpectation(
        0x202u, VK_OBJECT_TYPE_BUFFER, worker->owner, true, true, false);
    VortekHandleToken previous = 0;
    for (unsigned i = 0; i < TOKENS_PER_THREAD; ++i) {
        VortekHandleToken token = 0;
        uint64_t host = 0x100000u + worker->index * TOKENS_PER_THREAD + i;
        if (VortekHandleRegistry_registerVulkan(worker->registry,
                VK_OBJECT_TYPE_BUFFER, host, worker->owner, &token) != VORTEK_HANDLE_OK ||
            token <= previous) {
            testAtomicIncrement(worker->failures);
            continue;
        }
        worker->tokens[i] = token;
        previous = token;
        VortekHandleValue value;
        if (VortekHandleRegistry_lookup(worker->registry, token, &expected, &value) !=
                VORTEK_HANDLE_OK || value.hostValue != host ||
            VortekHandleRegistry_unregister(worker->registry, token, &expected, NULL) !=
                VORTEK_HANDLE_OK) {
            testAtomicIncrement(worker->failures);
        }
    }
    return NULL;
}

#if defined(_WIN32)
static DWORD WINAPI runWorkerWindows(LPVOID opaque) {
    (void)runWorker(opaque);
    return 0;
}
#endif

static int compareTokens(const void* left, const void* right) {
    const VortekHandleToken a = *(const VortekHandleToken*)left;
    const VortekHandleToken b = *(const VortekHandleToken*)right;
    return a < b ? -1 : a > b ? 1 : 0;
}

static int concurrencyTest(void) {
    int failures = 0;
    VortekHandleRegistry* registry = VortekHandleRegistry_create(0x202u, 64);
    if (!registry) return expect(false, "create concurrency registry");
    VortekHandleToken instance = 0, device = 0;
    VortekHandleRegistry_registerVulkan(registry, VK_OBJECT_TYPE_INSTANCE,
        0x60001u,
        (VortekHandleOwner){.instance = 0, .device = 0, .parent = 0}, &instance);
    VortekHandleRegistry_registerVulkan(registry, VK_OBJECT_TYPE_DEVICE,
        0x60002u,
        (VortekHandleOwner){.instance = instance, .device = 0, .parent = 0}, &device);

#if defined(_WIN32)
    HANDLE threads[THREAD_COUNT] = {0};
#else
    pthread_t threads[THREAD_COUNT];
#endif
    Worker workers[THREAD_COUNT];
    VortekHandleToken* allTokens = calloc(
        THREAD_COUNT * TOKENS_PER_THREAD, sizeof(*allTokens));
    TestAtomicInt workerFailures = 0;
    for (unsigned i = 0; i < THREAD_COUNT; ++i) {
        workers[i] = (Worker) {
            .registry = registry,
            .owner = {
                .instance = instance, .device = device, .parent = 0},
            .tokens = allTokens + i * TOKENS_PER_THREAD,
            .index = i,
            .failures = &workerFailures,
        };
#if defined(_WIN32)
        threads[i] = CreateThread(NULL, 0, runWorkerWindows, &workers[i], 0, NULL);
        if (!threads[i]) {
            testAtomicIncrement(&workerFailures);
        }
#else
        if (pthread_create(&threads[i], NULL, runWorker, &workers[i]) != 0) {
            testAtomicIncrement(&workerFailures);
            threads[i] = (pthread_t)0;
        }
#endif
    }
    for (unsigned i = 0; i < THREAD_COUNT; ++i) {
#if defined(_WIN32)
        if (threads[i]) {
            WaitForSingleObject(threads[i], INFINITE);
            CloseHandle(threads[i]);
        }
#else
        if (threads[i]) pthread_join(threads[i], NULL);
#endif
    }
    failures += expect(testAtomicLoad(&workerFailures) == 0,
        "concurrent register/lookup/tombstone operations");
    qsort(allTokens, THREAD_COUNT * TOKENS_PER_THREAD,
        sizeof(*allTokens), compareTokens);
    for (size_t i = 1; i < THREAD_COUNT * TOKENS_PER_THREAD; ++i) {
        if (allTokens[i] == 0 || allTokens[i] == allTokens[i - 1]) {
            failures += expect(false, "concurrent tokens are unique and nonzero");
            break;
        }
    }
    failures += expect(VortekHandleRegistry_liveCount(registry) == 2,
        "concurrent tombstones retain only owners");
    free(allTokens);
    failures += drainAndDestroy(registry);
    return failures;
}

static int highCardinalityTest(void) {
    int failures = 0;
    VortekHandleRegistry* registry = VortekHandleRegistry_create(
        0x303u, (size_t)HIGH_CARDINALITY + 2u);
    if (!registry) return expect(false, "create high-cardinality registry");

    VortekHandleToken instance = 0, device = 0;
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_INSTANCE, 0x70001u,
        (VortekHandleOwner){.instance = 0, .device = 0, .parent = 0}, &instance) ==
        VORTEK_HANDLE_OK, "register high-cardinality instance");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_DEVICE, 0x70002u,
        (VortekHandleOwner){.instance = instance, .device = 0, .parent = 0}, &device) ==
        VORTEK_HANDLE_OK, "register high-cardinality device");

    VortekHandleOwner owner = {
        .instance = instance, .device = device, .parent = 0};
    VortekHandleExpectation expected = vulkanExpectation(
        0x303u, VK_OBJECT_TYPE_BUFFER, owner, true, true, false);
    VortekHandleToken* tokens = calloc(HIGH_CARDINALITY, sizeof(*tokens));
    if (!tokens) {
        (void)drainAndDestroy(registry);
        return expect(false, "allocate high-cardinality tokens");
    }

    for (uint32_t index = 0; index < HIGH_CARDINALITY; ++index) {
        VortekHandleStatus status = VortekHandleRegistry_registerVulkan(registry,
            VK_OBJECT_TYPE_BUFFER, UINT64_C(0x800000) + index, owner, &tokens[index]);
        if (status != VORTEK_HANDLE_OK) {
            failures += expect(false, "fill bounded token index");
            break;
        }
    }
    for (uint32_t offset = 0; offset < HIGH_CARDINALITY; ++offset) {
        const uint32_t index = HIGH_CARDINALITY - offset - 1u;
        VortekHandleValue value = {0};
        if (VortekHandleRegistry_lookup(registry, tokens[index], &expected, &value) !=
                VORTEK_HANDLE_OK ||
            value.hostValue != UINT64_C(0x800000) + index) {
            failures += expect(false, "indexed reverse lookup at high cardinality");
            break;
        }
    }

    const VortekHandleToken priorLast = tokens[HIGH_CARDINALITY - 1u];
    for (uint32_t index = 0; index < HIGH_CARDINALITY; ++index) {
        if (VortekHandleRegistry_unregister(registry, tokens[index], &expected, NULL) !=
            VORTEK_HANDLE_OK) {
            failures += expect(false, "retire high-cardinality token set");
            break;
        }
    }
    for (uint32_t index = 0; index < HIGH_CARDINALITY; ++index) {
        VortekHandleStatus status = VortekHandleRegistry_registerVulkan(registry,
            VK_OBJECT_TYPE_BUFFER, UINT64_C(0x900000) + index, owner, &tokens[index]);
        if (status != VORTEK_HANDLE_OK || tokens[index] <= priorLast) {
            failures += expect(false, "reuse slots without reusing opaque tokens");
            break;
        }
    }
    failures += expect(VortekHandleRegistry_liveCount(registry) ==
        (size_t)HIGH_CARDINALITY + 2u, "bounded index remains complete after churn");

    for (uint32_t index = 0; index < HIGH_CARDINALITY; ++index) {
        if (VortekHandleRegistry_unregister(
                registry, tokens[index], &expected, NULL) != VORTEK_HANDLE_OK) {
            failures += expect(false,
                "retire final high-cardinality token set");
            break;
        }
    }
    free(tokens);
    failures += drainAndDestroy(registry);
    return failures;
}

typedef struct RetirementWorker {
    VortekHandleRegistry* registry;
    VortekHandleToken device;
    uint64_t generation;
    VortekHandleStatus status;
} RetirementWorker;

static void* retireDeviceWorker(void* opaque) {
    RetirementWorker* worker = opaque;
    worker->status = VortekHandleRegistry_beginDeviceRetirement(
        worker->registry, worker->device, worker->generation);
    return NULL;
}

#if defined(_WIN32)
static DWORD WINAPI retireDeviceWorkerWindows(LPVOID opaque) {
    (void)retireDeviceWorker(opaque);
    return 0;
}
#endif

static int lifecycleBatchTest(void) {
    int failures = 0;
    const uint64_t generation = 0x404u;
    VortekHandleRegistry* registry = VortekHandleRegistry_create(generation, 10);
    if (!registry) return expect(false, "create lifecycle registry");

    VortekHandleToken instance = 0, device = 0, commandPool = 0, otherPool = 0;
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_INSTANCE, 0xa001u,
        (VortekHandleOwner){.instance = 0, .device = 0, .parent = 0}, &instance) ==
        VORTEK_HANDLE_OK, "lifecycle instance");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_DEVICE, 0xa002u,
        (VortekHandleOwner){.instance = instance, .device = 0, .parent = 0}, &device) ==
        VORTEK_HANDLE_OK, "lifecycle device");
    failures += expect(VortekHandleRegistry_setDeviceNullDescriptor(
        registry, device, true) == VORTEK_HANDLE_OK,
        "record per-device nullDescriptor feature");
    VortekHandleExpectation deviceExpected = vulkanExpectation(generation,
        VK_OBJECT_TYPE_DEVICE,
        (VortekHandleOwner){.instance = instance, .device = 0, .parent = 0},
        true, false, false);
    VortekHandleValue deviceValue = {0};
    failures += expect(VortekHandleRegistry_lookup(registry, device,
        &deviceExpected, &deviceValue) == VORTEK_HANDLE_OK &&
        deviceValue.nullDescriptorEnabled,
        "lookup returns per-device nullDescriptor feature");

    VortekHandleOwner deviceOwner = {
        .instance = instance, .device = device, .parent = 0};
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_COMMAND_POOL, 0xa003u, deviceOwner, &commandPool) ==
        VORTEK_HANDLE_OK, "lifecycle command pool");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_COMMAND_POOL, 0xa004u, deviceOwner, &otherPool) ==
        VORTEK_HANDLE_OK, "lifecycle second pool");

    const uint64_t commandHosts[2] = {0xb001u, 0xb002u};
    VortekHandleToken commandTokens[2] = {0};
    VortekHandleOwner commandOwner = {
        .instance = instance, .device = device, .parent = commandPool};
    failures += expect(VortekHandleRegistry_registerVulkanBatch(registry,
        VK_OBJECT_TYPE_COMMAND_BUFFER, commandHosts, 2, commandOwner,
        commandTokens) == VORTEK_HANDLE_OK && commandTokens[0] != 0 &&
        commandTokens[1] > commandTokens[0], "atomic child batch publication");

    VortekHandleExpectation commandExpected = vulkanExpectation(generation,
        VK_OBJECT_TYPE_COMMAND_BUFFER, commandOwner, true, true, false);
    commandExpected.requireParentOwner = true;
    VortekHandleExpectation wrongParent = commandExpected;
    wrongParent.owner.parent = otherPool;
    failures += expect(VortekHandleRegistry_unregisterBatch(registry,
        commandTokens, 2, &wrongParent) == VORTEK_HANDLE_WRONG_OWNER &&
        VortekHandleRegistry_liveCount(registry) == 6,
        "wrong-parent batch retires nothing");

    const VortekHandleToken duplicateTokens[2] = {
        commandTokens[0], commandTokens[0]
    };
    failures += expect(VortekHandleRegistry_unregisterBatch(registry,
        duplicateTokens, 2, &commandExpected) == VORTEK_HANDLE_INVALID_ARGUMENT &&
        VortekHandleRegistry_liveCount(registry) == 6,
        "duplicate batch retires nothing");
    failures += expect(VortekHandleRegistry_unregisterBatch(registry,
        commandTokens, 2, &commandExpected) == VORTEK_HANDLE_OK &&
        VortekHandleRegistry_liveCount(registry) == 4,
        "uniform child batch tombstone");

    const uint64_t tooManyHosts[7] = {
        0xc001u, 0xc002u, 0xc003u, 0xc004u, 0xc005u, 0xc006u, 0xc007u
    };
    VortekHandleToken failedTokens[7] = {1, 1, 1, 1, 1, 1, 1};
    failures += expect(VortekHandleRegistry_registerVulkanBatch(registry,
        VK_OBJECT_TYPE_COMMAND_BUFFER, tooManyHosts, 7, commandOwner,
        failedTokens) == VORTEK_HANDLE_CAPACITY &&
        VortekHandleRegistry_liveCount(registry) == 4,
        "failed publication rolls back every inserted token");
    for (size_t index = 0; index < 7; ++index)
        failures += expect(failedTokens[index] == 0,
            "failed publication clears wire outputs");

    const uint64_t resetHosts[2] = {0xd001u, 0xd002u};
    VortekHandleToken resetTokens[2] = {0};
    failures += expect(VortekHandleRegistry_registerVulkanBatch(registry,
        VK_OBJECT_TYPE_COMMAND_BUFFER, resetHosts, 2, commandOwner,
        resetTokens) == VORTEK_HANDLE_OK,
        "publish children for implicit reset");
    failures += expect(VortekHandleRegistry_unregisterChildren(
        registry, commandPool) == VORTEK_HANDLE_OK &&
        VortekHandleRegistry_liveCount(registry) == 4,
        "pool reset tombstones every child");
    failures += expect(VortekHandleRegistry_lookup(registry, resetTokens[0],
        &commandExpected, &deviceValue) == VORTEK_HANDLE_STALE,
        "implicit child tombstone is stale");

    VortekDeviceLease lease = {0};
    failures += expect(VortekHandleRegistry_acquireDeviceLease(registry,
        device, generation, &lease) == VORTEK_HANDLE_OK && lease.active &&
        lease.hostDeviceBits == 0xa002u,
        "acquire exact generation device lease");

    VortekHandleExpectation poolExpected = vulkanExpectation(generation,
        VK_OBJECT_TYPE_COMMAND_POOL, deviceOwner, true, true, false);
    failures += expect(VortekHandleRegistry_unregister(registry, commandPool,
        &poolExpected, NULL) == VORTEK_HANDLE_RETIRING,
        "active device call blocks child retirement");

    const uint64_t leasedChildHost = 0xe001u;
    VortekHandleToken leasedChild = 0;
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_COMMAND_BUFFER, leasedChildHost, commandOwner,
        &leasedChild) == VORTEK_HANDLE_OK,
        "publish child while device call is active");
    failures += expect(VortekHandleRegistry_unregisterBatch(registry,
        &leasedChild, 1, &commandExpected) == VORTEK_HANDLE_RETIRING,
        "active device call blocks batch retirement");
    failures += expect(VortekHandleRegistry_unregisterChildren(registry,
        commandPool) == VORTEK_HANDLE_RETIRING,
        "active device call blocks implicit child cascade");

    const uint64_t pipelineHost = 0xe002u;
    VortekHandleToken pipelineToken = 0;
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_PIPELINE, pipelineHost, deviceOwner, &pipelineToken) ==
        VORTEK_HANDLE_OK, "publish async output under active lease");
    VortekHandleExpectation pipelineExpected = vulkanExpectation(generation,
        VK_OBJECT_TYPE_PIPELINE, deviceOwner, true, true, false);
    failures += expect(VortekHandleRegistry_rollbackBatchWithLease(registry,
        &pipelineToken, 1, &pipelineExpected, &lease) == VORTEK_HANDLE_OK,
        "lease-authorized async publication rollback");
    failures += expect(VortekHandleRegistry_lookup(registry, pipelineToken,
        &pipelineExpected, &deviceValue) == VORTEK_HANDLE_STALE,
        "rolled-back async output is stale");
    VortekDeviceLease wrongGenerationLease = {0};
    failures += expect(VortekHandleRegistry_acquireDeviceLease(registry,
        device, generation + 1u, &wrongGenerationLease) ==
        VORTEK_HANDLE_WRONG_GENERATION,
        "cross-generation lease rejected");

    RetirementWorker retirement = {
        .registry = registry,
        .device = device,
        .generation = generation,
        .status = VORTEK_HANDLE_INVALID_ARGUMENT,
    };
#if defined(_WIN32)
    HANDLE retirementThread = CreateThread(
        NULL, 0, retireDeviceWorkerWindows, &retirement, 0, NULL);
#else
    pthread_t retirementThread = (pthread_t)0;
    int retirementThreadCreated = pthread_create(
        &retirementThread, NULL, retireDeviceWorker, &retirement) == 0;
#endif
    VortekHandleStatus acquireDuringRetirement = VORTEK_HANDLE_OK;
    for (uint32_t attempt = 0; attempt < 100000u; ++attempt) {
        VortekDeviceLease probe = {0};
        acquireDuringRetirement = VortekHandleRegistry_acquireDeviceLease(
            registry, device, generation, &probe);
        if (acquireDuringRetirement == VORTEK_HANDLE_RETIRING) break;
        if (acquireDuringRetirement == VORTEK_HANDLE_OK)
            (void)VortekHandleRegistry_releaseDeviceLease(&probe);
#if defined(_WIN32)
        SwitchToThread();
#else
        sched_yield();
#endif
    }
    failures += expect(acquireDuringRetirement == VORTEK_HANDLE_RETIRING,
        "retirement blocks new async leases");
    failures += expect(VortekHandleRegistry_deviceLeaseShouldCancel(&lease),
        "active lease observes retirement cancellation");
    failures += expect(VortekHandleRegistry_releaseDeviceLease(&lease) ==
        VORTEK_HANDLE_OK, "release async device lease");
#if defined(_WIN32)
    failures += expect(retirementThread != NULL &&
        WaitForSingleObject(retirementThread, INFINITE) == WAIT_OBJECT_0,
        "device retirement waits for leases");
    if (retirementThread) CloseHandle(retirementThread);
#else
    failures += expect(retirementThreadCreated &&
        pthread_join(retirementThread, NULL) == 0,
        "device retirement waits for leases");
#endif
    failures += expect(retirement.status == VORTEK_HANDLE_OK,
        "device retirement completes after final release");

    failures += expect(VortekHandleRegistry_unregisterBatch(registry,
        &leasedChild, 1, &commandExpected) == VORTEK_HANDLE_OK,
        "child retirement resumes after async lease release");

    failures += drainAndDestroy(registry);
    return failures;
}

static int drainOrderTest(void) {
    int failures = 0;
    const uint64_t generation = 0x505u;
    const uint64_t hostDevice = 0xf003u;
    VortekHandleRegistry* registry = VortekHandleRegistry_create(generation, 20);
    if (!registry) return expect(false, "create drain-order registry");

    VortekHandleToken instance = 0, physical = 0, device = 0;
    VortekHandleToken commandPool = 0, commandBuffer = 0, pipeline = 0;
    VortekHandleToken framebuffer = 0, imageView = 0, buffer = 0, memory = 0;
    VortekHandleToken conversion = 0, sampler = 0, swapchain = 0;
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_INSTANCE, 0xf001u,
        (VortekHandleOwner){.instance = 0, .device = 0, .parent = 0},
        &instance) == VORTEK_HANDLE_OK, "drain-order instance");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_PHYSICAL_DEVICE, 0xf002u,
        (VortekHandleOwner){.instance = instance, .device = 0, .parent = 0},
        &physical) == VORTEK_HANDLE_OK, "drain-order physical device");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_DEVICE, hostDevice,
        (VortekHandleOwner){.instance = instance, .device = 0, .parent = 0},
        &device) == VORTEK_HANDLE_OK, "drain-order device");
    const VortekHandleOwner owner = {
        .instance = instance, .device = device, .parent = 0};
    int swapchainPayload = 8;
    failures += expect(VortekHandleRegistry_registerWrapper(registry,
        VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN, &swapchainPayload, owner,
        &swapchain) == VORTEK_HANDLE_OK, "drain-order swapchain wrapper");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_COMMAND_POOL, 0xf004u, owner, &commandPool) ==
        VORTEK_HANDLE_OK, "drain-order command pool");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_COMMAND_BUFFER, 0xf005u,
        (VortekHandleOwner){
            .instance = instance, .device = device, .parent = commandPool},
        &commandBuffer) == VORTEK_HANDLE_OK, "drain-order command buffer");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_PIPELINE, 0xf006u, owner, &pipeline) ==
        VORTEK_HANDLE_OK, "drain-order pipeline");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_FRAMEBUFFER, 0xf009u, owner, &framebuffer) ==
        VORTEK_HANDLE_OK, "drain-order framebuffer");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_IMAGE_VIEW, 0xf007u, owner, &imageView) ==
        VORTEK_HANDLE_OK, "drain-order image view");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_BUFFER, 0xf008u, owner, &buffer) ==
        VORTEK_HANDLE_OK, "drain-order buffer");
    /* Create the conversion first to prove dependency priority, not token
     * order, keeps it alive until its sampler has been destroyed. */
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_SAMPLER_YCBCR_CONVERSION, 0xf00au, owner,
        &conversion) == VORTEK_HANDLE_OK, "drain-order sampler conversion");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_SAMPLER, 0xf00bu, owner, &sampler) ==
        VORTEK_HANDLE_OK, "drain-order sampler");
    int memoryPayload = 9;
    failures += expect(VortekHandleRegistry_registerWrapper(registry,
        VORTEK_HANDLE_ROLE_RESOURCE_MEMORY, &memoryPayload, owner, &memory) ==
        VORTEK_HANDLE_OK, "drain-order resource memory");

    const VortekHandleToken expectedDeviceTokens[] = {
        commandBuffer, pipeline, framebuffer, commandPool, imageView, sampler,
        conversion, buffer, swapchain, memory, device};
    const VortekHandleRole expectedDeviceRoles[] = {
        VORTEK_HANDLE_ROLE_VULKAN, VORTEK_HANDLE_ROLE_VULKAN,
        VORTEK_HANDLE_ROLE_VULKAN, VORTEK_HANDLE_ROLE_VULKAN,
        VORTEK_HANDLE_ROLE_VULKAN, VORTEK_HANDLE_ROLE_VULKAN,
        VORTEK_HANDLE_ROLE_VULKAN, VORTEK_HANDLE_ROLE_VULKAN,
        VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN,
        VORTEK_HANDLE_ROLE_RESOURCE_MEMORY,
        VORTEK_HANDLE_ROLE_VULKAN};
    for (size_t index = 0;
            index < sizeof(expectedDeviceTokens) / sizeof(expectedDeviceTokens[0]);
            ++index) {
        VortekHandleDrainValue drained = {0};
        failures += expect(VortekHandleRegistry_drainNext(registry,
            VORTEK_HANDLE_DRAIN_DEVICE, device, &drained) == VORTEK_HANDLE_OK &&
            drained.value.token == expectedDeviceTokens[index] &&
            drained.value.role == expectedDeviceRoles[index] &&
            drained.hostDeviceValue == hostDevice &&
            drained.waitDevice == (index == 0),
            "device drain is dependency ordered and claims one idle wait");
    }
    VortekHandleDrainValue drained = {0};
    failures += expect(VortekHandleRegistry_drainNext(registry,
        VORTEK_HANDLE_DRAIN_DEVICE, device, &drained) == VORTEK_HANDLE_OK &&
        drained.value.token == 0, "device scope reports exact completion");
    failures += expect(VortekHandleRegistry_drainNext(registry,
        VORTEK_HANDLE_DRAIN_INSTANCE, instance, &drained) == VORTEK_HANDLE_OK &&
        drained.value.token == physical && drained.hostDeviceValue == 0,
        "instance drain retires physical device before instance");
    failures += expect(VortekHandleRegistry_drainNext(registry,
        VORTEK_HANDLE_DRAIN_INSTANCE, instance, &drained) == VORTEK_HANDLE_OK &&
        drained.value.token == instance,
        "instance drain retires the instance last");
    failures += expect(VortekHandleRegistry_liveCount(registry) == 0,
        "scoped drains remove every selected value");
    failures += expect(VortekHandleRegistry_destroy(registry),
        "destroy scoped-drained registry");

    registry = VortekHandleRegistry_create(generation + 1u, 4);
    if (!registry) return failures + expect(false, "create destroy-refusal registry");
    instance = 0;
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_INSTANCE, 0xf101u,
        (VortekHandleOwner){.instance = 0, .device = 0, .parent = 0},
        &instance) == VORTEK_HANDLE_OK, "destroy-refusal live instance");
    failures += expect(!VortekHandleRegistry_destroy(registry) &&
        VortekHandleRegistry_liveCount(registry) == 1,
        "destroy refuses to discard live host authority");
    failures += drainAndDestroy(registry);
    return failures;
}

static int closedLeaseRollbackTest(void) {
    int failures = 0;
    const uint64_t generation = 0x606u;
    VortekHandleRegistry* registry = VortekHandleRegistry_create(generation, 4);
    if (!registry) return expect(false, "create closed-rollback registry");
    VortekHandleToken instance = 0, device = 0, pipeline = 0;
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_INSTANCE, 0x11001u,
        (VortekHandleOwner){.instance = 0, .device = 0, .parent = 0},
        &instance) == VORTEK_HANDLE_OK, "closed-rollback instance");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_DEVICE, 0x11002u,
        (VortekHandleOwner){.instance = instance, .device = 0, .parent = 0},
        &device) == VORTEK_HANDLE_OK, "closed-rollback device");
    const VortekHandleOwner owner = {
        .instance = instance, .device = device, .parent = 0};
    VortekDeviceLease lease = {0};
    failures += expect(VortekHandleRegistry_acquireDeviceLease(
        registry, device, generation, &lease) == VORTEK_HANDLE_OK,
        "closed-rollback device lease");
    failures += expect(VortekHandleRegistry_registerVulkan(registry,
        VK_OBJECT_TYPE_PIPELINE, 0x11003u, owner, &pipeline) ==
        VORTEK_HANDLE_OK, "closed-rollback pipeline publication");
    VortekHandleExpectation expected = vulkanExpectation(generation,
        VK_OBJECT_TYPE_PIPELINE, owner, true, true, false);
    VortekHandleRegistry_beginClose(registry);
    VortekHandleDrainValue blockedDrain = {0};
    failures += expect(VortekHandleRegistry_drainNext(registry,
        VORTEK_HANDLE_DRAIN_DEVICE, device, &blockedDrain) ==
        VORTEK_HANDLE_RETIRING,
        "active lease blocks teardown drain after close");
    failures += expect(VortekHandleRegistry_rollbackBatchWithLease(registry,
        &pipeline, 1, &expected, &lease) == VORTEK_HANDLE_OK,
        "active lease rolls back publication after authority close");
    failures += expect(VortekHandleRegistry_releaseDeviceLease(&lease) ==
        VORTEK_HANDLE_OK, "release closed-rollback lease");
    failures += drainAndDestroy(registry);
    return failures;
}

int main(void) {
    int failures = basicAuthorityTest() + concurrencyTest() +
        highCardinalityTest() + lifecycleBatchTest() + drainOrderTest() +
        closedLeaseRollbackTest();
    if (failures == 0) puts("handle_registry_test: PASS");
    return failures == 0 ? 0 : 1;
}
