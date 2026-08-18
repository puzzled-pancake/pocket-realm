#include <dlfcn.h>
#include <jni.h>
#include <string.h>

#include "vk_context.h"
#include "vortek_serializer.h"
#include "request_handler.h"
#include "vulkan_helper.h"
#include "jni_utils.h"

/*
 * Pocket Realm's Vortek provider intentionally opens only Android's public
 * Vulkan soname.  Custom Turnip is a separate, hash-pinned guest ICD path and
 * never enters this Android linker namespace.
 */
VulkanWrapper vulkanWrapper = {0};
bool vortekSerializerCastVkObject = true;
static void* systemVulkanLibrary = NULL;

JNIEXPORT jlong JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_createVkContext(
        JNIEnv* env, jobject object, jint clientFd, jobject options) {
    VkContext* context = createVkContext(env, object, clientFd, options);
    return context ? (jlong)context : 0;
}

JNIEXPORT void JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_destroyVkContext(
        JNIEnv* env, jobject object, jlong contextPtr) {
    destroyVkContext(env, (VkContext*)contextPtr);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_initVulkanWrapper(
        JNIEnv* env, jobject object) {
    (void)env;
    (void)object;
    if (!systemVulkanLibrary) {
        systemVulkanLibrary = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    }
    if (!systemVulkanLibrary) {
        println("vortek: unable to open Android libvulkan.so: %s", dlerror());
        return JNI_FALSE;
    }
    initVulkanWrapper(&vulkanWrapper, systemVulkanLibrary);
    if (!vulkanWrapper.vkGetInstanceProcAddr || !vulkanWrapper.vkCreateInstance ||
            !vulkanWrapper.vkEnumerateInstanceExtensionProperties) {
        println("vortek: Android Vulkan loader lacks required entrypoints");
        memset(&vulkanWrapper, 0, sizeof(vulkanWrapper));
        dlclose(systemVulkanLibrary);
        systemVulkanLibrary = NULL;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_handleExtraDataRequest(
        JNIEnv* env, jobject object, jlong contextPtr, jint requestId, jint requestLength) {
    (void)env;
    (void)object;
    return handleExtraDataRequest((VkContext*)contextPtr, requestId, requestLength);
}
