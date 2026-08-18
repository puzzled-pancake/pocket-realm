#ifndef POCKET_EGL_CONTEXT_REGISTRY_H
#define POCKET_EGL_CONTEXT_REGISTRY_H

#include <stdint.h>
#include <pthread.h>
#include <EGL/egl.h>

/*
 * The Android GLSurfaceView context is the share root for Wine GLX contexts.
 * Its pointer and monotonic owner-generation watermark cross
 * libwinlator/libgladiorenderer and GL threads, so every read/write must hold
 * this mutex. Clearing the pointer must not reset the watermark: a delayed
 * callback from an older surface must never republish its stale context.
 */
extern EGLContext globalEGLContext;
extern uint64_t globalEGLContextGeneration;
extern pthread_mutex_t globalEGLContextMutex;

#endif
