#ifndef VORTEK_CONTEXT_H
#define VORTEK_CONTEXT_H

#include "vortek.h"
#include "xwindow_swapchain.h"
#include "texture_decoder.h"
#include "async_pipeline_creator.h"
#include "timeline_semaphore.h"

typedef struct VkContext {
    int clientFd;
    int vkMaxVersion;
    short maxDeviceMemory;
    short imageCacheSize;
    ResourceMemoryType resourceMemoryType;
    ArrayList* exposedDeviceExtensions;
    ArrayList* disabledDeviceExtensions;
    uint64_t totalAllocationSize;
    uint32_t memoryDiagnosticCount;

    bool hasExternalMemoryFd;
    bool hasExternalMemoryDMABuf;

#if ENABLE_VALIDATION_LAYER
    VkDebugReportCallbackEXT debugReportCallback;
#endif

    char* inputBuffer;
    int inputBufferSize;
    MemoryPool memoryPool;

    pthread_t requestHandlerThread;
    bool requestHandlerThreadStarted;
    RingBuffer* clientRing;
    RingBuffer* serverRing;
    _Atomic int status;

    int graphicsQueueIndex;

    TextureDecoder* textureDecoder;
    ShaderInspector* shaderInspector;
    ThreadPool* threadPool;

    ArrayList extraDataRequests;
    pthread_mutex_t extraDataRequestsMutex;
    bool extraDataRequestsMutexInitialized;

    JMethods jmethods;
    char* engineName;
    uint32_t engineVersion;
    VkDriverId driverID;
} VkContext;

#define VORTEK_MEMORY_DIAGNOSTIC_LIMIT 64

static inline bool vortekReserveMemoryDiagnostic(VkContext* context) {
    if (!context || context->memoryDiagnosticCount >= VORTEK_MEMORY_DIAGNOSTIC_LIMIT) {
        return false;
    }
    context->memoryDiagnosticCount++;
    return true;
}

typedef struct ExtraDataRequest {
    uint16_t requestId;
    void* data;
    int size;
} ExtraDataRequest;

extern VkContext* createVkContext(JNIEnv *env, jobject obj, int clientFd, jobject options);
extern void destroyVkContext(JNIEnv *env, VkContext* context);
extern bool handleExtraDataRequest(VkContext* context, uint16_t requestId, int requestLength);

#endif
