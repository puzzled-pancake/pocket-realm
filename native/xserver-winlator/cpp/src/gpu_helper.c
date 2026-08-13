#include <jni.h>
#include <malloc.h>
#include <android/log.h>
#include <stdlib.h>
#include <dlfcn.h>

#define VK_NO_PROTOTYPES 1
#include <vulkan/vulkan.h>
#include <EGL/egl.h>

#include "winlator.h"
#include "egl_context_registry.h"

EGLContext globalEGLContext = EGL_NO_CONTEXT;
uint64_t globalEGLContextGeneration = 0;
pthread_mutex_t globalEGLContextMutex = PTHREAD_MUTEX_INITIALIZER;

#define MAX_VULKAN_PHYSICAL_DEVICES 64U
#define MAX_VULKAN_DEVICE_EXTENSIONS 1024U

/** One checked snapshot from one physical device exposed by Android's public loader. */
JNIEXPORT jobject JNICALL
Java_com_winlator_core_GPUHelper_nativeProbeSystemVulkan(JNIEnv *env, jclass obj) {
    (void)obj;
    jobject probe = NULL;
    jobjectArray extensionArray = NULL;
    jclass stringClass = NULL;
    jclass probeClass = NULL;
    VkExtensionProperties* extensions = NULL;
    VkInstance instance = VK_NULL_HANDLE;
    PFN_vkDestroyInstance vkDestroyInstance = NULL;
    void* libvulkan = dlopen(LIBVULKAN_PATH, RTLD_NOW | RTLD_LOCAL);
    if (!libvulkan) goto done;

    PFN_vkCreateInstance vkCreateInstance = dlsym(libvulkan, "vkCreateInstance");
    vkDestroyInstance = dlsym(libvulkan, "vkDestroyInstance");
    PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices =
            dlsym(libvulkan, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties vkGetPhysicalDeviceProperties =
            dlsym(libvulkan, "vkGetPhysicalDeviceProperties");
    PFN_vkGetPhysicalDeviceFeatures vkGetPhysicalDeviceFeatures =
            dlsym(libvulkan, "vkGetPhysicalDeviceFeatures");
    PFN_vkEnumerateDeviceExtensionProperties vkEnumerateDeviceExtensionProperties =
            dlsym(libvulkan, "vkEnumerateDeviceExtensionProperties");
    if (!vkCreateInstance || !vkDestroyInstance || !vkEnumeratePhysicalDevices ||
            !vkGetPhysicalDeviceProperties || !vkGetPhysicalDeviceFeatures ||
            !vkEnumerateDeviceExtensionProperties) goto done;

    VkInstanceCreateInfo createInfo = {0};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    if (vkCreateInstance(&createInfo, NULL, &instance) != VK_SUCCESS) goto done;

    uint32_t deviceCount = 0;
    if (vkEnumeratePhysicalDevices(instance, &deviceCount, NULL) != VK_SUCCESS ||
            deviceCount == 0 || deviceCount > MAX_VULKAN_PHYSICAL_DEVICES) goto done;
    deviceCount = 1;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkResult result = vkEnumeratePhysicalDevices(instance, &deviceCount, &physicalDevice);
    if ((result != VK_SUCCESS && result != VK_INCOMPLETE) || deviceCount != 1 ||
            physicalDevice == VK_NULL_HANDLE) goto done;

    VkPhysicalDeviceProperties properties = {0};
    VkPhysicalDeviceFeatures features = {0};
    vkGetPhysicalDeviceProperties(physicalDevice, &properties);
    vkGetPhysicalDeviceFeatures(physicalDevice, &features);
    if (properties.apiVersion == 0) goto done;

    uint32_t extensionCount = 0;
    result = vkEnumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount, NULL);
    if (result != VK_SUCCESS || extensionCount == 0 ||
            extensionCount > MAX_VULKAN_DEVICE_EXTENSIONS) goto done;
    extensions = calloc(extensionCount, sizeof(VkExtensionProperties));
    if (!extensions) goto done;
    uint32_t extensionCapacity = extensionCount;
    result = vkEnumerateDeviceExtensionProperties(
            physicalDevice, NULL, &extensionCount, extensions);
    if ((result != VK_SUCCESS && result != VK_INCOMPLETE) || extensionCount == 0 ||
            extensionCount > extensionCapacity) goto done;

    stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass || (*env)->ExceptionCheck(env)) goto done;
    extensionArray = (*env)->NewObjectArray(env, (jsize)extensionCount, stringClass, NULL);
    if (!extensionArray || (*env)->ExceptionCheck(env)) goto done;
    for (uint32_t i = 0; i < extensionCount; i++) {
        jstring extension = (*env)->NewStringUTF(env, extensions[i].extensionName);
        if (!extension || (*env)->ExceptionCheck(env)) goto done;
        (*env)->SetObjectArrayElement(env, extensionArray, (jsize)i, extension);
        (*env)->DeleteLocalRef(env, extension);
        if ((*env)->ExceptionCheck(env)) goto done;
    }

    probeClass = (*env)->FindClass(env, "com/winlator/core/GPUHelper$SystemVulkanProbe");
    if (!probeClass || (*env)->ExceptionCheck(env)) goto done;
    jmethodID constructor = (*env)->GetMethodID(env, probeClass, "<init>", "(IZ[Ljava/lang/String;)V");
    if (!constructor || (*env)->ExceptionCheck(env)) goto done;
    probe = (*env)->NewObject(
            env, probeClass, constructor, (jint)properties.apiVersion,
            features.textureCompressionBC == VK_TRUE ? JNI_TRUE : JNI_FALSE,
            extensionArray);
    if ((*env)->ExceptionCheck(env)) probe = NULL;

done:
    if (probeClass) (*env)->DeleteLocalRef(env, probeClass);
    if (extensionArray) (*env)->DeleteLocalRef(env, extensionArray);
    if (stringClass) (*env)->DeleteLocalRef(env, stringClass);
    if (instance && vkDestroyInstance) vkDestroyInstance(instance, NULL);
    free(extensions);
    if (libvulkan) dlclose(libvulkan);
    return probe;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_core_GPUHelper_setGlobalEGLContext(JNIEnv *env, jclass obj,
                                                      jlong generation) {
    EGLContext context = eglGetCurrentContext();
    if (context == EGL_NO_CONTEXT || generation <= 0) return JNI_FALSE;

    pthread_mutex_lock(&globalEGLContextMutex);
    bool accepted = (uint64_t)generation > globalEGLContextGeneration;
    if (accepted) {
        globalEGLContext = context;
        globalEGLContextGeneration = (uint64_t)generation;
    }
    pthread_mutex_unlock(&globalEGLContextMutex);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winlator_core_GPUHelper_clearGlobalEGLContext(JNIEnv *env, jclass obj,
                                                        jlong generation) {
    pthread_mutex_lock(&globalEGLContextMutex);
    if (generation > 0 && (uint64_t)generation == globalEGLContextGeneration) {
        globalEGLContext = EGL_NO_CONTEXT;
    }
    pthread_mutex_unlock(&globalEGLContextMutex);
}
