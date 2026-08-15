package com.pocketrealm.client

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/** Stable persisted selection; runtime protocol values remain independent. */
enum class ArmClientRenderer(
    val id: String,
    val runtimeRenderer: String,
    val label: String,
    val summary: String,
    val experimental: Boolean,
    val packaged: Boolean,
) {
    DXVK(
        id = "dxvk",
        runtimeRenderer = "dxvk",
        label = "DXVK (Vulkan)",
        summary = "Default renderer with the selected packaged Vulkan driver and DXVK version.",
        experimental = false,
        packaged = true,
    ),
    LEGACY_GLADIO(
        id = "legacy-gladio",
        runtimeRenderer = "opengl",
        label = "Legacy OpenGL (Gladio)",
        summary = "Experimental desktop OpenGL-to-Android GLES bridge for devices where Vulkan is unsuitable.",
        experimental = true,
        packaged = true,
    ),
    MESA_VIRGL(
        id = "mesa-virgl",
        runtimeRenderer = "virgl",
        label = "Mesa VirGL",
        summary = "Experimental Mesa virpipe client with the source-matched Android VirGL GLES server.",
        experimental = true,
        packaged = true,
    ),
}

data class ArmRendererAvailability(
    val available: Boolean,
    val reason: String,
)

data class GladioCapability(
    val declaredGlesVersion: Int,
    val eglMajor: Int,
    val eglMinor: Int,
    val actualGlesMajor: Int,
    val actualGlesMinor: Int,
    val maxVertexUniformVectors: Int,
    val sharedSurfacelessContext: Boolean,
) {
    val declaredGlesLabel: String
        get() = "${declaredGlesVersion shr 16}.${declaredGlesVersion and 0xffff}"

    val actualGlesLabel: String
        get() = "$actualGlesMajor.$actualGlesMinor"
}

/** Closed ARM renderer catalog. No arbitrary path, URL, or binary can be selected. */
object ArmClientRendererCatalog {
    const val SELECTION_SCHEMA = 2
    const val DEFAULT_ID = "dxvk"
    const val GLADIO_PACKAGE_ID = "box64-gladio-eaa2a8d"
    const val GLADIO_BUILD_ID = "gladio-eaa2a8d-arm64-glibc-gles-v5"
    const val GLADIO_CLIENT_ASSET =
        "arm-translated/renderer-packages/$GLADIO_PACKAGE_ID/libGL.so.1"
    const val GLADIO_CLIENT_SHA256 =
        "1a634a5d9259a87188979a29d93b098edf09e8ee1639b7fb05e446e31327e865"
    const val GLADIO_SERVER_BUILD_ID = "gladio-eaa2a8d-android-gles-server-1ffa75ce"
    const val GLADIO_SERVER_SHA256 =
        "1ffa75ce4f2dd45b85feb83c5f5db5208a496d5a89ef7a434833cfb8a9d76a28"
    const val VIRGL_PACKAGE_ID = "box64-virgl-23.1.9"
    const val VIRGL_ENVIRONMENT_ID = "virpipe-v0-gl31-noerror-ext-v1"
    const val VIRGL_BUILD_ID =
        "mesa-virpipe-23.1.9-ca3d735-virpipe-v0-gl31-noerror-ext-v1"
    const val VIRGL_CLIENT_ASSET =
        "arm-translated/renderer-packages/$VIRGL_PACKAGE_ID/libGL.so.1"
    const val VIRGL_CLIENT_SHA256 =
        "531e3dc809281feadcc2120abc6d9f88025d92d567ac32eed9c376bd9e4e04f6"
    const val VIRGL_SERVER_BUILD_ID = "virglrenderer-ca3d735-android-gles3-c6895de1"
    const val VIRGL_SERVER_SHA256 =
        "c6895de1a5407fc60f3098e4d650689fb6c45e89126560cce8867d98e56286ae"
    const val MIN_GLES_MAJOR = 3
    const val MIN_GLES_MINOR = 0
    const val MIN_VERTEX_UNIFORM_VECTORS = 256

    fun entries(): List<ArmClientRenderer> = ArmClientRenderer.entries

    fun find(id: String?): ArmClientRenderer? = ArmClientRenderer.entries.firstOrNull {
        it.id == id
    }

    /** Old renderer values never reactivate an experimental lane automatically. */
    fun resolvePersisted(id: String?, schema: Int): ArmClientRenderer =
        if (schema == SELECTION_SCHEMA) find(id) ?: ArmClientRenderer.DXVK
        else ArmClientRenderer.DXVK

    fun requireSelection(id: String): ArmClientRenderer = requireNotNull(find(id)) {
        "unknown ARM client renderer: $id"
    }

    fun availability(
        renderer: ArmClientRenderer,
        gles: Result<GladioCapability>? = null,
        abi: String = "arm64-v8a",
    ): ArmRendererAvailability = when (renderer) {
        ArmClientRenderer.DXVK -> ArmRendererAvailability(
            true,
            "Default Vulkan route; the selected DXVK and Vulkan packages are checked separately.",
        )
        // On-device qualification lane: experimental renderers remain exact
        // selections with no silent fallback, but the device capability probe
        // is informational only. A probe that fails or times out (observed on
        // the Retroid Pocket 6) must not hide the lane from testing; launch
        // still fails closed on real packaging/runtime errors.
        ArmClientRenderer.LEGACY_GLADIO -> when {
            abi != "arm64-v8a" -> ArmRendererAvailability(
                false,
                "Legacy OpenGL is packaged only for ARM64 devices; this build is ${abi.ifBlank { "unknown ABI" }}.",
            )
            else -> testingSelection("Legacy OpenGL", gles, ::evaluateGladio)
        }
        ArmClientRenderer.MESA_VIRGL -> when {
            abi != "arm64-v8a" -> ArmRendererAvailability(
                false,
                "Mesa VirGL is packaged only for ARM64 devices; this build is ${abi.ifBlank { "unknown ABI" }}.",
            )
            else -> testingSelection("Mesa VirGL", gles, ::evaluateVirgl)
        }
    }

    private fun testingSelection(
        label: String,
        gles: Result<GladioCapability>?,
        evaluate: (GladioCapability) -> ArmRendererAvailability,
    ): ArmRendererAvailability = when {
        gles == null -> ArmRendererAvailability(
            true,
            "$label is selectable for on-device testing; EGL/GLES check still running. " +
                "No renderer fallback is used.",
        )
        gles.isFailure -> ArmRendererAvailability(
            true,
            "$label is selectable for on-device testing; EGL/GLES not verified (" +
                (gles.exceptionOrNull()?.message ?: "capability probe failed") +
                "). No renderer fallback is used.",
        )
        else -> ArmRendererAvailability(
            true,
            evaluate(requireNotNull(gles.getOrNull())).reason,
        )
    }

    fun requireRuntimeRenderer(
        runtimeRenderer: String,
        gles: Result<GladioCapability>? = null,
        abi: String = "arm64-v8a",
    ): ArmClientRenderer {
        val renderer = ArmClientRenderer.entries.firstOrNull {
            it.runtimeRenderer == runtimeRenderer
        } ?: throw IllegalArgumentException("unsupported ARM renderer: $runtimeRenderer")
        require(renderer.packaged) { "${renderer.label} is not packaged" }
        if (renderer != ArmClientRenderer.DXVK) {
            val availability = availability(renderer, gles, abi)
            require(availability.available) { availability.reason }
        }
        return renderer
    }

    fun evaluateGladio(capability: GladioCapability): ArmRendererAvailability {
        val actualVersionOk = capability.actualGlesMajor > MIN_GLES_MAJOR ||
            (capability.actualGlesMajor == MIN_GLES_MAJOR &&
                capability.actualGlesMinor >= MIN_GLES_MINOR)
        return when {
            capability.eglMajor < 1 ||
                (capability.eglMajor == 1 && capability.eglMinor < 4) ->
                ArmRendererAvailability(false, "Legacy OpenGL requires EGL 1.4 or newer.")
            !actualVersionOk -> ArmRendererAvailability(
                false,
                "Legacy OpenGL requires OpenGL ES $MIN_GLES_MAJOR.$MIN_GLES_MINOR or newer; " +
                    "the verified context is ${capability.actualGlesLabel}.",
            )
            !capability.sharedSurfacelessContext -> ArmRendererAvailability(
                false,
                "Legacy OpenGL requires shared surfaceless EGL contexts for the GLX bridge.",
            )
            capability.maxVertexUniformVectors < MIN_VERTEX_UNIFORM_VECTORS ->
                ArmRendererAvailability(
                    false,
                    "Legacy OpenGL requires at least $MIN_VERTEX_UNIFORM_VECTORS vertex uniform vectors; " +
                        "this device reports ${capability.maxVertexUniformVectors}.",
                )
            else -> ArmRendererAvailability(
                true,
                "Experimental and not device-qualified: EGL ${capability.eglMajor}.${capability.eglMinor}, " +
                    "OpenGL ES ${capability.actualGlesLabel}. No renderer fallback is used.",
            )
        }
    }

    fun evaluateVirgl(capability: GladioCapability): ArmRendererAvailability {
        val actualVersionOk = capability.actualGlesMajor > MIN_GLES_MAJOR ||
            (capability.actualGlesMajor == MIN_GLES_MAJOR &&
                capability.actualGlesMinor >= MIN_GLES_MINOR)
        return when {
            capability.eglMajor < 1 ||
                (capability.eglMajor == 1 && capability.eglMinor < 4) ->
                ArmRendererAvailability(false, "Mesa VirGL requires EGL 1.4 or newer.")
            !actualVersionOk -> ArmRendererAvailability(
                false,
                "Mesa VirGL requires OpenGL ES 3.0 or newer; the verified context is " +
                    capability.actualGlesLabel + ".",
            )
            !capability.sharedSurfacelessContext -> ArmRendererAvailability(
                false,
                "Mesa VirGL requires shared surfaceless EGL contexts.",
            )
            else -> ArmRendererAvailability(
                true,
                "Experimental and not device-qualified: EGL ${capability.eglMajor}.${capability.eglMinor}, " +
                    "OpenGL ES ${capability.actualGlesLabel}. No renderer fallback is used.",
            )
        }
    }
}

/** Performs the same two-context, shared, surfaceless EGL shape used by Gladio GLX.
 *  Some device EGL stacks (observed on the Retroid Pocket 6) can block a
 *  cross-thread EGL probe indefinitely inside the app process, so the EGL work
 *  runs under a hard watchdog: callers either get a capability or a fast
 *  timeout failure. A timed-out probe thread is abandoned parked; callers
 *  treat timeout as "not verified", never as a hang. */
object AndroidGladioCapabilityProbe {
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    private const val PROBE_TIMEOUT_MS = 3_000L

    fun probe(context: Context): GladioCapability {
        val manager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val declared = manager.deviceConfigurationInfo.reqGlEsVersion
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            return executor.submit<GladioCapability> { probeEgl(declared) }
                .get(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("EGL capability probe timed out on this device", e)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause as? Exception ?: e
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeEgl(declared: Int): GladioCapability {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL display is unavailable" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) {
            "EGL initialization failed (0x${EGL14.eglGetError().toString(16)})"
        }
        var first = EGL14.EGL_NO_CONTEXT
        var second = EGL14.EGL_NO_CONTEXT
        try {
            check(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
                "EGL OpenGL ES API is unavailable"
            }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val configAttributes = intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(
                display, configAttributes, 0, configs, 0, 1, count, 0,
            ) && count[0] == 1 && configs[0] != null) {
                "No RGBA8 OpenGL ES 3 EGL configuration is available"
            }
            val config = checkNotNull(configs[0])
            val contextAttributes = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE,
            )
            first = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0,
            )
            check(first != EGL14.EGL_NO_CONTEXT) { "Primary OpenGL ES 3 context creation failed" }
            second = EGL14.eglCreateContext(display, config, first, contextAttributes, 0)
            check(second != EGL14.EGL_NO_CONTEXT) { "Shared OpenGL ES 3 context creation failed" }
            val surfaceless = EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, second,
            )
            check(surfaceless) { "Shared surfaceless EGL context is unavailable" }
            val parsed = parseGlesVersion(GLES20.glGetString(GLES20.GL_VERSION).orEmpty())
            val uniforms = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_UNIFORM_VECTORS, uniforms, 0)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) {
                "OpenGL ES capability query failed"
            }
            return GladioCapability(
                declaredGlesVersion = declared,
                eglMajor = version[0],
                eglMinor = version[1],
                actualGlesMajor = parsed.first,
                actualGlesMinor = parsed.second,
                maxVertexUniformVectors = uniforms[0],
                sharedSurfacelessContext = true,
            )
        } finally {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (second != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, second)
            if (first != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, first)
            // The default EGLDisplay is process-global and may already own the
            // live XServerView share root. Never terminate it from a probe.
        }
    }

    internal fun parseGlesVersion(value: String): Pair<Int, Int> {
        val match = Regex("OpenGL ES(?:-CM)?\\s+(\\d+)\\.(\\d+)").find(value)
            ?: error("OpenGL ES version string is unavailable")
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }
}
