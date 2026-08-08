#include "gl_context.h"
#include "gl_renderer.h"
#include "gl_dsa.h"
#include "sysvshared_memory.h"
#include "request_handler.h"
#include "egl_context_registry.h"

pthread_mutex_t glx_context_mutex = PTHREAD_MUTEX_INITIALIZER;

static void loadJMethods(JMethods* jmethods) {
    JNIEnv* env;
    (*jmethods->jvm)->AttachCurrentThread(jmethods->jvm, &env, NULL);
    jmethods->env = env;

    jclass cls = (*env)->GetObjectClass(env, jmethods->obj);
    jmethods->getWindowSize = (*env)->GetMethodID(env, cls, "getWindowSize", "(I)[S");
    jmethods->clearWindowContent = (*env)->GetMethodID(env, cls, "clearWindowContent", "(I)V");
    jmethods->updateWindowContent = (*env)->GetMethodID(env, cls, "updateWindowContent", "(ISSZZ)I");
    jmethods->getGLXContextPtr = (*env)->GetMethodID(env, cls, "getGLXContextPtr", "(II)J");
}

static void getWindowSize(JMethods* jmethods, int windowId, short* outWidth, short* outHeight) {
    jshortArray windowSize = (*jmethods->env)->CallObjectMethod(jmethods->env, jmethods->obj, jmethods->getWindowSize, windowId);
    jshort* windowSizePtr = (*jmethods->env)->GetShortArrayElements(jmethods->env, windowSize, 0);
    *outWidth = windowSizePtr[0];
    *outHeight = windowSizePtr[1];
    (*jmethods->env)->ReleaseShortArrayElements(jmethods->env, windowSize, windowSizePtr, JNI_ABORT);
}

static void createDisplayBuffers(GLContext* context) {
    for (int i = 0; i < ARRAY_SIZE(currentRenderer->displayBuffers); i++) {
        currentRenderer->displayBuffers[i] = GLFramebuffer_create();
        GLFramebuffer_bind(GL_FRAMEBUFFER, currentRenderer->displayBuffers[i]);
        GLFramebuffer_setAttachment(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, context->displayBufAttachments[i].texture, 0);
        GLFramebuffer_setAttachment(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, context->displayBufAttachments[i].renderbuffer, 0);
    }
}

static void destroyDisplayBuffers() {
    if (!currentRenderer) return;
    for (int i = 0; i < ARRAY_SIZE(currentRenderer->displayBuffers); i++) {
        if (currentRenderer->displayBuffers[i] > 0) {
            GLFramebuffer_delete(currentRenderer->displayBuffers[i]);
            currentRenderer->displayBuffers[i] = 0;
        }
    }
}

static void createDisplayBufAttachments(GLContext* context) {
    short width = currentRenderer->displaySize[0];
    short height = currentRenderer->displaySize[1];

    for (int i = 0; i < ARRAY_SIZE(context->displayBufAttachments); i++) {
        if (context->displayBufAttachments[i].texture == 0) {
            glGenTextures(1, &context->displayBufAttachments[i].texture);
            glBindTexture(GL_TEXTURE_2D, context->displayBufAttachments[i].texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
            glBindTexture(GL_TEXTURE_2D, 0);
        }

        if (context->displayBufAttachments[i].renderbuffer == 0) {
            glGenRenderbuffers(1, &context->displayBufAttachments[i].renderbuffer);
            glBindRenderbuffer(GL_RENDERBUFFER, context->displayBufAttachments[i].renderbuffer);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
            glBindRenderbuffer(GL_RENDERBUFFER, 0);
        }
    }
}

static void destroyDisplayBufAttachments(GLContext* context) {
    for (int i = 0; i < ARRAY_SIZE(context->displayBufAttachments); i++) {
        if (context->displayBufAttachments[i].texture > 0) {
            glDeleteTextures(1, &context->displayBufAttachments[i].texture);
            context->displayBufAttachments[i].texture = 0;
        }

        if (context->displayBufAttachments[i].renderbuffer > 0) {
            glDeleteRenderbuffers(1, &context->displayBufAttachments[i].renderbuffer);
            context->displayBufAttachments[i].renderbuffer = 0;
        }
    }
}

static void setCurrentRenderWindow(GLContext* context, int windowId) {
    if (windowId == 0) return;
    JMethods* jmethods = &context->jmethods;
    (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->clearWindowContent, windowId);

    bool hasDisplayBuffers = currentRenderer->displayBuffers[0] > 0 && currentRenderer->displayBuffers[1] > 0;
    if (hasDisplayBuffers) return;

    short width;
    short height;
    getWindowSize(&context->jmethods, windowId, &width, &height);
    if (width == 0 || height == 0) return;

    bool resized = currentRenderer->displaySize[0] != width || currentRenderer->displaySize[1] != height;
    currentRenderer->displaySize[0] = width;
    currentRenderer->displaySize[1] = height;

    if (resized) destroyDisplayBufAttachments(context);
    createDisplayBufAttachments(context);
    createDisplayBuffers(context);

    ARRAYS_FILL(currentRenderer->clientState.framebuffer, MAX_FRAMEBUFFER_TARGETS, 0);
    GLRenderer_setDrawBuffer(currentRenderer, GL_BACK);

    GLTexture* texture = currentRenderer->clientState.texture[indexOfGLTarget(GL_TEXTURE_2D)];
    glBindTexture(GL_TEXTURE_2D, texture ? texture->id : 0);

    glViewport(0, 0, width, height);
    glScissor(0, 0, width, height);
    currentRenderer->swapBuffers = true;
}

enum PresentationSource {
    PRESENTATION_UNKNOWN = 0,
    PRESENTATION_DRAW = 1,
    PRESENTATION_READ = 2,
    PRESENTATION_FRONT = 3,
    PRESENTATION_BACK = 4,
};

enum WindowUpdateResult {
    WINDOW_UPDATE_RESIZE = 0,
    WINDOW_UPDATE_BLACK = 1,
    WINDOW_UPDATE_CONTENT = 2,
};

static GLuint getPresentationFramebuffer(int source, GLuint drawFramebuffer,
                                         GLuint readFramebuffer) {
    switch (source) {
        case PRESENTATION_READ: return readFramebuffer;
        case PRESENTATION_FRONT: return currentRenderer->displayBuffers[0];
        case PRESENTATION_BACK: return currentRenderer->displayBuffers[1];
        case PRESENTATION_DRAW:
        default: return drawFramebuffer;
    }
}

static bool presentationNeedsFlip(int source) {
    return source != PRESENTATION_READ;
}

static int copyPresentation(GLContext* context, int drawableId, int source,
                            GLuint drawFramebuffer, GLuint readFramebuffer,
                            bool validateContent) {
    GLFramebuffer_bindReadback(
        getPresentationFramebuffer(source, drawFramebuffer, readFramebuffer));
    JMethods* jmethods = &context->jmethods;
    return (*jmethods->env)->CallIntMethod(
        jmethods->env, jmethods->obj, jmethods->updateWindowContent,
        drawableId, currentRenderer->displaySize[0], currentRenderer->displaySize[1],
        presentationNeedsFlip(source) ? JNI_TRUE : JNI_FALSE,
        validateContent ? JNI_TRUE : JNI_FALSE);
}

static void swapDisplayBuffers(GLContext* context, int drawableId) {
    GLuint drawFramebuffer = currentRenderer->clientState.framebuffer[indexOfGLTarget(GL_DRAW_FRAMEBUFFER)];
    GLuint readFramebuffer = currentRenderer->clientState.framebuffer[indexOfGLTarget(GL_READ_FRAMEBUFFER)];
    bool explicitRead = readFramebuffer > 0 &&
                        readFramebuffer != drawFramebuffer &&
                        readFramebuffer != currentRenderer->displayBuffers[0] &&
                        readFramebuffer != currentRenderer->displayBuffers[1];
    int result = WINDOW_UPDATE_BLACK;

    /* WineD3D can compose into either its current explicit read FBO or one of
     * the emulated default buffers, depending on GLX-context creation order.
     * IDs and bind history alone cannot distinguish the populated source. On
     * startup, validate the pixels from the normal full-frame readback and try
     * the finite candidate set until one contains real RGB content. Cache the
     * source kind (not the rotating FBO id) for this GLX context. */
    if (context->presentationSource != PRESENTATION_UNKNOWN) {
        bool revalidate = ++context->presentationFrames >= 300;
        result = copyPresentation(context, drawableId, context->presentationSource,
                                  drawFramebuffer, readFramebuffer, revalidate);
        if (result == WINDOW_UPDATE_BLACK && revalidate) {
            context->presentationSource = PRESENTATION_UNKNOWN;
            context->presentationFrames = 0;
        }
        else if (revalidate) context->presentationFrames = 0;
    }

    if (context->presentationSource == PRESENTATION_UNKNOWN &&
        result != WINDOW_UPDATE_RESIZE) {
        int candidates[] = {
            explicitRead ? PRESENTATION_READ : PRESENTATION_DRAW,
            explicitRead ? PRESENTATION_DRAW : PRESENTATION_READ,
            PRESENTATION_FRONT,
            PRESENTATION_BACK,
        };
        GLuint tried[ARRAY_SIZE(candidates)] = {0};
        int triedCount = 0;
        for (int i = 0; i < ARRAY_SIZE(candidates); i++) {
            GLuint framebuffer = getPresentationFramebuffer(
                candidates[i], drawFramebuffer, readFramebuffer);
            bool duplicate = framebuffer == 0;
            for (int j = 0; j < triedCount && !duplicate; j++) {
                duplicate = tried[j] == framebuffer;
            }
            if (duplicate) continue;
            tried[triedCount++] = framebuffer;
            result = copyPresentation(context, drawableId, candidates[i],
                                      drawFramebuffer, readFramebuffer, true);
            if (result == WINDOW_UPDATE_RESIZE) break;
            if (result == WINDOW_UPDATE_CONTENT) {
                context->presentationSource = candidates[i];
                context->presentationFrames = 0;
                println("gladio: presentation source=%d framebuffer=%u drawable=%d",
                        candidates[i], framebuffer, drawableId);
                break;
            }
        }
    }

    /* Presentation is an implementation readback, not a guest GL bind. Keep
     * Wine's cached framebuffer state intact and restore the real GLES read
     * binding immediately after Java has copied the pixels. */
    GLFramebuffer_bindReadback(readFramebuffer);
    if (result != WINDOW_UPDATE_RESIZE) {
        if (currentRenderer->swapBuffers) {
            SWAP(currentRenderer->displayBuffers[0], currentRenderer->displayBuffers[1], GLuint);
            GLRenderer_setDrawBuffer(currentRenderer, GL_BACK);
        }

        GLTexture* texture = currentRenderer->clientState.texture[indexOfGLTarget(GL_TEXTURE_2D)];
        glBindTexture(GL_TEXTURE_2D, texture ? texture->id : 0);
    }
    else {
        destroyDisplayBuffers();
        destroyDisplayBufAttachments(context);
        setCurrentRenderWindow(context, drawableId);
    }
}

static bool isCanDrawImmediate(short requestCode) {
    return requestCode == REQUEST_CODE_GL_BEGIN ||
           requestCode == REQUEST_CODE_GL_END ||
           requestCode == REQUEST_CODE_GL_DRAW_ARRAYS ||
           requestCode == REQUEST_CODE_GL_DRAW_ELEMENTS ||
           requestCode == REQUEST_CODE_GL_ENABLE_CLIENT_STATE ||
           requestCode == REQUEST_CODE_GL_DISABLE_CLIENT_STATE ||
           requestCode == REQUEST_CODE_GL_VERTEX_POINTER ||
           requestCode == REQUEST_CODE_GL_COLOR_POINTER ||
           requestCode == REQUEST_CODE_GL_NORMAL_POINTER ||
           requestCode == REQUEST_CODE_GL_TEX_COORD_POINTER ||
           requestCode == REQUEST_CODE_GL_VERTEX4F ||
           requestCode == REQUEST_CODE_GL_COLOR4F ||
           requestCode == REQUEST_CODE_GL_NORMAL3F ||
           requestCode == REQUEST_CODE_GL_TEX_COORD4F ||
           requestCode == REQUEST_CODE_GL_MULTI_TEX_COORD4F ||
           requestCode == REQUEST_CODE_GL_ARRAY_ELEMENT ? false : true;
}

static void* requestHandlerThread(void* param) {
    GLContext* context = param;
    loadJMethods(&context->jmethods);
    short requestCode;

    while (context->running) {
        if (!gl_recv(context->serverRing, &requestCode, &context->inputBuffer)) break;

        if (isCanDrawImmediate(requestCode)) GLRenderer_drawImmediate(currentRenderer);

        switch (requestCode) {
            case REQUEST_CODE_SET_CURRENT_RENDER_WINDOW: {
                int windowId = ArrayBuffer_getInt(&context->inputBuffer);
                int contextId = ArrayBuffer_getInt(&context->inputBuffer);
                JMethods* jmethods = &context->jmethods;
                GLXContext* glxContext = (GLXContext*)(*jmethods->env)->CallLongMethod(jmethods->env, jmethods->obj, jmethods->getGLXContextPtr, context->clientFd, contextId);

                if (glxContext && context->glxContext != glxContext) {
                    destroyDisplayBuffers();
                    eglMakeCurrent(eglGetDisplay(EGL_DEFAULT_DISPLAY), EGL_NO_SURFACE,
                                   EGL_NO_SURFACE, glxContext->eglContext);
                    context->glxContext = glxContext;
                    currentRenderer = &glxContext->renderer;
                    context->presentationSource = PRESENTATION_UNKNOWN;
                    context->presentationFrames = 0;
                    GLRenderer_resetFrameCount(currentRenderer);
                }

                setCurrentRenderWindow(context, windowId);
                gl_send(context->clientRing, REQUEST_CODE_SET_CURRENT_RENDER_WINDOW, NULL, 0);
                break;
            }
            case REQUEST_CODE_SWAP_DISPLAY_BUFFERS: {
                int drawableId = ArrayBuffer_getInt(&context->inputBuffer);
                currentRenderer->frameCount++;
                swapDisplayBuffers(context, drawableId);
                gl_send(context->clientRing, REQUEST_CODE_SWAP_DISPLAY_BUFFERS, NULL, 0);
                break;
            }
            default: {
                if (requestCode >= REQUEST_CODE_GL_DSA_START && requestCode < REQUEST_CODE_GL_CALL_START) {
                    handleDSARequest(context, requestCode);
                    break;
                }

#if IS_DEBUG_ENABLED(DEBUG_MODE_HANDLE_REQUEST)
                println("handleRequest name=%s size=%d", requestCodeToString(requestCode), context->inputBuffer.size);
#endif

                HandleRequestFunc handleRequestFunc = getHandleRequestFunc(requestCode);
                if (handleRequestFunc) handleRequestFunc(context);
                break;
            }
        }

#if IS_DEBUG_ENABLED(DEBUG_MODE_GL_ERROR)
        GLenum error = glGetError();
        if (error != GL_NO_ERROR) println("gladio: glError %s %x", requestCodeToString(requestCode), error);
#endif
    }

    destroyDisplayBuffers();
    destroyDisplayBufAttachments(context);
    if (context->glxContext) {
        eglMakeCurrent(eglGetDisplay(EGL_DEFAULT_DISPLAY), EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        context->glxContext = NULL;
    }
    (*context->jmethods.jvm)->DetachCurrentThread(context->jmethods.jvm);
    return NULL;
}

GLContext* createGLContext(JNIEnv* env, jobject obj, int clientFd) {
    GLContext* context = calloc(1, sizeof(GLContext));
    context->clientFd = clientFd;
    context->presentationSource = PRESENTATION_UNKNOWN;

    int shmFds[2];
    shmFds[0] = ashmemCreateRegion("gl-server-ring", RingBuffer_getSHMemSize(SERVER_RING_BUFFER_SIZE));
    shmFds[1] = ashmemCreateRegion("gl-client-ring", RingBuffer_getSHMemSize(CLIENT_RING_BUFFER_SIZE));

    context->serverRing = RingBuffer_create(shmFds[0], SERVER_RING_BUFFER_SIZE);
    if (!context->serverRing) goto error;

    context->clientRing = RingBuffer_create(shmFds[1], CLIENT_RING_BUFFER_SIZE);
    if (!context->clientRing) goto error;

    int result = send_fds(clientFd, shmFds, 2, NULL, 0);
    if (result < 0) goto error;

    (*env)->GetJavaVM(env, &context->jmethods.jvm);
    context->jmethods.obj = (*env)->NewGlobalRef(env, obj);

    context->threadPool = ThreadPool_init(THREAD_POOL_NUM_THREADS);
    context->running = true;
    pthread_create(&context->requestHandlerThread, NULL, requestHandlerThread, context);

    CLOSEFD(shmFds[0]);
    CLOSEFD(shmFds[1]);
    return context;

error:
    CLOSEFD(shmFds[0]);
    CLOSEFD(shmFds[1]);
    MEMFREE(context);
    return NULL;
}

void destroyGLContext(JNIEnv* env, GLContext* context) {
    context->running = false;

    if (context->requestHandlerThread) {
        RingBuffer_setStatus(context->serverRing, RING_STATUS_EXIT);
        RingBuffer_setStatus(context->clientRing, RING_STATUS_EXIT);
        pthread_join(context->requestHandlerThread, NULL);

        ThreadPool_destroy(context->threadPool);
        context->threadPool = NULL;

        context->requestHandlerThread = 0;
        RingBuffer_free(context->serverRing);
        RingBuffer_free(context->clientRing);
    }

    if (context->jmethods.obj) {
        (*env)->DeleteGlobalRef(env, context->jmethods.obj);
        context->jmethods.obj = NULL;
    }

    ArrayBuffer_free(&context->inputBuffer);
    ArrayBuffer_free(&context->outputBuffer);

    free(context);
}

GLXContext* createGLXContext(int contextId, GLXContext* sharedContext) {
    static const EGLint confAttribList[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    static const EGLint ctxAttribList[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    EGLBoolean success;

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) return NULL;

    EGLint major, minor;
    success = eglInitialize(eglDisplay, &major, &minor);
    if (!success) return NULL;

    int numConfigs;
    EGLConfig eglConfig;
    success = eglChooseConfig(eglDisplay, confAttribList, &eglConfig, 1, &numConfigs);
    if (!success || numConfigs != 1) return NULL;

    EGLContext eglContext;
    uint64_t shareGeneration = 0;
    if (sharedContext) {
        eglContext = eglCreateContext(
            eglDisplay, eglConfig, sharedContext->eglContext, ctxAttribList);
    }
    else {
        /* Keep surface invalidation from racing the creation of its first
         * shared child. Once eglCreateContext succeeds the child keeps the
         * share group alive even if Android later replaces the surface. */
        pthread_mutex_lock(&globalEGLContextMutex);
        EGLContext shareContext = globalEGLContext;
        shareGeneration = globalEGLContextGeneration;
        eglContext = shareContext == EGL_NO_CONTEXT || shareGeneration == 0
            ? EGL_NO_CONTEXT
            : eglCreateContext(eglDisplay, eglConfig, shareContext, ctxAttribList);
        pthread_mutex_unlock(&globalEGLContextMutex);
    }
    if (eglContext == EGL_NO_CONTEXT) {
        EGLint error = eglGetError();
        __android_log_print(ANDROID_LOG_ERROR, "PR/Gladio",
                            "EGL create failed context=%d shareGeneration=%llu error=0x%x",
                            contextId, (unsigned long long)shareGeneration, error);
        return NULL;
    }

    GLXContext* context = calloc(1, sizeof(GLXContext));
    if (!context) {
        eglDestroyContext(eglDisplay, eglContext);
        return NULL;
    }
    context->eglContext = eglContext;
    context->renderer.contextId = contextId;

    GLX_CONTEXT_LOCK();
    success = eglMakeCurrent(
        eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, context->eglContext);
    if (!success) {
        EGLint error = eglGetError();
        GLX_CONTEXT_UNLOCK();
        __android_log_print(ANDROID_LOG_ERROR, "PR/Gladio",
                            "EGL make-current failed context=%d shareGeneration=%llu error=0x%x",
                            contextId, (unsigned long long)shareGeneration, error);
        eglDestroyContext(eglDisplay, eglContext);
        free(context);
        return NULL;
    }
    GLClientState_init(
        &context->renderer.clientState,
        sharedContext ? &sharedContext->renderer.clientState : NULL);
    GLVertexArrayObject_setBound(&context->renderer.clientState, 0);
    GLRenderer_initOnEGLContext(&context->renderer);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    GLX_CONTEXT_UNLOCK();
    return context;
}

void destroyGLXContext(GLXContext* context) {
    GLX_CONTEXT_LOCK();
    GLClientState_destroy(&context->renderer.clientState);
    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, context->eglContext);
    GLRenderer_destroy(&context->renderer);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    GLX_CONTEXT_UNLOCK();
    eglDestroyContext(eglDisplay, context->eglContext);
}

static void internalReadVertexArrayElement(GLContext* context, int arrayIdx, int elementIdx, ArrayBuffer* dstBuffer, float* constValue) {
#define PARSE_VALUESF(glType, divVal) \
    const glType* values = srcValues; \
    for (int i = 0; i < vertexAttrib->size; i++) dstValues[i] = arrayIdx == COLOR_ARRAY_INDEX ? (float)values[i] / divVal : (float)values[i]

    GLClientState* clientState = &currentRenderer->clientState;
    if (!clientState->vao->attribs[arrayIdx].state) {
        if (constValue && dstBuffer->position > 0) {
            if (arrayIdx == NORMAL_ARRAY_INDEX) {
                ArrayBuffer_putBytes(dstBuffer, constValue, 3 * sizeof(float));
            }
            else ArrayBuffer_putBytes(dstBuffer, constValue, 4 * sizeof(float));
        }
        return;
    }

    GLVertexAttrib* vertexAttrib = &clientState->vao->attribs[arrayIdx];

    float* dstValues = NULL;
    if (arrayIdx == NORMAL_ARRAY_INDEX) {
        dstValues = ArrayBuffer_putFloat3(dstBuffer, 0.0f, 0.0f, 1.0f);
    }
    else dstValues = ArrayBuffer_putFloat4(dstBuffer, 0.0f, 0.0f, 0.0f, 1.0f);
    dstBuffer->position++;

    void* srcValues;
    if (vertexAttrib->pointer) {
        srcValues = vertexAttrib->pointer + (elementIdx - clientState->indexStart) * vertexAttrib->stride;
    }
    else srcValues = ArrayBuffer_getBytes(&context->inputBuffer, vertexAttrib->stride);

    switch (vertexAttrib->type) {
        case GL_FLOAT: {
            memcpy(dstValues, srcValues, vertexAttrib->size * sizeof(float));
            break;
        }
        case GL_HALF_FLOAT: {
            const GLhalf* values = srcValues;
            _Float16 value;
            for (int i = 0; i < vertexAttrib->size; i++) {
                memcpy(&value, &values[i], sizeof(value));
                dstValues[i] = (float)value;
            }
            break;
        }
        case GL_BYTE: {
            PARSE_VALUESF(GLbyte, INT8_MAX);
            break;
        }
        case GL_UNSIGNED_BYTE: {
            PARSE_VALUESF(GLubyte, UINT8_MAX);
            break;
        }
        case GL_SHORT: {
            PARSE_VALUESF(GLshort, INT16_MAX);
            break;
        }
        case GL_UNSIGNED_SHORT: {
            PARSE_VALUESF(GLushort, UINT16_MAX);
            break;
        }
        case GL_INT: {
            PARSE_VALUESF(GLint, (double)INT32_MAX);
            break;
        }
        case GL_UNSIGNED_INT: {
            PARSE_VALUESF(GLuint, (double)UINT32_MAX);
            break;
        }
        case GL_DOUBLE: {
            PARSE_VALUESF(GLdouble, 1);
            break;
        }
    }

#undef PARSE_VALUESF
}

void readCommandBuffer(GLContext* context) {
    if (ArrayBuffer_available(&context->inputBuffer) == 0) {
        GLRenderer_endImmediate(currentRenderer);
        return;
    }

    GLenum mode = ArrayBuffer_getInt(&context->inputBuffer);
    int indexStart = ArrayBuffer_getInt(&context->inputBuffer);
    int indexCount = ArrayBuffer_getInt(&context->inputBuffer);
    int bufferSize = ArrayBuffer_getInt(&context->inputBuffer);

    if (bufferSize == 0) return;

    GLClientState* clientState = &currentRenderer->clientState;
    clientState->indexStart = indexStart;
    if (indexCount > 0) {
        for (int i = 0; i < clientState->vao->maxEnabledAttribs; i++) {
            if (clientState->vao->attribs[i].state) {
                clientState->vao->attribs[i].pointer = context->inputBuffer.buffer + context->inputBuffer.position;
                context->inputBuffer.position += indexCount * clientState->vao->attribs[i].stride;
            }
        }
    }
    else ArrayBuffer_rewind(&context->inputBuffer);

    ENSURE_ARRAY_CAPACITY(context->inputBuffer.size + bufferSize, context->inputBuffer.capacity, context->inputBuffer.buffer, 1);
    RingBuffer_read(context->serverRing, context->inputBuffer.buffer + context->inputBuffer.position, bufferSize);
    context->inputBuffer.size += bufferSize;

    GLRenderer_beginImmediate(currentRenderer, mode);
    while (ArrayBuffer_available(&context->inputBuffer) > 0) {
        short requestCode = ArrayBuffer_getShort(&context->inputBuffer);
        HandleRequestFunc handleRequestFunc = getHandleRequestFunc(requestCode);
        if (!handleRequestFunc) break;
        handleRequestFunc(context);
    }
    GLRenderer_endImmediate(currentRenderer);

    for (int i = 0; i < clientState->vao->maxEnabledAttribs; i++) {
        if (clientState->vao->attribs[i].state) clientState->vao->attribs[i].pointer = NULL;
    }
    clientState->indexStart = 0;
}

void readVertexArrayElement(GLContext* context, int arrayIdx, int elementIdx) {
    switch (arrayIdx) {
        case POSITION_ARRAY_INDEX:
            internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.vertices, NULL);
            break;
        case COLOR_ARRAY_INDEX:
            internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.colors, currentRenderer->state.color);
            break;
        case NORMAL_ARRAY_INDEX:
            internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.normals, currentRenderer->state.normal);
            break;
        default:
            if (arrayIdx >= TEXCOORD_ARRAY_INDEX) {
                int index = arrayIdx - TEXCOORD_ARRAY_INDEX;
                internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.texCoords[index], currentRenderer->state.texCoords[index]);
            }
            break;
    }
}

#define POCKET_DRAW_ATTR_MAGIC 0x504b4131
#define POCKET_DRAW_ATTR_GENERIC 1
#define POCKET_DRAW_ATTR_LEGACY 2

bool readUnboundVertexArrays(GLContext* context, GLenum drawMode, int drawCount, void** outIndices, GLenum indexType) {
    if (indexType != GL_NONE) {
        if (GLBuffer_getBound(GL_ELEMENT_ARRAY_BUFFER)) {
            *outIndices = (void*)(uint64_t)ArrayBuffer_getInt(&context->inputBuffer);
        }
        else *outIndices = ArrayBuffer_getBytes(&context->inputBuffer, drawCount * sizeofGLType(indexType));
    }

    if (ArrayBuffer_available(&context->inputBuffer) == 0) return false;
    if (ArrayBuffer_available(&context->inputBuffer) < 2 * (int)sizeof(int)) {
        println("gladio: draw payload truncated header available=%d",
                ArrayBuffer_available(&context->inputBuffer));
        return true;
    }

    int magic = ArrayBuffer_getInt(&context->inputBuffer);
    int recordCount = ArrayBuffer_getInt(&context->inputBuffer);
    if (magic != POCKET_DRAW_ATTR_MAGIC || recordCount < 0 ||
        recordCount > VERTEX_ATTRIB_COUNT) {
        println("gladio: draw payload invalid magic=0x%x records=%d available=%d",
                magic, recordCount,
                ArrayBuffer_available(&context->inputBuffer));
        return true;
    }

    bool meshCreated = false;

    GLClientState* clientState = &currentRenderer->clientState;
    for (int record = 0; record < recordCount; record++) {
        if (ArrayBuffer_available(&context->inputBuffer) < 3 * (int)sizeof(int)) {
            println("gladio: draw payload record=%d truncated metadata available=%d",
                    record,
                    ArrayBuffer_available(&context->inputBuffer));
            return true;
        }
        int i = ArrayBuffer_getInt(&context->inputBuffer);
        int kind = ArrayBuffer_getInt(&context->inputBuffer);
        int byteCount = ArrayBuffer_getInt(&context->inputBuffer);
        if (i < 0 || i >= VERTEX_ATTRIB_COUNT || byteCount < 0 ||
            byteCount > ArrayBuffer_available(&context->inputBuffer)) {
            println("gladio: draw payload record=%d invalid index=%d kind=%d bytes=%d available=%d",
                    record, i, kind, byteCount,
                    ArrayBuffer_available(&context->inputBuffer));
            return true;
        }

        void* pointer = ArrayBuffer_getBytes(&context->inputBuffer, byteCount);
        GLVertexAttrib* vertexAttrib = &clientState->vao->attribs[i];
        if (kind == POCKET_DRAW_ATTR_GENERIC) {
            int size = MIN(4, vertexAttrib->size);
            uintptr_t pointerOffset = 0;
            if (vertexAttrib->size == GL_BGRA) {
                uint64_t offset = (uint64_t)vertexAttrib->pointer;
                if (offset > (uint64_t)byteCount || vertexAttrib->stride < 3) return true;
                swapPixelsRedBlue(pointer + offset, vertexAttrib->stride, byteCount - offset);
                pointerOffset = (uintptr_t)vertexAttrib->pointer;
            }

            int location = i;
            if (GLClientState_isLegacyEnabledWithProgram(clientState, i) ||
                ARBProgram_isActive()) {
                if (clientState->program) {
                    location = clientState->program->location.attributes[i];
                }
                else location = clientState->arbProgram[0]->material->location.attributes[i];
            }

            if (location < 0) continue;
            GLuint* transientBuffer = &clientState->vao->transientBuffers[i];
            if (*transientBuffer == 0) glGenBuffers(1, transientBuffer);
            GLBuffer* oldArrayBuffer = clientState->vao->buffer[indexOfGLTarget(GL_ARRAY_BUFFER)];
            glBindBuffer(GL_ARRAY_BUFFER, *transientBuffer);
            glBufferData(GL_ARRAY_BUFFER, byteCount, pointer, GL_STREAM_DRAW);
            GLRenderer_enableVertexAttribute(currentRenderer, location);
            glVertexAttribPointer(location, size, vertexAttrib->type,
                                  vertexAttrib->normalized, vertexAttrib->stride,
                                  (const void*)pointerOffset);
            glBindBuffer(GL_ARRAY_BUFFER, oldArrayBuffer ? oldArrayBuffer->id : 0);
        }
        else if (kind == POCKET_DRAW_ATTR_LEGACY) {
            if (!meshCreated) {
                GLRenderer_beginImmediate(currentRenderer, drawMode);
                meshCreated = true;
            }

            ArrayBuffer savedInput = context->inputBuffer;
            context->inputBuffer.buffer = pointer;
            context->inputBuffer.position = 0;
            context->inputBuffer.size = byteCount;
            context->inputBuffer.capacity = byteCount;
            for (int j = 0; j < drawCount; j++) {
                readVertexArrayElement(context, i, -1);
                if (i == POSITION_ARRAY_INDEX) {
                    GLRenderer_addArrayElement(currentRenderer, currentRenderer->geometry.vertices.position-1);
                }
            }
            context->inputBuffer = savedInput;
        }
        else {
            println("gladio: draw payload record=%d invalid kind=%d", record, kind);
            return true;
        }
    }
    if (meshCreated) GLRenderer_endImmediate(currentRenderer);
    return meshCreated;
}

const char* getGLExtensions(int* outNumExtensions) {
    static int numExtensions = 0;
    /* Gladio is a compatibility translator over the GLES 3.0 product floor,
     * desktop OpenGL 3.3 implementation. Advertising instancing/base-vertex,
     * sampler, UBO, and other modern paths makes WineD3D 11 select draw paths
     * that this pinned provider cannot preserve. Keep the format-query pair:
     * WineD3D needs it to retain B8G8R8X8 render-target support. */
    static const char* extensionNames =
        "GL_EXT_abgr GL_ARB_shadow GL_ARB_window_pos GL_EXT_packed_pixels "
        "GL_ARB_vertex_buffer_object GL_ARB_vertex_array_object "
        "GL_ARB_texture_border_clamp GL_ARB_texture_env_add GL_EXT_texture_env_add "
        "GL_EXT_draw_range_elements GL_EXT_bgra GL_ARB_texture_compression "
        "GL_EXT_texture_compression_s3tc GL_EXT_texture_compression_dxt1 "
        "GL_EXT_texture_compression_dxt3 GL_EXT_texture_compression_dxt5 "
        "GL_ARB_point_parameters GL_EXT_point_parameters GL_EXT_texture_edge_clamp "
        "GL_EXT_multi_draw_arrays GL_ARB_multisample GL_EXT_polygon_offset "
        "GL_ARB_texture_rectangle GL_EXT_vertex_array GL_ARB_vertex_array_bgra "
        "GL_ARB_texture_non_power_of_two GL_EXT_blend_color GL_EXT_blend_minmax "
        "GL_EXT_blend_equation_separate GL_EXT_blend_func_separate "
        "GL_EXT_blend_subtract GL_EXT_texture_filter_anisotropic "
        "GL_ARB_texture_mirrored_repeat GL_ARB_point_sprite GL_ARB_texture_cube_map "
        "GL_EXT_texture_cube_map GL_EXT_texture_rg GL_ARB_texture_rg "
        "GL_EXT_texture_float GL_ARB_texture_float GL_EXT_texture_half_float "
        "GL_EXT_color_buffer_float GL_EXT_color_buffer_half_float "
        "GL_EXT_depth_texture GL_ARB_depth_texture GL_ARB_depth_clamp "
        "GL_ARB_ES2_compatibility GL_ARB_fragment_shader GL_ARB_vertex_shader "
        "GL_ARB_shading_language_100 GL_ARB_framebuffer_object "
        "GL_EXT_framebuffer_object GL_EXT_packed_depth_stencil "
        "GL_EXT_framebuffer_blit GL_ARB_draw_buffers GL_ARB_internalformat_query "
        "GL_ARB_internalformat_query2 GL_ARB_map_buffer_range "
        "GL_ARB_multitexture GL_ARB_texture_env_combine GL_EXT_texture_env_combine "
        "GL_ARB_texture_env_dot3 GL_EXT_texture_env_dot3 GL_ARB_shader_objects "
        "GL_ARB_vertex_program GL_ARB_fragment_program GL_ARB_color_buffer_float "
        "GL_ARB_occlusion_query";

    if (numExtensions == 0) {
        char* ptr = (char*)extensionNames;
        while ((ptr = strchr(ptr, ' '))) {
            while (*ptr == ' ') ptr++;
            numExtensions++;
        }
    }

    if (outNumExtensions) *outNumExtensions = numExtensions;
    return extensionNames;
}
