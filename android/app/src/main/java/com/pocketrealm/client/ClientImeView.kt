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
 * NOT contain or parent the XServerView — it is an independent overlay.
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
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.actionId = EditorInfo.IME_ACTION_DONE
        outAttrs.actionLabel = "Send"
        onImeOpened()
        return ClientInputConnection(this, contractProvider, generationProvider, onImeClosed)
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
        private val onImeClosed: () -> Unit,
    ) : BaseInputConnection(view, false) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            contractProvider().imeCommit(text.toString(), generationProvider())
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            contractProvider().imeDelete(beforeLength, generationProvider())
            return true
        }

        override fun finishComposingText(): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    }
}
