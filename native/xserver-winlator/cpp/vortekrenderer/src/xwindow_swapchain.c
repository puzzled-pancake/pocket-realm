#include "xwindow_swapchain.h"
#include "xwindow_swapchain_transaction.h"
#include "vulkan_helper.h"

#include <limits.h>
#include <stdint.h>

#define VORTEK_XWINDOW_SWAPCHAIN_MAX_IMAGES 2u

static bool clearJniException(JMethods* jmethods, const char* operation) {
    if (!jmethods || !jmethods->env) return false;
    if (!(*jmethods->env)->ExceptionCheck(jmethods->env)) return false;
    (*jmethods->env)->ExceptionClear(jmethods->env);
    println("Vortek window JNI operation failed: %s", operation);
    return true;
}

bool getWindowExtent(
        JMethods* jmethods, uint64_t contextGeneration,
        uint64_t instanceToken, int windowId, VkExtent2D* extent) {
    if (!jmethods || !jmethods->env || !jmethods->obj ||
            !jmethods->getWindowExtentAuthority || !extent ||
            contextGeneration == 0 || instanceToken == 0 || windowId <= 0) {
        return false;
    }
    const jlong packed = (*jmethods->env)->CallLongMethod(
            jmethods->env, jmethods->obj,
            jmethods->getWindowExtentAuthority,
            (jlong)contextGeneration, (jlong)instanceToken, (jint)windowId);
    if (clearJniException(jmethods, "extent") || packed == 0) return false;
    const uint64_t bits = (uint64_t)packed;
    const uint32_t width = (uint32_t)(bits >> 32u);
    const uint32_t height = (uint32_t)bits;
    if (width == 0 || height == 0) return false;
    extent->width = width;
    extent->height = height;
    return true;
}

static AHardwareBuffer* getWindowHardwareBuffer(
        JMethods* jmethods, uint64_t contextGeneration,
        uint64_t instanceToken, int windowId,
        jboolean useHALPixelFormatBGRA8888) {
    if (!jmethods || !jmethods->env || !jmethods->obj ||
            !jmethods->getWindowHardwareBufferAuthority) return NULL;
    const jlong pointer = (*jmethods->env)->CallLongMethod(
            jmethods->env, jmethods->obj,
            jmethods->getWindowHardwareBufferAuthority,
            (jlong)contextGeneration, (jlong)instanceToken, (jint)windowId,
            useHALPixelFormatBGRA8888);
    if (clearJniException(jmethods, "hardware-buffer") || pointer == 0)
        return NULL;
    /* Java acquired one AHardwareBuffer reference while holding the window
     * and Drawable locks.  Every return path below must release it. */
    return (AHardwareBuffer*)(uintptr_t)pointer;
}

static bool findMemoryTypeIndex(
        uint32_t typeBits, VkMemoryPropertyFlags required,
        uint32_t* indexOut) {
    if (!indexOut) return false;
    for (uint32_t index = 0; index < 32u; ++index) {
        if ((typeBits & (UINT32_C(1) << index)) == 0) continue;
        const VkMemoryPropertyFlags available = getMemoryPropertyFlags(index);
        if ((available & required) == required) {
            *indexOut = index;
            return true;
        }
    }
    return false;
}

typedef struct XWindowImageBuildContext {
    VkDevice device;
    XWindowSwapchain* swapchain;
    AHardwareBuffer* hardwareBuffer;
    AHardwareBuffer_Desc hardwareBufferDescription;
} XWindowImageBuildContext;

static int32_t beginImage(void* userdata, uint32_t index) {
    (void)index;
    XWindowImageBuildContext* context = userdata;
    if (!context || !context->swapchain || context->hardwareBuffer)
        return (int32_t)VK_ERROR_INITIALIZATION_FAILED;

    XWindowSwapchain* swapchain = context->swapchain;
    const jboolean bgra = swapchain->imageFormat == VK_FORMAT_B8G8R8A8_UNORM ||
            swapchain->imageFormat == VK_FORMAT_B8G8R8A8_SRGB;
    context->hardwareBuffer = getWindowHardwareBuffer(
            swapchain->jmethods, swapchain->contextGeneration,
            swapchain->instanceToken, swapchain->windowId, bgra);
    if (!context->hardwareBuffer) return (int32_t)VK_ERROR_SURFACE_LOST_KHR;

    memset(&context->hardwareBufferDescription, 0,
            sizeof(context->hardwareBufferDescription));
    AHardwareBuffer_describe(
            context->hardwareBuffer, &context->hardwareBufferDescription);
    if (context->hardwareBufferDescription.width == 0 ||
            context->hardwareBufferDescription.height == 0 ||
            context->hardwareBufferDescription.width !=
                    swapchain->imageExtent.width ||
            context->hardwareBufferDescription.height !=
                    swapchain->imageExtent.height) {
        return (int32_t)VK_ERROR_SURFACE_LOST_KHR;
    }
    return (int32_t)VK_SUCCESS;
}

static void endImage(void* userdata, uint32_t index) {
    (void)index;
    XWindowImageBuildContext* context = userdata;
    if (!context) return;
    if (context->hardwareBuffer) {
        AHardwareBuffer_release(context->hardwareBuffer);
        context->hardwareBuffer = NULL;
    }
    memset(&context->hardwareBufferDescription, 0,
            sizeof(context->hardwareBufferDescription));
}

static int32_t createTransactionImage(
        void* userdata, uint32_t index, uint64_t* imageOut) {
    (void)index;
    XWindowImageBuildContext* context = userdata;
    if (!context || !context->swapchain || !context->hardwareBuffer ||
            !imageOut) {
        return (int32_t)VK_ERROR_INITIALIZATION_FAILED;
    }
    *imageOut = 0;
    XWindowSwapchain* swapchain = context->swapchain;

    VkExternalFormatANDROID externalFormatAndroid = {0};
    externalFormatAndroid.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;

    VkExternalMemoryImageCreateInfo externalMemoryImageInfo = {0};
    externalMemoryImageInfo.sType =
            VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalMemoryImageInfo.pNext = &externalFormatAndroid;
    externalMemoryImageInfo.handleTypes =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo imageInfo = {0};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalMemoryImageInfo;
    imageInfo.flags = VK_IMAGE_CREATE_ALIAS_BIT;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = swapchain->imageFormat;
    imageInfo.extent.width = context->hardwareBufferDescription.width;
    imageInfo.extent.height = context->hardwareBufferDescription.height;
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = swapchain->imageUsage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkImage image = VK_NULL_HANDLE;
    const VkResult result = vulkanWrapper.vkCreateImage(
            context->device, &imageInfo, NULL, &image);
    if (result == VK_SUCCESS) *imageOut = (uint64_t)(uintptr_t)image;
    return (int32_t)result;
}

static int32_t allocateTransactionMemory(
        void* userdata, uint32_t index, uint64_t imageBits,
        uint64_t* memoryOut) {
    (void)index;
    XWindowImageBuildContext* context = userdata;
    if (!context || !context->device || !context->hardwareBuffer ||
            imageBits == 0 || !memoryOut) {
        return (int32_t)VK_ERROR_INITIALIZATION_FAILED;
    }
    *memoryOut = 0;
    const VkImage image = (VkImage)(uintptr_t)imageBits;

    VkAndroidHardwareBufferPropertiesANDROID ahbProperties = {0};
    ahbProperties.sType =
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    VkResult result = vulkanWrapper.vkGetAndroidHardwareBufferPropertiesANDROID(
            context->device, context->hardwareBuffer, &ahbProperties);
    if (result != VK_SUCCESS) return (int32_t)result;
    if (ahbProperties.allocationSize == 0 || ahbProperties.memoryTypeBits == 0)
        return (int32_t)VK_ERROR_INITIALIZATION_FAILED;

    uint32_t memoryTypeIndex = 0;
    if (!findMemoryTypeIndex(ahbProperties.memoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &memoryTypeIndex)) {
        return (int32_t)VK_ERROR_FEATURE_NOT_PRESENT;
    }

    VkImportAndroidHardwareBufferInfoANDROID memoryImportInfo = {0};
    memoryImportInfo.sType =
            VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    memoryImportInfo.buffer = context->hardwareBuffer;

    VkMemoryDedicatedAllocateInfo memoryDedicatedInfo = {0};
    memoryDedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    memoryDedicatedInfo.pNext = &memoryImportInfo;
    memoryDedicatedInfo.image = image;

    VkMemoryAllocateInfo memoryInfo = {0};
    memoryInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    memoryInfo.pNext = &memoryDedicatedInfo;
    memoryInfo.allocationSize = ahbProperties.allocationSize;
    memoryInfo.memoryTypeIndex = memoryTypeIndex;

    VkDeviceMemory memory = VK_NULL_HANDLE;
    result = vulkanWrapper.vkAllocateMemory(
            context->device, &memoryInfo, NULL, &memory);
    if (result == VK_SUCCESS) *memoryOut = (uint64_t)(uintptr_t)memory;
    return (int32_t)result;
}

static int32_t bindTransactionMemory(
        void* userdata, uint32_t index, uint64_t imageBits,
        uint64_t memoryBits) {
    (void)index;
    XWindowImageBuildContext* context = userdata;
    if (!context || !context->device || imageBits == 0 || memoryBits == 0)
        return (int32_t)VK_ERROR_INITIALIZATION_FAILED;
    return (int32_t)vulkanWrapper.vkBindImageMemory(
            context->device, (VkImage)(uintptr_t)imageBits,
            (VkDeviceMemory)(uintptr_t)memoryBits, 0);
}

static void destroyTransactionImage(void* userdata, uint64_t imageBits) {
    XWindowImageBuildContext* context = userdata;
    if (!context || !context->device || imageBits == 0) return;
    vulkanWrapper.vkDestroyImage(
            context->device, (VkImage)(uintptr_t)imageBits, NULL);
}

static void freeTransactionMemory(void* userdata, uint64_t memoryBits) {
    XWindowImageBuildContext* context = userdata;
    if (!context || !context->device || memoryBits == 0) return;
    vulkanWrapper.vkFreeMemory(
            context->device, (VkDeviceMemory)(uintptr_t)memoryBits, NULL);
}

static const VtXWindowTransactionOps imageTransactionOps = {
    .beginImage = beginImage,
    .createImage = createTransactionImage,
    .allocateMemory = allocateTransactionMemory,
    .bindImageMemory = bindTransactionMemory,
    .endImage = endImage,
    .destroyImage = destroyTransactionImage,
    .freeMemory = freeTransactionMemory,
    .invalidResult = (int32_t)VK_ERROR_INITIALIZATION_FAILED,
};

static void* allocateZeroed(
        void* userdata, size_t count, size_t size) {
    (void)userdata;
    return calloc(count, size);
}

static void freeAllocation(void* userdata, void* allocation) {
    (void)userdata;
    free(allocation);
}

static const VtXWindowHeapOps heapTransactionOps = {
    .allocateZeroed = allocateZeroed,
    .freeAllocation = freeAllocation,
    .outOfMemoryResult = (int32_t)VK_ERROR_OUT_OF_HOST_MEMORY,
    .invalidResult = (int32_t)VK_ERROR_INITIALIZATION_FAILED,
};

static void rollbackSwapchainImages(
        VkDevice device, XWindowSwapchain* swapchain) {
    if (!device || !swapchain || !swapchain->images) return;
    XWindowImageBuildContext context = {
        .device = device,
        .swapchain = swapchain,
    };
    VtXWindowTransactionImage images[VORTEK_XWINDOW_SWAPCHAIN_MAX_IMAGES] =
            {{0}};
    const uint32_t count = swapchain->imageCount <=
            VORTEK_XWINDOW_SWAPCHAIN_MAX_IMAGES
            ? swapchain->imageCount : VORTEK_XWINDOW_SWAPCHAIN_MAX_IMAGES;
    for (uint32_t index = 0; index < count; ++index) {
        images[index].image =
                (uint64_t)(uintptr_t)swapchain->images[index].image;
        images[index].memory =
                (uint64_t)(uintptr_t)swapchain->images[index].memory;
        swapchain->images[index].image = VK_NULL_HANDLE;
        swapchain->images[index].memory = VK_NULL_HANDLE;
    }
    vt_xwindow_transaction_rollback(
            &imageTransactionOps, &context, images, count);
}

int getSurfaceMinImageCount(void) {
    return 1;
}

VkSurfaceFormatKHR* getSurfaceFormats(uint32_t* formatCount) {
    static VkSurfaceFormatKHR surfaceFormats[] = {
        {VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
        {VK_FORMAT_B8G8R8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
        {VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
        {VK_FORMAT_R8G8B8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
    };
    if (formatCount) *formatCount = ARRAY_SIZE(surfaceFormats);
    return surfaceFormats;
}

VkResult XWindowSwapchain_create(
        VkDevice device, uint32_t graphicsQueueIndex,
        const VkSwapchainCreateInfoKHR* swapchainInfo, JMethods* jmethods,
        uint64_t contextGeneration, uint64_t instanceToken, int windowId,
        XWindowSwapchain** swapchainOut) {
    if (!swapchainOut) return VK_ERROR_INITIALIZATION_FAILED;
    *swapchainOut = NULL;
    if (!device || !swapchainInfo || !jmethods || contextGeneration == 0 ||
            instanceToken == 0 || windowId <= 0 ||
            swapchainInfo->minImageCount == 0 ||
            swapchainInfo->minImageCount > VORTEK_XWINDOW_SWAPCHAIN_MAX_IMAGES ||
            swapchainInfo->imageExtent.width == 0 ||
            swapchainInfo->imageExtent.height == 0) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    XWindowSwapchain* swapchain = NULL;
    XWindowSwapchain_Image* images = NULL;
    VkResult result = (VkResult)vt_xwindow_transaction_allocate_heap(
            &heapTransactionOps, NULL, sizeof(*swapchain),
            swapchainInfo->minImageCount, sizeof(*images),
            (void**)&swapchain, (void**)&images);
    if (result != VK_SUCCESS) return result;
    swapchain->images = images;
    swapchain->contextGeneration = contextGeneration;
    swapchain->instanceToken = instanceToken;
    swapchain->windowId = windowId;
    swapchain->imageCount = swapchainInfo->minImageCount;
    swapchain->imageFormat = swapchainInfo->imageFormat;
    swapchain->imageUsage = swapchainInfo->imageUsage;
    swapchain->imageExtent = swapchainInfo->imageExtent;
    swapchain->jmethods = jmethods;

    XWindowImageBuildContext buildContext = {
        .device = device,
        .swapchain = swapchain,
    };
    VtXWindowTransactionImage builtImages[
            VORTEK_XWINDOW_SWAPCHAIN_MAX_IMAGES] = {{0}};
    result = (VkResult)vt_xwindow_transaction_build(
            &imageTransactionOps, &buildContext, swapchain->imageCount,
            builtImages);
    if (result != VK_SUCCESS) {
        free(swapchain->images);
        free(swapchain);
        return result;
    }
    for (uint32_t index = 0; index < swapchain->imageCount; ++index) {
        swapchain->images[index].image =
                (VkImage)(uintptr_t)builtImages[index].image;
        swapchain->images[index].memory =
                (VkDeviceMemory)(uintptr_t)builtImages[index].memory;
    }

    vulkanWrapper.vkGetDeviceQueue(
            device, graphicsQueueIndex, 0, &swapchain->queue);
    if (!swapchain->queue) {
        XWindowSwapchain_destroy(device, swapchain);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    *swapchainOut = swapchain;
    return VK_SUCCESS;
}

void XWindowSwapchain_destroy(VkDevice device, XWindowSwapchain* swapchain) {
    if (!swapchain) return;
    rollbackSwapchainImages(device, swapchain);
    free(swapchain->images);
    free(swapchain);
}

VkResult XWindowSwapchain_acquireNextImage(
        XWindowSwapchain* swapchain, uint64_t timeout,
        VkSemaphore signalSemaphore, VkFence fence, uint32_t* imageIndex) {
    (void)timeout;
    if (!swapchain || !imageIndex) return VK_ERROR_INITIALIZATION_FAILED;

    /* Revalidate before producing any semaphore/fence side effect. */
    VkExtent2D windowSize = {0};
    if (!getWindowExtent(swapchain->jmethods, swapchain->contextGeneration,
            swapchain->instanceToken, swapchain->windowId, &windowSize) ||
            swapchain->imageExtent.width != windowSize.width ||
            swapchain->imageExtent.height != windowSize.height) {
        return VK_ERROR_SURFACE_LOST_KHR;
    }

    if (signalSemaphore || fence) {
        VkSubmitInfo submitInfo = {0};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        if (signalSemaphore) {
            submitInfo.pSignalSemaphores = &signalSemaphore;
            submitInfo.signalSemaphoreCount = 1;
        }
        const VkResult result = vulkanWrapper.vkQueueSubmit(
                swapchain->queue, 1, &submitInfo, fence);
        if (result != VK_SUCCESS) return result;
    }

    *imageIndex = 0;
    return VK_SUCCESS;
}

bool XWindowSwapchain_presentImage(XWindowSwapchain* swapchain) {
    if (!swapchain || !swapchain->jmethods || !swapchain->jmethods->env ||
            !swapchain->jmethods->obj ||
            !swapchain->jmethods->updateWindowContentAuthority) return false;
    const jboolean updated = (*swapchain->jmethods->env)->CallBooleanMethod(
            swapchain->jmethods->env, swapchain->jmethods->obj,
            swapchain->jmethods->updateWindowContentAuthority,
            (jlong)swapchain->contextGeneration,
            (jlong)swapchain->instanceToken, (jint)swapchain->windowId);
    return !clearJniException(swapchain->jmethods, "present") &&
            updated == JNI_TRUE;
}
