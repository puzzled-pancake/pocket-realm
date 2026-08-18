#include "vk_context.h"
#include "vulkan_helper.h"
#include "request_handler.h"
#include "sysvshared_memory.h"
#include "string_utils.h"
#include "jni_utils.h"

#include <errno.h>
#include <limits.h>
#include <poll.h>
#include <time.h>

static atomic_uint_fast64_t nextContextGeneration = ATOMIC_VAR_INIT(1);

static uint64_t allocateContextGeneration(void) {
    uint_fast64_t current = atomic_load_explicit(
            &nextContextGeneration, memory_order_relaxed);
    for (;;) {
        /* Zero is invalid and UINT64_MAX is a permanent exhaustion latch.
         * Never wrap and reuse a context authority generation. */
        if (current == 0 || current == UINT64_MAX) return 0;
        const uint_fast64_t next = current + 1u;
        if (atomic_compare_exchange_weak_explicit(&nextContextGeneration,
                &current, next, memory_order_relaxed,
                memory_order_relaxed)) return (uint64_t)current;
    }
}

static bool cacheJMethods(JMethods* jmethods, JNIEnv* env) {
    if (!jmethods || !env || !jmethods->obj) return false;
    jclass cls = (*env)->GetObjectClass(env, jmethods->obj);
    if (!cls || (*env)->ExceptionCheck(env)) goto error;
    jmethods->registerWindowAuthorityGeneration = (*env)->GetMethodID(
            env, cls, "registerWindowAuthorityGeneration", "(J)Z");
    jmethods->unregisterWindowAuthorityGeneration = (*env)->GetMethodID(
            env, cls, "unregisterWindowAuthorityGeneration", "(J)Z");
    jmethods->releaseWindowAuthorityInstance = (*env)->GetMethodID(
            env, cls, "releaseWindowAuthorityInstance", "(JJ)Z");
    jmethods->validateWindowAuthority = (*env)->GetMethodID(
            env, cls, "validateWindowAuthority", "(JJI)J");
    jmethods->getWindowExtentAuthority = (*env)->GetMethodID(
            env, cls, "getWindowExtentAuthority", "(JJI)J");
    jmethods->getWindowHardwareBufferAuthority = (*env)->GetMethodID(
            env, cls, "getWindowHardwareBufferAuthority", "(JJIZ)J");
    jmethods->updateWindowContentAuthority = (*env)->GetMethodID(
            env, cls, "updateWindowContentAuthority", "(JJI)Z");
    bool success = jmethods->registerWindowAuthorityGeneration &&
                   jmethods->unregisterWindowAuthorityGeneration &&
                   jmethods->releaseWindowAuthorityInstance &&
                   jmethods->validateWindowAuthority &&
                   jmethods->getWindowExtentAuthority &&
                   jmethods->getWindowHardwareBufferAuthority &&
                   jmethods->updateWindowContentAuthority &&
                   !(*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, cls);
    return success;

error:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (cls) (*env)->DeleteLocalRef(env, cls);
    return false;
}

static bool attachJniEnvironment(JMethods* jmethods) {
    JNIEnv* env = NULL;
    if (!jmethods || !jmethods->jvm ||
            (*jmethods->jvm)->AttachCurrentThread(jmethods->jvm, &env, NULL) != JNI_OK ||
            !env) return false;
    jmethods->env = env;
    return true;
}

static uint64_t validateWindowAuthorityCallback(
        void* userdata, uint64_t contextGeneration,
        VortekHandleToken instanceOwner, uint32_t windowId) {
    VkContext* context = userdata;
    if (!context || !context->jmethods.env || !context->jmethods.obj ||
            !context->jmethods.validateWindowAuthority ||
            contextGeneration != context->contextGeneration) return 0;
    JNIEnv* env = context->jmethods.env;
    const jlong lifetime = (*env)->CallLongMethod(env, context->jmethods.obj,
            context->jmethods.validateWindowAuthority,
            (jlong)contextGeneration, (jlong)instanceOwner, (jint)windowId);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        println("Vortek window authority validation raised an exception");
        return 0;
    }
    return lifetime > 0 ? (uint64_t)lifetime : 0;
}

static bool callWindowAuthorityBoolean(
        JNIEnv* env, jobject obj, jmethodID method,
        jlong first, jlong second, bool hasSecond) {
    if (!env || !obj || !method) return false;
    const jboolean result = hasSecond
            ? (*env)->CallBooleanMethod(env, obj, method, first, second)
            : (*env)->CallBooleanMethod(env, obj, method, first);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        println("Vortek window authority lifecycle callback raised an exception");
        return false;
    }
    return result == JNI_TRUE;
}

bool VkContext_isClosing(VkContext* context) {
    return !context || atomic_load_explicit(&context->closing, memory_order_acquire) ||
           atomic_load_explicit(&context->status, memory_order_acquire) < 0;
}

void VkContext_requestStop(VkContext* context, VkResult status) {
    if (!context) return;
    atomic_store_explicit(&context->status, status, memory_order_release);
    atomic_store_explicit(&context->closing, true, memory_order_release);
    VkObjectAuthority_beginClose(context->handleAuthority);

    if (context->lifecycleMutexInitialized) {
        pthread_mutex_lock(&context->lifecycleMutex);
        if (context->lifecycleCondInitialized) {
            pthread_cond_broadcast(&context->lifecycleCond);
        }
        pthread_mutex_unlock(&context->lifecycleMutex);
    }
    if (context->serverRing) RingBuffer_setStatus(context->serverRing, RING_STATUS_EXIT);
    if (context->clientRing) RingBuffer_setStatus(context->clientRing, RING_STATUS_EXIT);
}

bool VkContext_acquireDeviceLease(
        VkContext* context, uint64_t deviceToken,
        VortekDeviceLease* leaseOut) {
    if (!context || !leaseOut || VkContext_isClosing(context) ||
            !context->handleAuthority || context->contextGeneration == 0) {
        return false;
    }
    return VkObjectAuthority_acquireDeviceLease(context->handleAuthority,
            deviceToken, context->contextGeneration, leaseOut) ==
            VORTEK_HANDLE_OK;
}

bool VkContext_releaseDeviceLease(VortekDeviceLease* lease) {
    return VkObjectAuthority_releaseDeviceLease(lease) == VORTEK_HANDLE_OK;
}

bool VkContext_beginDeviceRetirement(
        VkContext* context, uint64_t deviceToken) {
    return context && context->handleAuthority &&
            VkObjectAuthority_beginDeviceRetirement(context->handleAuthority,
                    deviceToken, context->contextGeneration) == VORTEK_HANDLE_OK;
}

bool VkContext_beginInstanceRetirement(
        VkContext* context, uint64_t instanceToken) {
    return context && context->handleAuthority &&
            VkObjectAuthority_beginInstanceRetirement(context->handleAuthority,
                    instanceToken, context->contextGeneration) == VORTEK_HANDLE_OK;
}

bool VkContext_releaseWindowInstanceAuthority(
        VkContext* context, uint64_t instanceToken) {
    return context && context->windowAuthorityGenerationRegistered &&
            callWindowAuthorityBoolean(context->jmethods.env,
                    context->jmethods.obj,
                    context->jmethods.releaseWindowAuthorityInstance,
                    (jlong)context->contextGeneration, (jlong)instanceToken,
                    true);
}

static void reclaimDrainedAuthorityValue(
        VkContext* context, const VortekHandleDrainValue* drained) {
    if (!context || !drained || drained->value.token == 0) return;
    const VortekHandleValue* value = &drained->value;
    const VkDevice device = (VkDevice)(uintptr_t)drained->hostDeviceValue;
    if (drained->waitDevice && device && vulkanWrapper.vkDeviceWaitIdle)
        (void)vulkanWrapper.vkDeviceWaitIdle(device);

    switch (value->role) {
        case VORTEK_HANDLE_ROLE_RESOURCE_MEMORY:
            ResourceMemory_free(context, device,
                    (ResourceMemory*)(uintptr_t)value->hostValue);
            return;
        case VORTEK_HANDLE_ROLE_SHADER_MODULE:
            destroyVkObject(VK_OBJECT_TYPE_SHADER_MODULE, device,
                    (void*)(uintptr_t)value->hostValue);
            return;
        case VORTEK_HANDLE_ROLE_XWINDOW_SWAPCHAIN:
            XWindowSwapchain_destroy(device,
                    (XWindowSwapchain*)(uintptr_t)value->hostValue);
            return;
        case VORTEK_HANDLE_ROLE_WINDOW_ID:
            return;
        case VORTEK_HANDLE_ROLE_VULKAN:
            break;
    }

    /* Pool-owned handles and swapchain images have no individual Vulkan
     * destruction operation.  Their parent is ordered after these tokens. */
    if (value->vulkanType == VK_OBJECT_TYPE_COMMAND_BUFFER ||
            value->vulkanType == VK_OBJECT_TYPE_DESCRIPTOR_SET ||
            (value->vulkanType == VK_OBJECT_TYPE_IMAGE &&
             value->owner.parent != 0) ||
            value->vulkanType == VK_OBJECT_TYPE_QUEUE ||
            value->vulkanType == VK_OBJECT_TYPE_PHYSICAL_DEVICE) {
        return;
    }
    if (value->vulkanType == VK_OBJECT_TYPE_BUFFER && context->textureDecoder) {
        TextureDecoder_removeBoundBuffer(context->textureDecoder,
                (VkBuffer)(uintptr_t)value->hostValue);
    }
    if (value->vulkanType == VK_OBJECT_TYPE_IMAGE &&
            context->textureDecoder &&
            TextureDecoder_containsImage(context->textureDecoder,
                    (VkImage)(uintptr_t)value->hostValue)) {
        TextureDecoder_destroyImage(context->textureDecoder, device,
                (VkImage)(uintptr_t)value->hostValue);
        return;
    }
#if ENABLE_VALIDATION_LAYER
    if (value->vulkanType == VK_OBJECT_TYPE_INSTANCE &&
            context->debugReportCallback) {
        vulkanWrapper.vkDestroyDebugReportCallback(
                (VkInstance)(uintptr_t)value->hostValue,
                context->debugReportCallback, NULL);
        context->debugReportCallback = VK_NULL_HANDLE;
    }
#endif
    destroyVkObject(value->vulkanType, device,
            (void*)(uintptr_t)value->hostValue);
}

bool VkContext_reclaimAuthority(
        VkContext* context, VortekHandleDrainScope scope,
        uint64_t scopeToken) {
    if (!context || !context->handleAuthority ||
            (scope != VORTEK_HANDLE_DRAIN_ALL && scopeToken == 0)) return false;
    for (;;) {
        VortekHandleDrainValue drained = {0};
        const VortekHandleStatus status = VkObjectAuthority_drainNext(
                context->handleAuthority, scope, scopeToken, &drained);
        if (status != VORTEK_HANDLE_OK) return false;
        if (drained.value.token == 0) return true;
        reclaimDrainedAuthorityValue(context, &drained);
    }
}

static void setDeadlineFromNow(struct timespec* deadline, uint32_t timeoutMs) {
    clock_gettime(CLOCK_REALTIME, deadline);
    deadline->tv_sec += timeoutMs / 1000u;
    deadline->tv_nsec += (long)(timeoutMs % 1000u) * 1000000L;
    if (deadline->tv_nsec >= 1000000000L) {
        deadline->tv_sec++;
        deadline->tv_nsec -= 1000000000L;
    }
}

static uint64_t monotonicMillis(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t)now.tv_sec * 1000u + (uint64_t)now.tv_nsec / 1000000u;
}

static bool readExactBeforeDeadline(VkContext* context, void* data, size_t size) {
    uint8_t* current = data;
    size_t remaining = size;
    uint64_t deadline = monotonicMillis() + VORTEK_EXTRA_DATA_WAIT_TIMEOUT_MS;

    while (remaining > 0 && !VkContext_isClosing(context)) {
        uint64_t now = monotonicMillis();
        if (now >= deadline) return false;
        uint64_t remainingMs = deadline - now;
        int pollTimeout = remainingMs > 100u ? 100 : (int)remainingMs;
        if (pollTimeout <= 0) pollTimeout = 1;

        struct pollfd pfd = {.fd = context->clientFd, .events = POLLIN};
        int pollResult;
        do {
            pollResult = poll(&pfd, 1, pollTimeout);
        } while (pollResult < 0 && errno == EINTR);
        if (pollResult < 0) return false;
        if (pollResult == 0) continue;
        if (!(pfd.revents & POLLIN)) {
            if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return false;
            continue;
        }

        ssize_t bytesRead;
        do {
            bytesRead = read(context->clientFd, current, remaining);
        } while (bytesRead < 0 && errno == EINTR);
        if (bytesRead <= 0) return false;
        current += (size_t)bytesRead;
        remaining -= (size_t)bytesRead;
    }
    return remaining == 0;
}

static ExtraDataRequest* findExtraDataRequestLocked(VkContext* context,
                                                    uint16_t requestId,
                                                    ExtraDataState state) {
    for (uint32_t i = 0; i < VORTEK_EXTRA_DATA_MAX_PENDING; i++) {
        ExtraDataRequest* request = &context->extraDataRequests[i];
        if (request->state == state && request->requestId == requestId) return request;
    }
    return NULL;
}

static ExtraDataRequest* findEmptyExtraDataSlotLocked(VkContext* context) {
    for (uint32_t i = 0; i < VORTEK_EXTRA_DATA_MAX_PENDING; i++) {
        if (context->extraDataRequests[i].state == EXTRA_DATA_EMPTY) {
            return &context->extraDataRequests[i];
        }
    }
    return NULL;
}

static bool requestIdWasSeenLocked(VkContext* context, uint16_t requestId) {
    uint32_t byteIndex = requestId >> 3u;
    uint8_t bit = (uint8_t)(1u << (requestId & 7u));
    return (context->seenExtraDataRequestIds[byteIndex] & bit) != 0;
}

static void markRequestIdSeenLocked(VkContext* context, uint16_t requestId) {
    uint32_t byteIndex = requestId >> 3u;
    uint8_t bit = (uint8_t)(1u << (requestId & 7u));
    context->seenExtraDataRequestIds[byteIndex] |= bit;
}

static ExtraDataRequest* waitForExtraDataRequest(VkContext* context, uint16_t requestId) {
    struct timespec deadline;
    setDeadlineFromNow(&deadline, VORTEK_EXTRA_DATA_WAIT_TIMEOUT_MS);

    pthread_mutex_lock(&context->lifecycleMutex);
    ExtraDataRequest* result = NULL;
    while (!VkContext_isClosing(context)) {
        result = findExtraDataRequestLocked(context, requestId, EXTRA_DATA_READY);
        if (result) {
            if (context->pendingExtraDataBytes >= result->size) {
                context->pendingExtraDataBytes -= result->size;
            }
            else context->pendingExtraDataBytes = 0;
            if (context->pendingExtraDataCount > 0) context->pendingExtraDataCount--;
            /* READING now means exclusively owned by the request thread. */
            result->state = EXTRA_DATA_READING;
            break;
        }

        int waitResult = pthread_cond_timedwait(&context->lifecycleCond,
                                               &context->lifecycleMutex,
                                               &deadline);
        if (waitResult == ETIMEDOUT) {
            atomic_store_explicit(&context->status, VK_ERROR_DEVICE_LOST, memory_order_release);
            atomic_store_explicit(&context->closing, true, memory_order_release);
            break;
        }
        if (waitResult != 0) {
            atomic_store_explicit(&context->status, VK_ERROR_DEVICE_LOST, memory_order_release);
            atomic_store_explicit(&context->closing, true, memory_order_release);
            break;
        }
    }
    pthread_mutex_unlock(&context->lifecycleMutex);
    return result;
}

static void releaseExtraDataRequest(VkContext* context, ExtraDataRequest* request) {
    if (!request) return;
    free(request->data);
    pthread_mutex_lock(&context->lifecycleMutex);
    memset(request, 0, sizeof(*request));
    pthread_cond_broadcast(&context->lifecycleCond);
    pthread_mutex_unlock(&context->lifecycleMutex);
}

static void* requestHandlerThread(void* param) {
    VkContext* context = param;
    if (!attachJniEnvironment(&context->jmethods)) {
        VkContext_requestStop(context, VK_ERROR_INITIALIZATION_FAILED);
        return NULL;
    }

    while (!VkContext_isClosing(context)) {
        ExtraDataRequest* extraDataRequest = NULL;
        int requestCode = vt_recv(context->serverRing, &context->inputBuffer,
                                  &context->inputBufferSize, &context->memoryPool);
        if (requestCode < 0) break;

        if (requestCode > INT16_MAX) {
            uint16_t requestId = (uint16_t)(requestCode & 0xffff);
            requestCode >>= 16;
            extraDataRequest = waitForExtraDataRequest(context, requestId);
            if (!extraDataRequest) break;
            if (extraDataRequest->size > INT_MAX) {
                releaseExtraDataRequest(context, extraDataRequest);
                VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
                break;
            }
            context->inputBufferSize = (int)extraDataRequest->size;
            context->inputBuffer = extraDataRequest->data;
        }

#if DEBUG_MODE
        println("handleRequest name=%s size=%d", requestCodeToString(requestCode), context->inputBufferSize);
#endif

        HandleRequestFunc handleRequestFunc = getHandleRequestFunc(requestCode);
        if (!handleRequestFunc) {
            releaseExtraDataRequest(context, extraDataRequest);
            VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
            break;
        }
        handleRequestFunc(context);

        vt_free(&context->memoryPool);
        releaseExtraDataRequest(context, extraDataRequest);
        context->inputBuffer = NULL;
        context->inputBufferSize = 0;
    }

    context->inputBuffer = NULL;
    context->inputBufferSize = 0;
    VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);
    (*context->jmethods.jvm)->DetachCurrentThread(context->jmethods.jvm);
    context->jmethods.env = NULL;
    vt_free(&context->memoryPool);
    return NULL;
}

static bool setupRingBuffers(VkContext* context) {
    bool success = false;
    int shmFds[2] = {-1, -1};
    shmFds[0] = ashmemCreateRegion("vt-server-ring", RingBuffer_getSHMemSize(SERVER_RING_BUFFER_SIZE));
    if (shmFds[0] < 0) goto done;

    shmFds[1] = ashmemCreateRegion("vt-client-ring", RingBuffer_getSHMemSize(CLIENT_RING_BUFFER_SIZE));
    if (shmFds[1] < 0) goto done;

    context->serverRing = RingBuffer_create(shmFds[0], SERVER_RING_BUFFER_SIZE);
    if (!context->serverRing) goto done;

    context->clientRing = RingBuffer_create(shmFds[1], CLIENT_RING_BUFFER_SIZE);
    if (!context->clientRing) goto done;

    if (send_fds(context->clientFd, shmFds, 2, NULL, 0) < 0) goto done;
    if (pthread_create(&context->requestHandlerThread, NULL, requestHandlerThread, context) != 0) goto done;
    context->requestHandlerThreadStarted = true;
    success = true;

done:
    if (shmFds[0] >= 0) close(shmFds[0]);
    if (shmFds[1] >= 0) close(shmFds[1]);
    return success;
}

VkContext* createVkContext(JNIEnv* env, jobject obj, int clientFd, jobject options) {
    if (!env || !obj || !options || clientFd < 0) return NULL;
    jobjectArray exposedDeviceExtensions =
            getJFieldByName(env, options, "exposedDeviceExtensions", JSIGNATURE_ARRAY_STRING).l;

    VkContext* context = calloc(1, sizeof(VkContext));
    if (!context) return NULL;
    context->clientFd = clientFd;
    atomic_init(&context->status, VK_SUCCESS);
    atomic_init(&context->closing, false);
    context->contextGeneration = allocateContextGeneration();
    if (context->contextGeneration == 0) goto error;
    context->handleAuthority = VkObjectAuthority_create(
            context->contextGeneration, VORTEK_HANDLE_AUTHORITY_CAPACITY);
    if (!context->handleAuthority) goto error;

    if (pthread_mutex_init(&context->lifecycleMutex, NULL) != 0) goto error;
    context->lifecycleMutexInitialized = true;
    if (pthread_mutex_init(&context->pipelineMutex, NULL) != 0) goto error;
    context->pipelineMutexInitialized = true;
    if (pthread_cond_init(&context->lifecycleCond, NULL) != 0) goto error;
    context->lifecycleCondInitialized = true;

    context->vkMaxVersion = getJFieldByName(env, options, "vkMaxVersion", "I").i;
    context->maxDeviceMemory = getJFieldByName(env, options, "maxDeviceMemory", "S").s;
    context->imageCacheSize = getJFieldByName(env, options, "imageCacheSize", "S").s;
    context->resourceMemoryType = getJFieldByName(env, options, "resourceMemoryType", "B").b;
    context->hardenedSafeLane =
            getJFieldByName(env, options, "hardenedSafeLane", "Z").z == JNI_TRUE;
    if (!context->hardenedSafeLane) goto error;
    context->exposedDeviceExtensions = jstringArrayToCharArray(env, exposedDeviceExtensions);
    if (exposedDeviceExtensions) (*env)->DeleteLocalRef(env, exposedDeviceExtensions);
    if ((*env)->ExceptionCheck(env)) goto error;

    context->memoryPool.data = calloc(MEMORY_POOL_MAX_SIZE, 1);
    if (!context->memoryPool.data) goto error;
    context->threadPool = ThreadPool_init(THREAD_POOL_NUM_THREADS);
    if (!context->threadPool) goto error;

    if ((*env)->GetJavaVM(env, &context->jmethods.jvm) != JNI_OK || !context->jmethods.jvm) goto error;
    context->jmethods.obj = (*env)->NewGlobalRef(env, obj);
    if (!context->jmethods.obj) goto error;

    /* Cache and prove every JNI authority entry point before the request
     * thread can observe a ring buffer. */
    if (!cacheJMethods(&context->jmethods, env)) goto error;
    if (!callWindowAuthorityBoolean(env, context->jmethods.obj,
            context->jmethods.registerWindowAuthorityGeneration,
            (jlong)context->contextGeneration, 0, false)) goto error;
    context->windowAuthorityGenerationRegistered = true;
    VortekHandleRegistry_setWindowValidator(context->handleAuthority,
            validateWindowAuthorityCallback, context);

    if (!setupRingBuffers(context)) goto error;
    return context;

error:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    destroyVkContext(env, context);
    return NULL;
}

void destroyVkContext(JNIEnv* env, VkContext* context) {
    if (!context) return;
    VkContext_requestStop(context, VK_ERROR_DEVICE_LOST);

    if (context->requestHandlerThreadStarted &&
            !pthread_equal(pthread_self(), context->requestHandlerThread)) {
        pthread_join(context->requestHandlerThread, NULL);
        context->requestHandlerThreadStarted = false;
        memset(&context->requestHandlerThread, 0, sizeof(context->requestHandlerThread));
    }

    /* Running async Vulkan/JNI jobs must finish before any helpers or global
     * Java references they may use are released.  Queued owned jobs are
     * cancelled and cleaned by the pool. */
    ThreadPool_destroy(context->threadPool);
    context->threadPool = NULL;

    /* The Java generation remains registered until all request and async
     * users have joined, then is purged before its global reference dies. */
    if (context->windowAuthorityGenerationRegistered) {
        if (!callWindowAuthorityBoolean(env, context->jmethods.obj,
                context->jmethods.unregisterWindowAuthorityGeneration,
                (jlong)context->contextGeneration, 0, false)) {
            println("Vortek window authority generation cleanup failed");
        }
        context->windowAuthorityGenerationRegistered = false;
    }

    /* requestStop blocked new leases; workers and cancellation cleanups have
     * now released all existing leases, so close cannot strand a queued job. */
    VkObjectAuthority_close(context->handleAuthority);
    if (!VkContext_reclaimAuthority(
            context, VORTEK_HANDLE_DRAIN_ALL, 0)) {
        println("Vortek authority reclamation failed");
    }
    if (!VkObjectAuthority_destroy(context->handleAuthority)) {
        /* Never clear live host values merely to free registry bookkeeping. */
        println("Vortek authority destroy refused undrained values");
    }
    context->handleAuthority = NULL;

    RingBuffer_free(context->serverRing);
    context->serverRing = NULL;
    RingBuffer_free(context->clientRing);
    context->clientRing = NULL;

    if (context->textureDecoder) {
        TextureDecoder_destroy(context->textureDecoder);
        context->textureDecoder = NULL;
    }
    free(context->shaderInspector);
    context->shaderInspector = NULL;

    if (context->jmethods.obj && env) {
        (*env)->DeleteGlobalRef(env, context->jmethods.obj);
        context->jmethods.obj = NULL;
    }

    context->graphicsQueueIndex = 0;
    ArrayList_free(context->exposedDeviceExtensions, true);
    context->exposedDeviceExtensions = NULL;
    ArrayList_free(context->disabledDeviceExtensions, true);
    context->disabledDeviceExtensions = NULL;

    for (uint32_t i = 0; i < VORTEK_EXTRA_DATA_MAX_PENDING; i++) {
        free(context->extraDataRequests[i].data);
        context->extraDataRequests[i].data = NULL;
        context->extraDataRequests[i].state = EXTRA_DATA_EMPTY;
    }

    vt_free(&context->memoryPool);
    ArrayList_free(&context->memoryPool.allocationList, false);
    free(context->memoryPool.data);
    context->memoryPool.data = NULL;
    free(context->engineName);
    context->engineName = NULL;

    if (context->lifecycleCondInitialized) pthread_cond_destroy(&context->lifecycleCond);
    if (context->pipelineMutexInitialized) pthread_mutex_destroy(&context->pipelineMutex);
    if (context->lifecycleMutexInitialized) pthread_mutex_destroy(&context->lifecycleMutex);
    free(context);
}

bool handleExtraDataRequest(VkContext* context, uint16_t requestId, int requestLength) {
    if (!context || requestLength < 0 ||
            (uint64_t)requestLength > VORTEK_EXTRA_DATA_MAX_FRAME_SIZE) {
        return false;
    }
    size_t dataSize = (size_t)requestLength;

    pthread_mutex_lock(&context->lifecycleMutex);
    if (VkContext_isClosing(context) || requestIdWasSeenLocked(context, requestId) ||
            context->pendingExtraDataCount >= VORTEK_EXTRA_DATA_MAX_PENDING ||
            dataSize > VORTEK_EXTRA_DATA_MAX_AGGREGATE_SIZE - context->pendingExtraDataBytes) {
        pthread_mutex_unlock(&context->lifecycleMutex);
        return false;
    }
    ExtraDataRequest* slot = findEmptyExtraDataSlotLocked(context);
    if (!slot) {
        pthread_mutex_unlock(&context->lifecycleMutex);
        return false;
    }
    /* Reserve id and aggregate budget before the blocking socket read. */
    markRequestIdSeenLocked(context, requestId);
    slot->requestId = requestId;
    slot->size = dataSize;
    slot->state = EXTRA_DATA_READING;
    context->pendingExtraDataBytes += dataSize;
    context->pendingExtraDataCount++;
    pthread_mutex_unlock(&context->lifecycleMutex);

    void* data = dataSize > 0 ? malloc(dataSize) : NULL;
    bool success = dataSize == 0 || (data && readExactBeforeDeadline(context, data, dataSize));

    pthread_mutex_lock(&context->lifecycleMutex);
    if (!success || VkContext_isClosing(context)) {
        if (context->pendingExtraDataBytes >= dataSize) context->pendingExtraDataBytes -= dataSize;
        else context->pendingExtraDataBytes = 0;
        if (context->pendingExtraDataCount > 0) context->pendingExtraDataCount--;
        memset(slot, 0, sizeof(*slot));
        pthread_cond_broadcast(&context->lifecycleCond);
        pthread_mutex_unlock(&context->lifecycleMutex);
        free(data);
        return false;
    }
    slot->data = data;
    slot->state = EXTRA_DATA_READY;
    pthread_cond_broadcast(&context->lifecycleCond);
    pthread_mutex_unlock(&context->lifecycleMutex);
    return true;
}
