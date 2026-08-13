#include <unistd.h>
#include <stdio.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <pthread.h>
#include <sys/mman.h>

#include "ring_buffer.h"
#include "time_utils.h"
#include "events.h"

#define STRUCT_OFFSETS() \
    struct Offsets { \
        uint32_t head; \
        uint32_t tail; \
        uint32_t status; \
        void* buffer; \
    }

#ifdef __ANDROID__
#include <android/log.h>
#define debug_printf(...) __android_log_print(ANDROID_LOG_DEBUG, "System.out", __VA_ARGS__)
#else
#define debug_printf(...) fprintf(stderr, __VA_ARGS__)
#endif

void RingBuffer_setHead(RingBuffer* ring, uint32_t head) {
    atomic_store_explicit(ring->head, head, memory_order_release);
}

uint32_t RingBuffer_getHead(RingBuffer* ring) {
    return atomic_load_explicit(ring->head, memory_order_acquire);
}

void RingBuffer_setTail(RingBuffer* ring, uint32_t tail) {
    return atomic_store_explicit(ring->tail, tail, memory_order_release);
}

uint32_t RingBuffer_getTail(RingBuffer* ring) {
    return atomic_load_explicit(ring->tail, memory_order_acquire);
}

void RingBuffer_setStatus(RingBuffer* ring, uint32_t status) {
    atomic_fetch_or_explicit(ring->status, status, memory_order_seq_cst);
}

void RingBuffer_unsetStatus(RingBuffer* ring, uint32_t status) {
    atomic_fetch_and_explicit(ring->status, ~status, memory_order_seq_cst);
}

bool RingBuffer_hasStatus(RingBuffer* ring, uint32_t status) {
    return (atomic_load_explicit(ring->status, memory_order_seq_cst) & status);
}

RingBuffer* RingBuffer_create(int shmFd, uint32_t bufferSize) {
    STRUCT_OFFSETS();
    if (shmFd < 0 || bufferSize == 0 ||
            (bufferSize & (bufferSize - 1u)) != 0 ||
            bufferSize > UINT32_MAX - (uint32_t)offsetof(struct Offsets, buffer)) {
        return NULL;
    }
    RingBuffer* ring = calloc(1, sizeof(RingBuffer));
    if (!ring) return NULL;

    int shmSize = RingBuffer_getSHMemSize(bufferSize);
    void* sharedData = mmap(NULL, shmSize, PROT_READ | PROT_WRITE, MAP_SHARED, shmFd, 0);
    if (sharedData == MAP_FAILED) {
        free(ring);
        return NULL;
    }
    memset(sharedData, 0, shmSize);

    ring->sharedData = sharedData;
    ring->head = sharedData + offsetof(struct Offsets, head);
    ring->tail = sharedData + offsetof(struct Offsets, tail);
    ring->status = sharedData + offsetof(struct Offsets, status);
    ring->buffer = sharedData + offsetof(struct Offsets, buffer);
    ring->bufferSize = bufferSize;

    RingBuffer_setStatus(ring, RING_STATUS_IDLE);
    return ring;
}

uint32_t RingBuffer_size(RingBuffer* ring) {
    if (!ring) return 0;
    return RingBuffer_getTail(ring) - RingBuffer_getHead(ring);
}

uint32_t RingBuffer_freeSpace(RingBuffer* ring) {
    if (!ring) return 0;
    const uint32_t used = RingBuffer_size(ring);
    return used <= ring->bufferSize ? ring->bufferSize - used : 0;
}

bool RingBuffer_read(RingBuffer* ring, void* data, uint32_t size) {
    if (!ring || !ring->buffer || (size != 0 && !data) ||
            size > ring->bufferSize) {
        debug_printf("ring: invalid read (%u/%u)\n", size,
                ring ? ring->bufferSize : 0u);
        return false;
    }

    if (!RingBuffer_waitForRead(ring, size)) return false;
    uint32_t head = RingBuffer_getHead(ring);
    uint32_t offset = head & (ring->bufferSize - 1);

    if ((offset + size) <= ring->bufferSize) {
        memcpy(data, ring->buffer + offset, size);
    }
    else {
        uint32_t start = ring->bufferSize - offset;
        memcpy(data, ring->buffer + offset, start);
        memcpy(data + start, ring->buffer, size - start);
    }

    RingBuffer_setHead(ring, head + size);
    return true;
}

bool RingBuffer_write(RingBuffer* ring, const void* data, uint32_t size) {
    if (!ring || !ring->buffer || (size != 0 && !data) ||
            size > ring->bufferSize) {
        debug_printf("ring: invalid write (%u/%u)\n", size,
                ring ? ring->bufferSize : 0u);
        return false;
    }

    if (!RingBuffer_waitForWrite(ring, size)) return false;
    uint32_t tail = RingBuffer_getTail(ring);
    uint32_t offset = tail & (ring->bufferSize - 1);

    if ((offset + size) <= ring->bufferSize) {
        memcpy(ring->buffer + offset, data, size);
    }
    else {
        uint32_t start = ring->bufferSize - offset;
        memcpy(ring->buffer + offset, data, start);
        memcpy(ring->buffer, data + start, size - start);
    }

    RingBuffer_setTail(ring, tail + size);
    return true;
}

static void RingBuffer_copyAt(
        RingBuffer* ring, uint32_t position, const void* source, uint32_t size) {
    if (size == 0) return;
    const uint32_t offset = position & (ring->bufferSize - 1u);
    uint8_t* destination = (uint8_t*)ring->buffer;
    const uint8_t* bytes = (const uint8_t*)source;
    if (offset + size <= ring->bufferSize) {
        memcpy(destination + offset, bytes, size);
    }
    else {
        const uint32_t first = ring->bufferSize - offset;
        memcpy(destination + offset, bytes, first);
        memcpy(destination, bytes + first, size - first);
    }
}

bool RingBuffer_writeFrame(
        RingBuffer* ring,
        const void* header,
        uint32_t headerSize,
        const void* data,
        uint32_t dataSize) {
    if (!ring || !ring->buffer || !header || headerSize == 0 ||
            (dataSize != 0 && !data) ||
            headerSize > ring->bufferSize ||
            dataSize > ring->bufferSize - headerSize) {
        return false;
    }
    const uint32_t total = headerSize + dataSize;
    if (!RingBuffer_waitForWrite(ring, total)) return false;
    const uint32_t tail = RingBuffer_getTail(ring);
    RingBuffer_copyAt(ring, tail, header, headerSize);
    RingBuffer_copyAt(ring, tail + headerSize, data, dataSize);
    RingBuffer_setTail(ring, tail + total);
    return true;
}

uint32_t RingBuffer_getSHMemSize(uint32_t bufferSize) {
    STRUCT_OFFSETS();

    return bufferSize + offsetof(struct Offsets, buffer);
}

void RingBuffer_free(RingBuffer* ring) {
    if (!ring) return;

    if (ring->sharedData) {
        RingBuffer_setStatus(ring, RING_STATUS_EXIT);
        munmap(ring->sharedData, RingBuffer_getSHMemSize(ring->bufferSize));
        ring->sharedData = NULL;
    }

    free(ring);
}

bool RingBuffer_waitForRead(RingBuffer* ring, uint32_t size) {
    if (!ring || size > ring->bufferSize) return false;
    uint32_t busyWaitIter = 0;
    do {
        if (RingBuffer_size(ring) >= size) break;
        busyWait(&busyWaitIter);
        if (RingBuffer_hasStatus(ring, RING_STATUS_EXIT)) return false;
    }
    while (1);
    return true;
}

bool RingBuffer_waitForWrite(RingBuffer* ring, uint32_t size) {
    if (!ring || size > ring->bufferSize) return false;
    uint32_t busyWaitIter = 0;
    do {
        if (RingBuffer_freeSpace(ring) >= size) break;
        busyWait(&busyWaitIter);
        if (RingBuffer_hasStatus(ring, RING_STATUS_EXIT)) return false;
    }
    while (1);
    return true;
}
