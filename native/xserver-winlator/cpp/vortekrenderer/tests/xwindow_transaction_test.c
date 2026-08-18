#include "xwindow_swapchain_transaction.h"

#include <stdbool.h>
#include <stdio.h>
#include <string.h>

#define CHILDREN 3u
#define FAILURE_RESULT (-17)
#define INVALID_RESULT (-23)

typedef enum FailureStage {
    FAIL_NONE,
    FAIL_BEGIN,
    FAIL_CREATE,
    FAIL_ALLOCATE,
    FAIL_BIND,
} FailureStage;

typedef struct TestState {
    FailureStage stage;
    uint32_t failIndex;
    uint32_t begins;
    uint32_t ends;
    uint32_t creates;
    uint32_t allocations;
    uint32_t binds;
    uint32_t destroys;
    uint32_t frees;
} TestState;

typedef struct AllocationState {
    uint32_t calls;
    uint32_t failCall;
    uint32_t frees;
    unsigned char storage[128];
} AllocationState;

static void* allocateZeroed(void* userdata, size_t count, size_t size) {
    AllocationState* state = userdata;
    ++state->calls;
    if (state->calls == state->failCall || count > SIZE_MAX / size ||
            count * size > sizeof(state->storage) / 2u) {
        return NULL;
    }
    const size_t offset = state->calls == 1 ? 0 : sizeof(state->storage) / 2u;
    memset(state->storage + offset, 0, count * size);
    return state->storage + offset;
}

static void freeAllocation(void* userdata, void* allocation) {
    AllocationState* state = userdata;
    (void)allocation;
    ++state->frees;
}

static const VtXWindowHeapOps heapOps = {
    .allocateZeroed = allocateZeroed,
    .freeAllocation = freeAllocation,
    .outOfMemoryResult = FAILURE_RESULT,
    .invalidResult = INVALID_RESULT,
};

static int32_t beginImage(void* userdata, uint32_t index) {
    TestState* state = userdata;
    ++state->begins;
    return state->stage == FAIL_BEGIN && state->failIndex == index
            ? FAILURE_RESULT : 0;
}

static int32_t createImage(
        void* userdata, uint32_t index, uint64_t* imageOut) {
    TestState* state = userdata;
    ++state->creates;
    if (state->stage == FAIL_CREATE && state->failIndex == index)
        return FAILURE_RESULT;
    *imageOut = UINT64_C(0x1000) + index;
    return 0;
}

static int32_t allocateMemory(
        void* userdata, uint32_t index, uint64_t image,
        uint64_t* memoryOut) {
    TestState* state = userdata;
    (void)image;
    ++state->allocations;
    if (state->stage == FAIL_ALLOCATE && state->failIndex == index)
        return FAILURE_RESULT;
    *memoryOut = UINT64_C(0x2000) + index;
    return 0;
}

static int32_t bindImageMemory(
        void* userdata, uint32_t index, uint64_t image, uint64_t memory) {
    TestState* state = userdata;
    (void)image;
    (void)memory;
    ++state->binds;
    return state->stage == FAIL_BIND && state->failIndex == index
            ? FAILURE_RESULT : 0;
}

static void endImage(void* userdata, uint32_t index) {
    TestState* state = userdata;
    (void)index;
    ++state->ends;
}

static void destroyImage(void* userdata, uint64_t image) {
    TestState* state = userdata;
    (void)image;
    ++state->destroys;
}

static void freeMemory(void* userdata, uint64_t memory) {
    TestState* state = userdata;
    (void)memory;
    ++state->frees;
}

static const VtXWindowTransactionOps ops = {
    .beginImage = beginImage,
    .createImage = createImage,
    .allocateMemory = allocateMemory,
    .bindImageMemory = bindImageMemory,
    .endImage = endImage,
    .destroyImage = destroyImage,
    .freeMemory = freeMemory,
    .invalidResult = INVALID_RESULT,
};

static int expect(bool condition, const char* label) {
    if (condition) return 0;
    fprintf(stderr, "FAIL: %s\n", label);
    return 1;
}

static int failureCase(FailureStage stage, uint32_t failIndex) {
    TestState state = {.stage = stage, .failIndex = failIndex};
    VtXWindowTransactionImage images[CHILDREN] = {{0}};
    const int32_t result = vt_xwindow_transaction_build(
            &ops, &state, CHILDREN, images);
    const uint32_t completed = failIndex;
    int failures = 0;
    failures += expect(result == FAILURE_RESULT, "injected failure returned");
    failures += expect(state.begins == failIndex + 1u, "bounded begin count");
    failures += expect(state.ends == failIndex + 1u, "every begin is ended");
    failures += expect(state.destroys == completed +
            (stage == FAIL_ALLOCATE || stage == FAIL_BIND ? 1u : 0u),
            "created images destroyed exactly once");
    failures += expect(state.frees == completed +
            (stage == FAIL_BIND ? 1u : 0u),
            "allocated memory freed exactly once");
    for (uint32_t index = 0; index < CHILDREN; ++index) {
        failures += expect(images[index].image == 0 && images[index].memory == 0,
                "failed transaction leaves no committed resource");
    }
    return failures;
}

int main(void) {
    int failures = 0;

    for (uint32_t failCall = 1; failCall <= 2; ++failCall) {
        AllocationState allocation = {.failCall = failCall};
        void* wrapper = (void*)(uintptr_t)1;
        void* childImages = (void*)(uintptr_t)1;
        failures += expect(vt_xwindow_transaction_allocate_heap(
                &heapOps, &allocation, 32, CHILDREN, 8,
                &wrapper, &childImages) == FAILURE_RESULT,
                "allocation failure returned");
        failures += expect(wrapper == NULL && childImages == NULL,
                "failed allocation publishes no pointers");
        failures += expect(allocation.frees == (failCall == 2 ? 1u : 0u),
                "second allocation failure rolls back wrapper exactly once");
    }

    AllocationState allocated = {0};
    void* wrapper = NULL;
    void* childImages = NULL;
    failures += expect(vt_xwindow_transaction_allocate_heap(
            &heapOps, &allocated, 32, CHILDREN, 8,
            &wrapper, &childImages) == 0 && wrapper && childImages,
            "outer allocation transaction succeeds");

    TestState success = {0};
    VtXWindowTransactionImage images[CHILDREN] = {{0}};
    failures += expect(vt_xwindow_transaction_build(
            &ops, &success, CHILDREN, images) == 0, "successful transaction");
    failures += expect(success.begins == CHILDREN && success.ends == CHILDREN &&
            success.creates == CHILDREN && success.allocations == CHILDREN &&
            success.binds == CHILDREN && success.destroys == 0 &&
            success.frees == 0, "success has no rollback");
    vt_xwindow_transaction_rollback(&ops, &success, images, CHILDREN);
    failures += expect(success.destroys == CHILDREN && success.frees == CHILDREN,
            "explicit teardown destroys all committed children");

    for (uint32_t index = 0; index < CHILDREN; ++index) {
        failures += failureCase(FAIL_BEGIN, index);
        failures += failureCase(FAIL_CREATE, index);
        failures += failureCase(FAIL_ALLOCATE, index);
        failures += failureCase(FAIL_BIND, index);
    }
    if (failures == 0) puts("xwindow_transaction_test: PASS");
    return failures == 0 ? 0 : 1;
}
