package com.pocketrealm.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pocketrealm.client.IntegratedClientDisplay
import com.pocketrealm.log.AppLog
import com.pocketrealm.storage.Settings
import com.pocketrealm.ui.theme.PocketRealmTheme

/**
 * Dedicated full-screen gameplay shell. Setup/recovery stays in [MainActivity];
 * this activity may attach only the exact supervisor-owned display generation
 * named by its private intent.
 */
class ClientActivity : ComponentActivity() {
    // Observable so a singleTask instance retained for its EGL context adopts
    // a new launch intent's generation and recomposes against it.
    private val expectedGenerationState = mutableLongStateOf(NO_GENERATION)
    private var expectedGeneration: Long
        get() = expectedGenerationState.longValue
        set(value) { expectedGenerationState.longValue = value }
    private var imeWasVisible = false

    @Suppress("DEPRECATION") // Bar colors remain required on the API 26-34 compatibility path.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expectedGeneration = intent.getLongExtra(EXTRA_GENERATION, NO_GENERATION)
        if (expectedGeneration == NO_GENERATION ||
            IntegratedClientDisplay.currentHost(expectedGeneration) == null
        ) {
            // This activity lives in its own task affinity, so a bare finish()
            // can strand the user on the launcher instead of the shell.
            returnToShell("stale launch intent")
            finish()
            return
        }

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        installImmersiveReapplyHooks()
        hideSystemUi()
        onBackPressedDispatcher.addCallback(this) {
            val activeHost = IntegratedClientDisplay.currentHost(expectedGeneration)
            if (activeHost?.isPointerCaptured == true) {
                activeHost.setPointerCapture(false)
                return@addCallback
            }
            // Do not finish/destroy the GLSurfaceView while Wine owns GLX
            // contexts sharing its EGL root. Move the existing home activity
            // to the front and retain this exact client/activity instance for
            // Enter game. Ordinary Surface loss then preserves the EGLContext.
            startActivity(
                Intent(this@ClientActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        val settings = Settings(applicationContext)
        setContent {
            val host by IntegratedClientDisplay.host.collectAsState()
            val expected by expectedGenerationState
            val settingsSnapshot by settings.flow.collectAsState(initial = Settings.Snapshot())
            val current = host?.takeIf { it.generation == expected }

            LaunchedEffect(current, host) {
                if (current == null) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    // The display generation is gone (save & exit, crash
                    // teardown). Returning to the shell explicitly avoids the
                    // separate task affinity dumping the user on the launcher.
                    returnToShell("display generation gone")
                    finish()
                }
            }

            PocketRealmTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .testTag("fullscreen-client-surface"),
                ) {
                    current?.let { display ->
                        key(display.generation) {
                            AndroidView(
                                factory = {
                                    check(display.container.parent == null) {
                                        "client display generation is already attached"
                                    }
                                    display.container
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (!settingsSnapshot.inputSafeMode) {
                            // Insets affect controls only. The renderer remains
                            // edge-to-edge beneath cutouts and transient bars.
                            TouchOverlay(
                                host = display,
                                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (IntegratedClientDisplay.currentHost(expectedGeneration) != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requested = intent.getLongExtra(EXTRA_GENERATION, NO_GENERATION)
        if (requested != NO_GENERATION &&
            IntegratedClientDisplay.currentHost(requested) != null
        ) {
            // A retained singleTask instance pinned to a superseded generation
            // must adopt the generation named by the new launch intent instead
            // of eating the reorder and failing closed on the stale value.
            AppLog.i(TAG, "adopting launch generation $requested (was $expectedGeneration)")
            expectedGeneration = requested
        } else {
            AppLog.w(TAG, "rejected launch generation $requested; keeping $expectedGeneration")
        }
    }

    /**
     * This activity sits in its own task affinity, so finishing after a stale
     * launch or a released display can leave the system on the launcher task
     * instead of the shell. Always hand the user back to MainActivity.
     */
    private fun returnToShell(reason: String) {
        AppLog.i(TAG, "returning to shell: $reason")
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    override fun onResume() {
        super.onResume()
        IntegratedClientDisplay.currentHost(expectedGeneration)?.onResume()
        hideSystemUi()
    }

    override fun onPause() {
        IntegratedClientDisplay.currentHost(expectedGeneration)?.onPause()
        super.onPause()
    }

    override fun onStop() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    /**
     * Route gameplay keys at the Activity boundary so a Compose overlay tap,
     * pointer-capture transition, or hidden IME focus target cannot strand the
     * RP6 buttons outside the XServerView. System keys still return false from
     * the host and retain Android ownership.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val host = IntegratedClientDisplay.currentHost(expectedGeneration)
        if (host != null && host.dispatchKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val host = IntegratedClientDisplay.currentHost(expectedGeneration)
        if (host != null && host.dispatchKey(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val host = IntegratedClientDisplay.currentHost(expectedGeneration)
        if (host != null && host.dispatchKey(event)) return true
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    /** Joystick motion follows the same focus-independent gameplay route. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) {
            val host = IntegratedClientDisplay.currentHost(expectedGeneration)
            if (host != null && host.dispatchGamepad(event)) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun installImmersiveReapplyHooks() {
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                window.decorView.post(::hideSystemUi)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !imeVisible) {
                IntegratedClientDisplay.currentHost(expectedGeneration)?.onSoftImeDismissed()
                view.post(::hideSystemUi)
            }
            imeWasVisible = imeVisible
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // Retain immersive-sticky behavior on API 26-29 as well as the modern
        // insets controller path used by the RP6.
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    companion object {
        private const val TAG = "ClientActivity"
        private const val EXTRA_GENERATION = "com.pocketrealm.extra.CLIENT_GENERATION"
        private const val NO_GENERATION = -1L

        fun intent(context: Context, generation: Long): Intent =
            Intent(context, ClientActivity::class.java)
                .putExtra(EXTRA_GENERATION, generation)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}
