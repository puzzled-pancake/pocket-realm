#ifndef VORTEK_XWINDOW_SWAPCHAIN_H
#define VORTEK_XWINDOW_SWAPCHAIN_H

#include <android/hardware_buffer.h>

#include "vortek.h"

typedef struct XWindowSwapchain_Image {
    VkImage image;
    VkDeviceMemory memory;
} XWindowSwapchain_Image;

typedef struct XWindowSwapchain {
    uint64_t contextGeneration;
    uint64_t instanceToken;
    int windowId;
    XWindowSwapchain_Image* images;
    uint32_t imageCount;
    VkFormat imageFormat;
    VkExtent2D imageExtent;
    VkImageUsageFlags imageUsage;
    VkQueue queue;
    JMethods* jmethods;
} XWindowSwapchain;

extern bool getWindowExtent(
        JMethods* jmethods, uint64_t contextGeneration,
        uint64_t instanceToken, int windowId, VkExtent2D* extent);
extern int getSurfaceMinImageCount(void);
extern VkSurfaceFormatKHR* getSurfaceFormats(uint32_t* formatCount);

extern VkResult XWindowSwapchain_create(
        VkDevice device, uint32_t graphicsQueueIndex,
        const VkSwapchainCreateInfoKHR* swapchainInfo, JMethods* jmethods,
        uint64_t contextGeneration, uint64_t instanceToken, int windowId,
        XWindowSwapchain** swapchainOut);
extern void XWindowSwapchain_destroy(
        VkDevice device, XWindowSwapchain* swapchain);
extern VkResult XWindowSwapchain_acquireNextImage(
        XWindowSwapchain* swapchain, uint64_t timeout,
        VkSemaphore signalSemaphore, VkFence fence, uint32_t* imageIndex);
extern bool XWindowSwapchain_presentImage(XWindowSwapchain* swapchain);

#endif
