#include <assert.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#include "thread_pool.h"

typedef struct BlockingJob {
    ThreadPool* pool;
    atomic_int* started;
    atomic_int* finished;
} BlockingJob;

static void blockingUntilCancelled(void* param) {
    BlockingJob* job = param;
    atomic_store_explicit(job->started, 1, memory_order_release);
    while (!ThreadPool_isCancellationRequested(job->pool)) sched_yield();
    atomic_store_explicit(job->finished, 1, memory_order_release);
}

static void increment(void* param) {
    atomic_fetch_add_explicit((atomic_int*)param, 1, memory_order_relaxed);
}

static void cleanupIncrement(void* param) {
    atomic_fetch_add_explicit((atomic_int*)param, 1, memory_order_relaxed);
}

static void waitUntilSet(atomic_int* value) {
    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    while (!atomic_load_explicit(value, memory_order_acquire)) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        assert(now.tv_sec - start.tv_sec < 5);
        sched_yield();
    }
}

static void testInvalidInitialization(void) {
    assert(ThreadPool_init(0) == NULL);
    assert(ThreadPool_init(-1) == NULL);
    assert(ThreadPool_init((int)THREAD_POOL_MAX_THREADS + 1) == NULL);
    ThreadPool_testFailCreateAfter(1);
    assert(ThreadPool_init(4) == NULL);
    ThreadPool_testFailCreateAfter(-1);
}

static void testOrdinaryDrain(void) {
    atomic_int count = 0;
    ThreadPool* pool = ThreadPool_init(4);
    assert(pool != NULL);
    assert(pool->numThreads == 4);
    for (int i = 0; i < 100; i++) assert(ThreadPool_run(pool, increment, &count));
    ThreadPool_wait(pool);
    assert(atomic_load(&count) == 100);
    ThreadPool_destroy(pool);
}

static void testCancellationAndQueuedCleanup(void) {
    atomic_int started = 0;
    atomic_int finished = 0;
    atomic_int cleaned = 0;
    ThreadPool* pool = ThreadPool_init(1);
    assert(pool != NULL);

    BlockingJob blocking = {pool, &started, &finished};
    assert(ThreadPool_runWithCleanup(pool, blockingUntilCancelled, &blocking, NULL));
    waitUntilSet(&started);

    const int queued = 32;
    for (int i = 0; i < queued; i++) {
        assert(ThreadPool_runWithCleanup(pool, increment, &cleaned, cleanupIncrement));
    }
    ThreadPool_destroy(pool);
    assert(atomic_load(&finished) == 1);
    assert(atomic_load(&cleaned) == queued);
}

static void testRepeatedLifecycle(void) {
    for (int i = 0; i < 100; i++) {
        atomic_int count = 0;
        ThreadPool* pool = ThreadPool_init(2);
        assert(pool != NULL);
        assert(ThreadPool_run(pool, increment, &count));
        ThreadPool_wait(pool);
        assert(atomic_load(&count) == 1);
        ThreadPool_destroy(pool);
    }
}

int main(void) {
    testInvalidInitialization();
    testOrdinaryDrain();
    testCancellationAndQueuedCleanup();
    testRepeatedLifecycle();
    puts("thread_pool_lifecycle_test: PASS");
    return EXIT_SUCCESS;
}
