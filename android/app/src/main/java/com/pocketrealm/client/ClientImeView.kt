package com.pocketrealm.client

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * A transparent zero-size [View] that advertises itself as a text editor so
 * Android's [android.view.inputmethod.InputMethodManager] will show the soft
 * IME for WoW chat / account-field entry.
 *
 * The winlator [com.winlator.widget.XServerView] is a `GLSurfaceView` that
 * cannot be modified and does not implement the text-editor contract. This view
 * is focused by [ClientDisplayHost.showIme] to become the IME target. It does
 * It is attached as a one-pixel sibling of the XServerView in the host's
 * shared display container, so Android can focus it without replacing the
 * rendered surface.
 *
 * The [InputConnection] routes `commitText` through the [InputContract]'s
 * generation-gated `imeCommit`, preserving the verified input boundary. No text
 * is injected outside the contract.
 *
 * @param contractProvider returns the active contract for the current generation
 * @param generationProvider returns the active generation id
 * @param onImeOpened called when the IME signals it is opening (releases held input)
 * @param onImeClosed called when the IME signals it is closing
 */
class ClientImeView(
    context: Context,
    private val contractProvider: () -> InputContract,
    private val generationProvider: () -> Long,
    private val beforeImeInput: () -> Unit,
    private val onImeOpened: () -> Unit,
    private val onImeClosed: () -> Unit,
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        visibility = VISIBLE
        // Zero-size overlay: it only needs to be focusable for the IME, not visible.
        layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_DONE
        outAttrs.actionId = EditorInfo.IME_ACTION_DONE
        outAttrs.actionLabel = "Send"
        beforeImeInput()
        onImeOpened()
        return ClientInputConnection(this, contractProvider, generationProvider, beforeImeInput)
    }

    /**
     * Android does not call an InputConnection method when the user dismisses
     * the soft keyboard with Back.  Observe that pre-IME event on the attached
     * editor so the contract leaves IME-active mode and the host can restore
     * normal game focus.  This is deliberately limited to the Back key; a
     * composing-text finish is not the same thing as closing the IME.
     */
    override fun onKeyPreIme(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            event.action == android.view.KeyEvent.ACTION_UP) {
            onImeClosed()
        }
        return super.onKeyPreIme(keyCode, event)
    }

    /**
     * The [BaseInputConnection] that bridges Android IME `commitText` /
     * `deleteSurroundingText` to the [InputContract]. Every committed character
     * is generation-gated and routed through the contract's deterministic
     * keyboard path; no text bypasses the contract.
     */
    private class ClientInputConnection(
        private val view: ClientImeView,
        private val contractProvider: () -> InputContract,
        private val generationProvider: () -> Long,
        private val beforeImeInput: () -> Unit,
    ) : BaseInputConnection(view, false) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            beforeImeInput()
            val result = contractProvider().imeCommit(text.toString(), generationProvider())
            if (!result.allAccepted) {
                android.util.Log.w(TAG, "IME commit rejected codepoints=${result.rejected}")
                val codepoints = result.rejected.take(4).joinToString(", ") { "U+%04X".format(it) }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        view.context,
                        "Unsupported text character(s): $codepoints",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            return result.allAccepted
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            beforeImeInput()
            contractProvider().imeDelete(beforeLength, generationProvider())
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            if (actionCode !in setOf(
                    EditorInfo.IME_ACTION_DONE,
                    EditorInfo.IME_ACTION_GO,
                    EditorInfo.IME_ACTION_NEXT,
                    EditorInfo.IME_ACTION_SEARCH,
                    EditorInfo.IME_ACTION_SEND,
                )) return false
            beforeImeInput()
            val now = android.os.SystemClock.uptimeMillis()
            val down = android.view.KeyEvent(
                now, now, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_ENTER, 0,
            )
            val up = android.view.KeyEvent(
                now, now + 1, android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_ENTER, 0,
            )
            val contract = contractProvider()
            return contract.key(InputContract.IME_SOURCE, down, generationProvider()) &&
                contract.key(InputContract.IME_SOURCE, up, generationProvider())
        }

        override fun finishComposingText(): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    }

    private companion object { const val TAG = "ClientImeView" }
}
