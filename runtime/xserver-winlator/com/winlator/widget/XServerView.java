package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private GLRenderer renderer;

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        setEGLContextFactory(new EGLContextFactory() {
            @Override
            public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
                int[] attributes = {0x3098 /* EGL_CONTEXT_CLIENT_VERSION */, 3, EGL10.EGL_NONE};
                return egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attributes);
            }

            @Override
            public void destroyContext(EGL10 egl, EGLDisplay display, EGLContext context) {
                GLRenderer current = renderer;
                if (current != null) current.onEglContextDestroyed();
                if (!egl.eglDestroyContext(display, context)) {
                    throw new RuntimeException("eglDestroyContext failed: 0x" +
                            Integer.toHexString(egl.eglGetError()));
                }
            }
        });
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    /** Clear the shared-context registry only when the host is permanently closed. */
    public void releaseRenderer() {
        renderer.onHostDestroyed();
    }
}
