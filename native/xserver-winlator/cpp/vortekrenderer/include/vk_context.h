#ifndef VORTEK_CONTEXT_H
#define VORTEK_CONTEXT_H

#include "vortek.h"
#include "xwindow_swapchain.h"
#include "texture_decoder.h"
#include "async_pipeline_creator.h"
#include "timeline_semaphore.h"
#include "vk_object.h"
#include <stdatomic.h>

#define VORTEK_EXTRA_DATA_MAX_FRAME_SIZE (64u * 1024u * 1024u)
#define VORTEK_EXTRA_DATA_MAX_AGGREGATE_SIZE (128u * 1024u * 1024u)
#define VORTEK_EXTRA_DATA_MAX_PENDING 32u
#define VORTEK_EXTRA_DATA_WAIT_TIMEOUT_MS 5000u
#define VORTEK_HANDLE_AUTHORITY_CAPACITY (1u << 18)

typedef enum ExtraDataState {
    EXTRA_DATA_EMPTY = 0,
    EXTRA_DATA_READING,
    EXTRA_DATA_READY,
} ExtraDataState;

typedef struct ExtraDataRequest {
    uint16_t requestId;
    void* data;
    size_t size;
    ExtraDataState state;
} ExtraDataRequest;

struct VkContext {
    int clientFd;
    int vkMaxVersion;
    short maxDeviceMemory;
    short imageCacheSize;
    ResourceMemoryType resourceMemoryType;
    bool hardenedSafeLane;
    /* Pending only while vkCreateDevice is being validated/published.  The
     * authoritative value is stored per live device registry token. */
    bool nullDescriptorEnabled;
    uint64_t contextGeneration;
    VkObjectAuthority* handleAuthority;
    ArrayList* exposedDeviceExtensions;
    ArrayList* disabledDeviceExtensions;
    uint64_t totalAllocationSize;

    bool hasExternalMemoryFd;
    bool hasExternalMemoryDMABuf;

#if ENABLE_VALIDATION_LAYER
    VkDebugReportCallbackEXT debugReportCallback;
#endif

    char* inputBuffer;
    int inputBufferSize;
    MemoryPool memoryPool;

    pthread_t requestHandlerThread;
    RingBuffer* clientRing;
    RingBuffer* serverRing;
    atomic_int status;
    atomic_bool closing;

    int graphicsQueueIndex;

    TextureDecoder* textureDecoder;
    ShaderInspector* shaderInspector;
    ThreadPool* threadPool;

    pthread_mutex_t lifecycleMutex;
    /* Serializes ShaderInspector's lazy module materialization across async
     * pipeline jobs.  Device authority leases protect referenced wrappers. */
    pthread_mutex_t pipelineMutex;
    pthread_cond_t lifecycleCond;
    bool lifecycleMutexInitialized;
    bool pipelineMutexInitialized;
    bool lifecycleCondInitialized;
    bool requestHandlerThreadStarted;
    bool windowAuthorityGenerationRegistered;
    ExtraDataRequest extraDataRequests[VORTEK_EXTRA_DATA_MAX_PENDING];
    size_t pendingExtraDataBytes;
    uint32_t pendingExtraDataCount;
    /* A request id is one-shot for a context.  This rejects stale/late frames
     * safely instead of confusing a wrapped 16-bit id with a new owner. */
    uint8_t seenExtraDataRequestIds[UINT16_MAX / 8u + 1u];

    JMethods jmethods;
    char* engineName;
    uint32_t engineVersion;
    VkDriverId driverID;
};

extern VkContext* createVkContext(JNIEnv *env, jobject obj, int clientFd, jobject options);
extern void destroyVkContext(JNIEnv *env, VkContext* context);
extern bool handleExtraDataRequest(VkContext* context, uint16_t requestId, int requestLength);
extern bool VkContext_isClosing(VkContext* context);
extern void VkContext_requestStop(VkContext* context, VkResult status);
extern bool VkContext_acquireDeviceLease(
        VkContext* context, uint64_t deviceToken,
        VortekDeviceLease* leaseOut);
extern bool VkContext_releaseDeviceLease(VortekDeviceLease* lease);
extern bool VkContext_beginDeviceRetirement(
        VkContext* context, uint64_t deviceToken);
extern bool VkContext_beginInstanceRetirement(
        VkContext* context, uint64_t instanceToken);
extern bool VkContext_releaseWindowInstanceAuthority(
        VkContext* context, uint64_t instanceToken);
/* Reclaims and tombstones every entry in the selected authority scope in
 * dependency-safe order.  DEVICE/INSTANCE include their root token. */
extern bool VkContext_reclaimAuthority(
        VkContext* context, VortekHandleDrainScope scope,
        uint64_t scopeToken);

#endif
