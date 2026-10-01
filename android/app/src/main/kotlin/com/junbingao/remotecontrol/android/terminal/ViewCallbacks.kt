package com.junbingao.remotecontrol.android.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * How the terminal view behaves on a phone, in the iPhone's terms: a tap on the shell brings the
 * keyboard up, a long press selects, a pinch changes the type size, and every key the person
 * types goes to the device as the emulator encodes it.
 *
 * Back stays Android's back — it leaves the screen, as the iPhone's back button does — rather
 * than being typed as Escape; the key bar carries Esc. Modifier keys are the hardware's alone:
 * the key bar's sticky Ctrl is the screen's, which rewrites the next bytes itself.
 */
internal class ViewCallbacks(
    private val view: () -> TerminalView?,
    private val onPinch: (Float) -> Unit,
) : TerminalViewClient {
    /**
     * The view multiplies each step of a pinch into the factor it hands here and keeps what this
     * returns. Returning one every time turns the value into the step alone, so the pinch is
     * measured from where the gesture began (see [TerminalBridge.pinched]).
     */
    override fun onScale(scale: Float): Float {
        onPinch(scale)
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        val terminal = view() ?: return
        terminal.requestFocus()
        terminal.context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /**
     * Text arrives from the keyboard as characters with suggestions and corrections off, which is
     * what a shell needs and what SwiftTerm asks the iPhone's keyboard for.
     */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
