#ifndef VORTEK_XWINDOW_SWAPCHAIN_TRANSACTION_H
#define VORTEK_XWINDOW_SWAPCHAIN_TRANSACTION_H

#include <stddef.h>
#include <stdint.h>

typedef struct VtXWindowTransactionImage {
    uint64_t image;
    uint64_t memory;
} VtXWindowTransactionImage;

typedef struct VtXWindowTransactionOps {
    int32_t (*beginImage)(void* userdata, uint32_t index);
    int32_t (*createImage)(
            void* userdata, uint32_t index, uint64_t* imageOut);
    int32_t (*allocateMemory)(
            void* userdata, uint32_t index, uint64_t image,
            uint64_t* memoryOut);
    int32_t (*bindImageMemory)(
            void* userdata, uint32_t index, uint64_t image,
            uint64_t memory);
    void (*endImage)(void* userdata, uint32_t index);
    void (*destroyImage)(void* userdata, uint64_t image);
    void (*freeMemory)(void* userdata, uint64_t memory);
    int32_t invalidResult;
} VtXWindowTransactionOps;

typedef struct VtXWindowHeapOps {
    void* (*allocateZeroed)(void* userdata, size_t count, size_t size);
    void (*freeAllocation)(void* userdata, void* allocation);
    int32_t outOfMemoryResult;
    int32_t invalidResult;
} VtXWindowHeapOps;

/* Allocates the wrapper and its image array as a single outer transaction.
 * Outputs remain NULL and the wrapper is rolled back if the second allocation
 * fails. */
static inline int32_t vt_xwindow_transaction_allocate_heap(
        const VtXWindowHeapOps* ops, void* userdata, size_t wrapperSize,
        uint32_t imageCount, size_t imageSize,
        void** wrapperOut, void** imagesOut) {
    if (wrapperOut) *wrapperOut = NULL;
    if (imagesOut) *imagesOut = NULL;
    if (!ops || !ops->allocateZeroed || !ops->freeAllocation ||
            !wrapperOut || !imagesOut || wrapperSize == 0 ||
            imageCount == 0 || imageSize == 0) {
        return ops ? ops->invalidResult : INT32_MIN;
    }
    if ((size_t)imageCount > SIZE_MAX / imageSize)
        return ops->outOfMemoryResult;

    void* wrapper = ops->allocateZeroed(userdata, 1, wrapperSize);
    if (!wrapper) return ops->outOfMemoryResult;
    void* images = ops->allocateZeroed(
            userdata, (size_t)imageCount, imageSize);
    if (!images) {
        ops->freeAllocation(userdata, wrapper);
        return ops->outOfMemoryResult;
    }
    *wrapperOut = wrapper;
    *imagesOut = images;
    return 0;
}

static inline void vt_xwindow_transaction_rollback(
        const VtXWindowTransactionOps* ops, void* userdata,
        VtXWindowTransactionImage* images, uint32_t count) {
    if (!ops || !images) return;
    while (count > 0) {
        VtXWindowTransactionImage* image = &images[--count];
        if (image->image != 0 && ops->destroyImage) {
            ops->destroyImage(userdata, image->image);
        }
        if (image->memory != 0 && ops->freeMemory) {
            ops->freeMemory(userdata, image->memory);
        }
        image->image = 0;
        image->memory = 0;
    }
}

/* Builds an image array transactionally. beginImage/endImage bracket the
 * temporary AHardwareBuffer reference.  A failed child rolls back itself and
 * every previously completed child exactly once. */
static inline int32_t vt_xwindow_transaction_build(
        const VtXWindowTransactionOps* ops, void* userdata,
        uint32_t count, VtXWindowTransactionImage* images) {
    if (!ops || !ops->beginImage || !ops->createImage ||
            !ops->allocateMemory || !ops->bindImageMemory ||
            !ops->endImage || !ops->destroyImage || !ops->freeMemory ||
            count == 0 || !images) {
        return ops ? ops->invalidResult : INT32_MIN;
    }

    for (uint32_t index = 0; index < count; ++index) {
        uint64_t image = 0;
        uint64_t memory = 0;
        int32_t result = ops->beginImage(userdata, index);
        if (result == 0) result = ops->createImage(userdata, index, &image);
        if (result == 0 && image == 0) result = ops->invalidResult;
        if (result == 0) {
            result = ops->allocateMemory(
                    userdata, index, image, &memory);
        }
        if (result == 0 && memory == 0) result = ops->invalidResult;
        if (result == 0) {
            result = ops->bindImageMemory(
                    userdata, index, image, memory);
        }
        ops->endImage(userdata, index);

        if (result != 0) {
            if (image != 0) ops->destroyImage(userdata, image);
            if (memory != 0) ops->freeMemory(userdata, memory);
            vt_xwindow_transaction_rollback(ops, userdata, images, index);
            return result;
        }
        images[index].image = image;
        images[index].memory = memory;
    }
    return 0;
}

#endif
