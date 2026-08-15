#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <stdio.h>
#include <string.h>

int main() {
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY) { printf("FAIL eglGetDisplay\n"); return 1; }
    EGLint maj, min;
    if (!eglInitialize(dpy, &maj, &min)) { printf("FAIL eglInitialize err=0x%x\n", eglGetError()); return 1; }
    printf("EGL %d.%d\n", maj, min);
    const char* exts = eglQueryString(dpy, EGL_EXTENSIONS);
    printf("surfaceless_ext=%d\n", exts && strstr(exts, "EGL_KHR_surfaceless_context"));

    EGLint cfgAttr[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, 0x0040,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE };
    EGLConfig cfg; EGLint n;
    if (!eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n) || n != 1) { printf("FAIL eglChooseConfig err=0x%x n=%d\n", eglGetError(), n); return 1; }
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint ctxAttr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext first = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttr);
    if (first == EGL_NO_CONTEXT) { printf("FAIL first context err=0x%x\n", eglGetError()); return 1; }
    EGLContext second = eglCreateContext(dpy, cfg, first, ctxAttr);
    if (second == EGL_NO_CONTEXT) { printf("FAIL shared context err=0x%x\n", eglGetError()); return 1; }
    printf("contexts ok\n");
    EGLBoolean ok = eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, second);
    printf("surfaceless makeCurrent = %d err=0x%x\n", ok, eglGetError());
    if (!ok) {
        EGLint pbAttr[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        EGLSurface pb = eglCreatePbufferSurface(dpy, cfg, pbAttr);
        printf("pbuffer = %p err=0x%x\n", (void*)pb, eglGetError());
        if (pb != EGL_NO_SURFACE) {
            ok = eglMakeCurrent(dpy, pb, pb, second);
            printf("pbuffer makeCurrent = %d err=0x%x\n", ok, eglGetError());
        }
    }
    if (ok) {
        printf("GL_VERSION = %s\n", glGetString(GL_VERSION));
        GLint u[1] = {0};
        glGetIntegerv(GL_MAX_VERTEX_UNIFORM_VECTORS, u);
        printf("maxVertexUniformVectors = %d glErr=0x%x\n", u[0], glGetError());
    }
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return 0;
}
