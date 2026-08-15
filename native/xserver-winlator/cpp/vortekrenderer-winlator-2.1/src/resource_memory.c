#include "resource_memory.h"
#include <errno.h>
#include <sys/stat.h>

#include "sysvshared_memory.h"
#include "vulkan_helper.h"
#include "dma_utils.h"

extern int AHardwareBuffer_getFd(AHardwareBuffer* hardwareBuffer);
extern DeviceMemoryInfo deviceMemoryInfo;

static ResourceMemory* internalAllocate() {
    ResourceMemory* resourceMemory = calloc(1, sizeof(ResourceMemory));
    resourceMemory->buffer = VK_NULL_HANDLE;
    resourceMemory->memory = VK_NULL_HANDLE;
    resourceMemory->fd = -1;
    resourceMemory->transportType = RESOURCE_MEMORY_TYPE_AUTO;
    return resourceMemory;
}

static const char* resourceMemoryTypeName(ResourceMemoryType type) {
    switch (type) {
        case RESOURCE_MEMORY_TYPE_OPAQUE_FD: return "opaque-fd";
        case RESOURCE_MEMORY_TYPE_DMA_BUF: return "dma-buf";
        case RESOURCE_MEMORY_TYPE_AHARDWAREBUFFER: return "ahardwarebuffer";
        default: return "device-local";
    }
}

static void logResourceMemoryAllocation(
        VkContext* context,
        ResourceMemory* resourceMemory,
        uint32_t requestedType,
        uint32_t selectedType,
        VkMemoryPropertyFlags requestedFlags,
        VkMemoryPropertyFlags selectedFlags,
        VkDeviceSize requestedSize,
        VkResult result) {
    if (!vortekReserveMemoryDiagnostic(context)) return;

    struct stat fdStat = {0};
    errno = 0;
    int statResult = resourceMemory->fd >= 0 ? fstat(resourceMemory->fd, &fdStat) : -1;
    int statErrno = statResult == 0 ? 0 : errno;
    println(
        "vortek-memory: event=allocate route=%s requestedType=%u selectedType=%u "
        "requestedFlags=0x%x selectedFlags=0x%x requestedSize=%llu allocationSize=%llu "
        "result=%d fd=%d fstat=%d fstatErrno=%d fdSize=%lld",
        resourceMemoryTypeName(resourceMemory->transportType),
        requestedType,
        selectedType,
        requestedFlags,
        selectedFlags,
        (unsigned long long)requestedSize,
        (unsigned long long)resourceMemory->allocationSize,
        result,
        resourceMemory->fd,
        statResult,
        statErrno,
        statResult == 0 ? (long long)fdStat.st_size : -1LL);
}

static AHardwareBuffer* allocateHardwareBuffer(int size) {
    AHardwareBuffer_Desc buffDesc = {0};
    buffDesc.width = size;
    buffDesc.height = 1;
    buffDesc.layers = 1;
    buffDesc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
    buffDesc.format = AHARDWAREBUFFER_FORMAT_BLOB;

    AHardwareBuffer* hardwareBuffer = NULL;
    AHardwareBuffer_allocate(&buffDesc, &hardwareBuffer);

    return hardwareBuffer;
}

ResourceMemory* ResourceMemory_allocate(VkContext* context, VkDevice device, VkMemoryAllocateInfo* memoryInfo) {
    const uint32_t requestedMemoryType = memoryInfo->memoryTypeIndex;
    const VkMemoryPropertyFlags requestedFlags = getMemoryPropertyFlags(requestedMemoryType);
    const VkDeviceSize requestedSize = memoryInfo->allocationSize;
    uint64_t maxAllocationSize = (VkDeviceSize)context->maxDeviceMemory << 20;
    if (maxAllocationSize == 0) maxAllocationSize = deviceMemoryInfo.maxAllocationSize;
    if ((context->totalAllocationSize + memoryInfo->allocationSize) >= maxAllocationSize) return NULL;

    VkResult result = VK_ERROR_INITIALIZATION_FAILED;
    ResourceMemory* resourceMemory = internalAllocate();
    if (isHostVisibleMemory(memoryInfo->memoryTypeIndex)) {
        bool hasExternalMemoryFd = context->hasExternalMemoryFd;
        bool hasExternalMemoryDMABuf = context->hasExternalMemoryDMABuf;

        switch (context->resourceMemoryType) {
            case RESOURCE_MEMORY_TYPE_OPAQUE_FD:
                hasExternalMemoryFd = true;
                hasExternalMemoryDMABuf = false;
                break;
            case RESOURCE_MEMORY_TYPE_DMA_BUF:
                hasExternalMemoryFd = false;
                hasExternalMemoryDMABuf = true;
                break;
            case RESOURCE_MEMORY_TYPE_AHARDWAREBUFFER:
                hasExternalMemoryFd = false;
                hasExternalMemoryDMABuf = false;
                break;
        }

        if (hasExternalMemoryFd) {
            resourceMemory->transportType = RESOURCE_MEMORY_TYPE_OPAQUE_FD;
            VkExternalMemoryBufferCreateInfo externalMemoryBufferInfo = {0};
            externalMemoryBufferInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
            externalMemoryBufferInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

            VkBufferCreateInfo bufferInfo = {0};
            bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
            bufferInfo.pNext = &externalMemoryBufferInfo;
            bufferInfo.size = memoryInfo->allocationSize;
            bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                               VK_BUFFER_USAGE_TRANSFER_DST_BIT |
                               VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT |
                               VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT |
                               VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT |
                               VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                               VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
                               VK_BUFFER_USAGE_VERTEX_BUFFER_BIT |
                               VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
            bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

            result = vulkanWrapper.vkCreateBuffer(device, &bufferInfo, NULL, &resourceMemory->buffer);
            if (result != VK_SUCCESS) goto error;

            VkMemoryRequirements memReqs = {0};
            vulkanWrapper.vkGetBufferMemoryRequirements(device, resourceMemory->buffer, &memReqs);

            VkExportMemoryAllocateInfo exportMemoryInfo = {0};
            exportMemoryInfo.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
            exportMemoryInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

            VkMemoryDedicatedAllocateInfo memoryDedicatedInfo = {0};
            memoryDedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
            memoryDedicatedInfo.pNext = &exportMemoryInfo;
            memoryDedicatedInfo.image = VK_NULL_HANDLE;
            memoryDedicatedInfo.buffer = resourceMemory->buffer;

            memoryInfo->pNext = &memoryDedicatedInfo;
            memoryInfo->allocationSize = memReqs.size;
            result = vulkanWrapper.vkAllocateMemory(device, memoryInfo, NULL, &resourceMemory->memory);
            if (result != VK_SUCCESS) goto error;

            VkMemoryGetFdInfoKHR getFdInfo = {0};
            getFdInfo.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
            getFdInfo.memory = resourceMemory->memory;
            getFdInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

            int fd = -1;
            result = vulkanWrapper.vkGetMemoryFd(device, &getFdInfo, &fd);
            if (result != VK_SUCCESS || fd <= 0) goto error;
            resourceMemory->fd = fd;
        }
        else {
            if ((getMemoryPropertyFlags(memoryInfo->memoryTypeIndex) & VK_MEMORY_PROPERTY_HOST_CACHED_BIT)) {
                memoryInfo->memoryTypeIndex = getMemoryTypeIndex(0x7FFFFFFF, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            }

            bool useExternalMemoryAHB = !hasExternalMemoryDMABuf;
            if (hasExternalMemoryDMABuf) {
                resourceMemory->transportType = RESOURCE_MEMORY_TYPE_DMA_BUF;
                int fd = dmabuf_alloc(memoryInfo->allocationSize);
                if (fd > 0) {
                    int dupFd = dup(fd);
                    VkImportMemoryFdInfoKHR memoryImportInfo = {0};
                    memoryImportInfo.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR;
                    memoryImportInfo.fd = dupFd;
                    memoryImportInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;

                    memoryInfo->pNext = &memoryImportInfo;
                    result = vulkanWrapper.vkAllocateMemory(device, memoryInfo, NULL, &resourceMemory->memory);
                    if (result == VK_SUCCESS) {
                        resourceMemory->fd = fd;
                    }
                    else {
                        CLOSEFD(fd);
                        CLOSEFD(dupFd);
                        useExternalMemoryAHB = true;
                    }
                }
                else useExternalMemoryAHB = true;
            }

            if (useExternalMemoryAHB) {
                resourceMemory->transportType = RESOURCE_MEMORY_TYPE_AHARDWAREBUFFER;
                AHardwareBuffer* hardwareBuffer = allocateHardwareBuffer(memoryInfo->allocationSize);
                if (!hardwareBuffer) goto error;
                resourceMemory->hardwareBuffer = hardwareBuffer;

                int fd = AHardwareBuffer_getFd(hardwareBuffer);
                if (fd <= 0) goto error;

                VkImportAndroidHardwareBufferInfoANDROID memoryImportInfo = {0};
                memoryImportInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
                memoryImportInfo.buffer = hardwareBuffer;

                memoryInfo->pNext = &memoryImportInfo;
                result = vulkanWrapper.vkAllocateMemory(device, memoryInfo, NULL, &resourceMemory->memory);
                if (result != VK_SUCCESS) goto error;
                resourceMemory->fd = fd;
            }
        }
    }
    else {
        result = vulkanWrapper.vkAllocateMemory(device, memoryInfo, NULL, &resourceMemory->memory);
        if (result != VK_SUCCESS) goto error;
    }

    if (result == VK_SUCCESS) context->totalAllocationSize += memoryInfo->allocationSize;
    resourceMemory->allocationSize = memoryInfo->allocationSize;
    logResourceMemoryAllocation(
        context,
        resourceMemory,
        requestedMemoryType,
        memoryInfo->memoryTypeIndex,
        requestedFlags,
        getMemoryPropertyFlags(memoryInfo->memoryTypeIndex),
        requestedSize,
        result);
    return resourceMemory;

error:
    logResourceMemoryAllocation(
        context,
        resourceMemory,
        requestedMemoryType,
        memoryInfo->memoryTypeIndex,
        requestedFlags,
        getMemoryPropertyFlags(memoryInfo->memoryTypeIndex),
        requestedSize,
        result);
    ResourceMemory_free(context, device, resourceMemory);
    return NULL;
}

void ResourceMemory_free(VkContext* context, VkDevice device, ResourceMemory* resourceMemory) {
    if (!resourceMemory) return;

    if (resourceMemory->buffer) {
        vulkanWrapper.vkDestroyBuffer(device, resourceMemory->buffer, NULL);
        resourceMemory->buffer = VK_NULL_HANDLE;
    }

    CLOSEFD(resourceMemory->fd);
    if (resourceMemory->memory) {
        vulkanWrapper.vkFreeMemory(device, resourceMemory->memory, NULL);
        resourceMemory->memory = VK_NULL_HANDLE;

        uint64_t totalAllocationSize = context->totalAllocationSize;
        totalAllocationSize = totalAllocationSize > resourceMemory->allocationSize ? (totalAllocationSize - resourceMemory->allocationSize) : 0;
        context->totalAllocationSize = totalAllocationSize;
    }

    if (resourceMemory->hardwareBuffer) {
        AHardwareBuffer_release(resourceMemory->hardwareBuffer);
        resourceMemory->hardwareBuffer = NULL;
    }

    resourceMemory->allocationSize = 0;
    MEMFREE(resourceMemory);
}
