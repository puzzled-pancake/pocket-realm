#ifndef WINLATOR_THREAD_POOL_H
#define WINLATOR_THREAD_POOL_H

#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

#define THREAD_POOL_MAX_THREADS 64u
#define THREAD_POOL_MAX_QUEUED_TASKS 256u

typedef void (*ThreadPool_TaskFunc)(void* param);
typedef void (*ThreadPool_TaskCleanupFunc)(void* param);

typedef struct ThreadPool_Task {
    ThreadPool_TaskFunc func;
    ThreadPool_TaskCleanupFunc cleanup;
    void* param;
    struct ThreadPool_Task* next;
} ThreadPool_Task;

/*
 * All mutable state below is protected by mutex.  Workers are joinable and
 * remain owned by the pool until ThreadPool_destroy().  Once shutdown starts,
 * new work is rejected, queued owned tasks are cleaned without execution, and
 * already-running tasks are given a cancellation signal and joined.
 */
typedef struct ThreadPool {
    pthread_mutex_t mutex;
    pthread_cond_t taskCond;
    pthread_cond_t idleCond;
    pthread_t* threads;
    /* Immutable compatibility view used by both pinned BC decoders. */
    int numThreads;
    size_t threadCount;
    size_t activeCount;
    size_t queuedCount;
    bool accepting;
    bool cancellationRequested;
    ThreadPool_Task* firstTask;
    ThreadPool_Task* lastTask;
} ThreadPool;

#if defined(THREAD_POOL_TESTING)
static int threadPoolTestFailCreateAfter = -1;

static inline void ThreadPool_testFailCreateAfter(int successfulCreates) {
    threadPoolTestFailCreateAfter = successfulCreates;
}
#endif

static inline int ThreadPool_createWorker(pthread_t* thread,
                                          void* (*startRoutine)(void*),
                                          void* param,
                                          size_t successfulCreates) {
#if defined(THREAD_POOL_TESTING)
    if (threadPoolTestFailCreateAfter >= 0 &&
            successfulCreates >= (size_t)threadPoolTestFailCreateAfter) {
        return -1;
    }
#endif
    return pthread_create(thread, NULL, startRoutine, param);
}

static inline ThreadPool_Task* ThreadPool_takeTaskLocked(ThreadPool* threadPool) {
    ThreadPool_Task* task = threadPool->firstTask;
    if (!task) return NULL;

    threadPool->firstTask = task->next;
    if (!threadPool->firstTask) threadPool->lastTask = NULL;
    task->next = NULL;
    threadPool->queuedCount--;
    return task;
}

static inline void ThreadPool_cleanupTaskList(ThreadPool_Task* task) {
    while (task) {
        ThreadPool_Task* next = task->next;
        if (task->cleanup) task->cleanup(task->param);
        free(task);
        task = next;
    }
}

static inline void* threadPoolWorker(void* param) {
    ThreadPool* threadPool = param;

    for (;;) {
        pthread_mutex_lock(&threadPool->mutex);
        while (!threadPool->firstTask && threadPool->accepting) {
            pthread_cond_wait(&threadPool->taskCond, &threadPool->mutex);
        }

        ThreadPool_Task* task = ThreadPool_takeTaskLocked(threadPool);
        if (!task) {
            pthread_mutex_unlock(&threadPool->mutex);
            break;
        }
        threadPool->activeCount++;
        pthread_mutex_unlock(&threadPool->mutex);

        task->func(task->param);
        free(task);

        pthread_mutex_lock(&threadPool->mutex);
        threadPool->activeCount--;
        if (threadPool->queuedCount == 0 && threadPool->activeCount == 0) {
            pthread_cond_broadcast(&threadPool->idleCond);
        }
        pthread_mutex_unlock(&threadPool->mutex);
    }
    return NULL;
}

static inline ThreadPool* ThreadPool_init(int requestedThreads) {
    if (requestedThreads <= 0 || (unsigned int)requestedThreads > THREAD_POOL_MAX_THREADS) {
        return NULL;
    }

    ThreadPool* threadPool = calloc(1, sizeof(ThreadPool));
    if (!threadPool) return NULL;

    bool mutexInitialized = false;
    bool taskCondInitialized = false;
    bool idleCondInitialized = false;
    if (pthread_mutex_init(&threadPool->mutex, NULL) != 0) goto error;
    mutexInitialized = true;
    if (pthread_cond_init(&threadPool->taskCond, NULL) != 0) goto error;
    taskCondInitialized = true;
    if (pthread_cond_init(&threadPool->idleCond, NULL) != 0) goto error;
    idleCondInitialized = true;

    threadPool->threads = calloc((size_t)requestedThreads, sizeof(pthread_t));
    if (!threadPool->threads) goto error;
    threadPool->accepting = true;

    for (int i = 0; i < requestedThreads; i++) {
        if (ThreadPool_createWorker(&threadPool->threads[i], threadPoolWorker,
                                    threadPool, threadPool->threadCount) != 0) {
            pthread_mutex_lock(&threadPool->mutex);
            threadPool->accepting = false;
            threadPool->cancellationRequested = true;
            pthread_cond_broadcast(&threadPool->taskCond);
            pthread_mutex_unlock(&threadPool->mutex);
            for (size_t j = 0; j < threadPool->threadCount; j++) {
                pthread_join(threadPool->threads[j], NULL);
            }
            goto error;
        }
        threadPool->threadCount++;
    }
    threadPool->numThreads = requestedThreads;
    return threadPool;

error:
    free(threadPool->threads);
    if (idleCondInitialized) pthread_cond_destroy(&threadPool->idleCond);
    if (taskCondInitialized) pthread_cond_destroy(&threadPool->taskCond);
    if (mutexInitialized) pthread_mutex_destroy(&threadPool->mutex);
    free(threadPool);
    return NULL;
}

/*
 * cleanup is called only when the task is not accepted or is cancelled before
 * it starts.  Once func starts, func owns param and must release it itself.
 */
static inline bool ThreadPool_runWithCleanup(ThreadPool* threadPool,
                                             ThreadPool_TaskFunc func,
                                             void* param,
                                             ThreadPool_TaskCleanupFunc cleanup) {
    if (!threadPool || !func) {
        if (cleanup) cleanup(param);
        return false;
    }

    ThreadPool_Task* task = calloc(1, sizeof(ThreadPool_Task));
    if (!task) {
        if (cleanup) cleanup(param);
        return false;
    }
    task->func = func;
    task->cleanup = cleanup;
    task->param = param;

    pthread_mutex_lock(&threadPool->mutex);
    if (!threadPool->accepting || threadPool->queuedCount >= THREAD_POOL_MAX_QUEUED_TASKS) {
        pthread_mutex_unlock(&threadPool->mutex);
        if (cleanup) cleanup(param);
        free(task);
        return false;
    }

    if (threadPool->lastTask) threadPool->lastTask->next = task;
    else threadPool->firstTask = task;
    threadPool->lastTask = task;
    threadPool->queuedCount++;
    pthread_cond_signal(&threadPool->taskCond);
    pthread_mutex_unlock(&threadPool->mutex);
    return true;
}

static inline bool ThreadPool_run(ThreadPool* threadPool, ThreadPool_TaskFunc func, void* param) {
    if (!threadPool || !func) return false;

    ThreadPool_Task* task = calloc(1, sizeof(ThreadPool_Task));
    if (!task) {
        func(param);
        return true;
    }
    task->func = func;
    task->param = param;

    pthread_mutex_lock(&threadPool->mutex);
    if (!threadPool->accepting) {
        pthread_mutex_unlock(&threadPool->mutex);
        free(task);
        return false;
    }
    if (threadPool->queuedCount >= THREAD_POOL_MAX_QUEUED_TASKS) {
        /* Legacy fixed-size callers do not consume a return value.  Preserve
         * their correctness without allowing the queue to grow unbounded. */
        pthread_mutex_unlock(&threadPool->mutex);
        free(task);
        func(param);
        return true;
    }

    if (threadPool->lastTask) threadPool->lastTask->next = task;
    else threadPool->firstTask = task;
    threadPool->lastTask = task;
    threadPool->queuedCount++;
    pthread_cond_signal(&threadPool->taskCond);
    pthread_mutex_unlock(&threadPool->mutex);
    return true;
}

static inline bool ThreadPool_isCancellationRequested(ThreadPool* threadPool) {
    if (!threadPool) return true;
    pthread_mutex_lock(&threadPool->mutex);
    bool cancelled = threadPool->cancellationRequested;
    pthread_mutex_unlock(&threadPool->mutex);
    return cancelled;
}

static inline void ThreadPool_wait(ThreadPool* threadPool) {
    if (!threadPool) return;
    pthread_mutex_lock(&threadPool->mutex);
    while (threadPool->queuedCount > 0 || threadPool->activeCount > 0) {
        pthread_cond_wait(&threadPool->idleCond, &threadPool->mutex);
    }
    pthread_mutex_unlock(&threadPool->mutex);
}

static inline void ThreadPool_destroy(ThreadPool* threadPool) {
    if (!threadPool) return;

    pthread_mutex_lock(&threadPool->mutex);
    threadPool->accepting = false;
    threadPool->cancellationRequested = true;
    ThreadPool_Task* cancelledTasks = threadPool->firstTask;
    threadPool->firstTask = NULL;
    threadPool->lastTask = NULL;
    threadPool->queuedCount = 0;
    pthread_cond_broadcast(&threadPool->taskCond);
    if (threadPool->activeCount == 0) pthread_cond_broadcast(&threadPool->idleCond);
    pthread_mutex_unlock(&threadPool->mutex);

    ThreadPool_cleanupTaskList(cancelledTasks);
    for (size_t i = 0; i < threadPool->threadCount; i++) {
        pthread_join(threadPool->threads[i], NULL);
    }

    free(threadPool->threads);
    pthread_cond_destroy(&threadPool->idleCond);
    pthread_cond_destroy(&threadPool->taskCond);
    pthread_mutex_destroy(&threadPool->mutex);
    free(threadPool);
}

#endif
