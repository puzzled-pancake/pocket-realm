#include "vk_context.h"
#include "vulkan_helper.h"
#include "request_handler.h"
#include "sysvshared_memory.h"
#include "string_utils.h"
#include "jni_utils.h"

#define VORTEK_EXTRA_DATA_MAX_FRAME_SIZE (64 * 1024 * 1024)

static bool loadJMethods(JMethods* jmethods) {
    JNIEnv* env;
    if (!jmethods || !jmethods->jvm ||
            (*jmethods->jvm)->AttachCurrentThread(jmethods->jvm, &env, NULL) != JNI_OK || !env) {
        return false;
    }
    jmethods->env = env;

    jclass cls = (*env)->GetObjectClass(env, jmethods->obj);
    if (!cls || (*env)->ExceptionCheck(env)) return false;
    jmethods->getWindowWidth = (*env)->GetMethodID(env, cls, "getWindowWidth", "(I)I");
    jmethods->getWindowHeight = (*env)->GetMethodID(env, cls, "getWindowHeight", "(I)I");
    jmethods->getWindowHardwareBuffer = (*env)->GetMethodID(env, cls, "getWindowHardwareBuffer", "(IZ)J");
    jmethods->updateWindowContent = (*env)->GetMethodID(env, cls, "updateWindowContent", "(I)V");
    bool result = jmethods->getWindowWidth && jmethods->getWindowHeight &&
            jmethods->getWindowHardwareBuffer && jmethods->updateWindowContent &&
            !(*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, cls);
    return result;
}

static ExtraDataRequest* waitForExtraDataRequest(VkContext* context, uint16_t requestId) {
    ExtraDataRequest* result = NULL;
    uint32_t busyWaitIter = 0;

    while (context->status >= 0) {
        result = NULL;
        pthread_mutex_lock(&context->extraDataRequestsMutex);
        for (int i = 0; i < context->extraDataRequests.size; i++) {
            ExtraDataRequest* extraDataRequest = context->extraDataRequests.elements[i];
            if (extraDataRequest->requestId == requestId) {
                result = extraDataRequest;
                ArrayList_removeAt(&context->extraDataRequests, i);
                break;
            }
        }
        pthread_mutex_unlock(&context->extraDataRequestsMutex);

        if (result) break;
        busyWait(&busyWaitIter);
    }

    return context->status >= 0 ? result : NULL;
}

static void* requestHandlerThread(void* param) {
    VkContext* context = param;
    if (!loadJMethods(&context->jmethods)) {
        context->status = VK_ERROR_INITIALIZATION_FAILED;
        return NULL;
    }

    while (context->status >= 0) {
        ExtraDataRequest* extraDataRequest = NULL;
        int requestCode = vt_recv(context->serverRing, &context->inputBuffer, &context->inputBufferSize, &context->memoryPool);
        if (requestCode < 0) {
            context->status = VK_ERROR_DEVICE_LOST;
            break;
        }

        if (requestCode > INT16_MAX) {
            uint16_t requestId = requestCode & 0xffff;
            requestCode = requestCode >> 16;
            extraDataRequest = waitForExtraDataRequest(context, requestId);
            if (!extraDataRequest) break;
            context->inputBufferSize = extraDataRequest->size;
            context->inputBuffer = extraDataRequest->data;
        }

#if DEBUG_MODE
        println("handleRequest name=%s size=%d", requestCodeToString(requestCode), context->inputBufferSize);
#endif

        HandleRequestFunc handleRequestFunc = getHandleRequestFunc(requestCode);
        if (handleRequestFunc) handleRequestFunc(context);

        vt_free(&context->memoryPool);

        if (extraDataRequest) {
            MEMFREE(extraDataRequest->data);
            MEMFREE(extraDataRequest);
            extraDataRequest = NULL;
        }

        context->inputBuffer = NULL;
        context->inputBufferSize = 0;
    }

    (*context->jmethods.jvm)->DetachCurrentThread(context->jmethods.jvm);
    vt_free(&context->memoryPool);
    return NULL;
}

static bool setupRingBuffers(VkContext* context) {
    int shmFds[2] = {-1, -1};
    shmFds[0] = ashmemCreateRegion("vt-server-ring", RingBuffer_getSHMemSize(SERVER_RING_BUFFER_SIZE));
    if (shmFds[0] < 0) goto error;
    shmFds[1] = ashmemCreateRegion("vt-client-ring", RingBuffer_getSHMemSize(CLIENT_RING_BUFFER_SIZE));
    if (shmFds[1] < 0) goto error;

    context->serverRing = RingBuffer_create(shmFds[0], SERVER_RING_BUFFER_SIZE);
    if (!context->serverRing) goto error;

    context->clientRing = RingBuffer_create(shmFds[1], CLIENT_RING_BUFFER_SIZE);
    if (!context->clientRing) goto error;
    RingBuffer_setPeerFd(context->serverRing, context->clientFd);
    RingBuffer_setPeerFd(context->clientRing, context->clientFd);

    int result = send_fds(context->clientFd, shmFds, 2, NULL, 0);
    close(shmFds[0]);
    shmFds[0] = -1;
    close(shmFds[1]);
    shmFds[1] = -1;

    if (result < 0) goto error;

    if (pthread_create(&context->requestHandlerThread, NULL, requestHandlerThread, context) != 0) goto error;
    context->requestHandlerThreadStarted = true;
    return true;

error:
    if (shmFds[0] >= 0) close(shmFds[0]);
    if (shmFds[1] >= 0) close(shmFds[1]);
    return false;
}

VkContext* createVkContext(JNIEnv* env, jobject obj, int clientFd, jobject options) {
    if (!env || !obj || !options || clientFd < 0) return NULL;
    jobjectArray exposedDeviceExtensions = getJFieldByName(env, options, "exposedDeviceExtensions", JSIGNATURE_ARRAY_STRING).l;

    VkContext* context = calloc(1, sizeof(VkContext));
    if (!context) return NULL;
    context->clientFd = clientFd;
    context->vkMaxVersion = getJFieldByName(env, options, "vkMaxVersion", "I").i;
    context->maxDeviceMemory = getJFieldByName(env, options, "maxDeviceMemory", "S").s;
    context->imageCacheSize = getJFieldByName(env, options, "imageCacheSize", "S").s;
    context->resourceMemoryType = getJFieldByName(env, options, "resourceMemoryType", "B").b;
    context->exposedDeviceExtensions = jstringArrayToCharArray(env, exposedDeviceExtensions);

    if (pthread_mutex_init(&context->extraDataRequestsMutex, NULL) != 0) goto error;
    context->extraDataRequestsMutexInitialized = true;

    context->memoryPool.data = calloc(MEMORY_POOL_MAX_SIZE, 1);
    if (!context->memoryPool.data) goto error;
    context->threadPool = ThreadPool_init(THREAD_POOL_NUM_THREADS);
    if (!context->threadPool) goto error;

    if ((*env)->GetJavaVM(env, &context->jmethods.jvm) != JNI_OK || !context->jmethods.jvm) goto error;
    context->jmethods.obj = (*env)->NewGlobalRef(env, obj);
    if (!context->jmethods.obj) goto error;

    if (!setupRingBuffers(context)) {
        goto error;
    }

    return context;

error:
    destroyVkContext(env, context);
    return NULL;
}

void destroyVkContext(JNIEnv* env, VkContext* context) {
    if (!context) return;
    context->status = VK_ERROR_DEVICE_LOST;

    if (context->serverRing) RingBuffer_setStatus(context->serverRing, RING_STATUS_EXIT);
    if (context->clientRing) RingBuffer_setStatus(context->clientRing, RING_STATUS_EXIT);
    if (context->requestHandlerThreadStarted &&
            !pthread_equal(pthread_self(), context->requestHandlerThread)) {
        pthread_join(context->requestHandlerThread, NULL);
        context->requestHandlerThreadStarted = false;
    }
    ThreadPool_destroy(context->threadPool);
    context->threadPool = NULL;
    RingBuffer_free(context->serverRing);
    context->serverRing = NULL;
    RingBuffer_free(context->clientRing);
    context->clientRing = NULL;

    if (context->jmethods.obj && env) {
        (*env)->DeleteGlobalRef(env, context->jmethods.obj);
        context->jmethods.obj = NULL;
    }

    context->graphicsQueueIndex = 0;

    if (context->textureDecoder) {
        TextureDecoder_destroy(context->textureDecoder);
        context->textureDecoder = NULL;
    }

    ArrayList_free(context->exposedDeviceExtensions, true);
    context->exposedDeviceExtensions = NULL;

    ArrayList_free(context->disabledDeviceExtensions, true);
    context->disabledDeviceExtensions = NULL;

    for (int i = 0; i < context->extraDataRequests.size; ++i) {
        ExtraDataRequest* request = context->extraDataRequests.elements[i];
        if (request) free(request->data);
    }
    ArrayList_free(&context->extraDataRequests, true);
    if (context->extraDataRequestsMutexInitialized) {
        pthread_mutex_destroy(&context->extraDataRequestsMutex);
    }

    MEMFREE(context->memoryPool.data);
    MEMFREE(context->engineName);
    free(context);
}

bool handleExtraDataRequest(VkContext* context, uint16_t requestId, int requestLength) {
    if (!context || requestLength < 0 ||
            requestLength > VORTEK_EXTRA_DATA_MAX_FRAME_SIZE || context->status < 0) return false;
    void* data = NULL;
    if (requestLength > 0) {
        data = calloc(requestLength, 1);
        if (!data) return false;
        int total = 0;
        while (total < requestLength) {
            int bytesRead = sock_read(context->clientFd,
                    (char*)data + total, requestLength - total);
            if (bytesRead <= 0) {
                free(data);
                return false;
            }
            total += bytesRead;
        }
    }

    ExtraDataRequest* extraDataRequest = calloc(1, sizeof(ExtraDataRequest));
    if (!extraDataRequest) {
        free(data);
        return false;
    }
    extraDataRequest->requestId = requestId;
    extraDataRequest->size = requestLength;
    extraDataRequest->data = data;

    pthread_mutex_lock(&context->extraDataRequestsMutex);
    ArrayList_add(&context->extraDataRequests, extraDataRequest);
    pthread_mutex_unlock(&context->extraDataRequestsMutex);
    return true;
}
