package com.junbingao.remotecontrol.android.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView

/**
 * What the emulator tells its owner. The iPhone's coordinator answers the same questions for
 * SwiftTerm: redraw when the screen changes, copy what the person selected (or what the shell
 * asked to put on the clipboard) and paste on request; the title, the bell and the process are
 * nobody's business here.
 *
 * Nothing is logged. Terminal output carries whatever the shell prints, and the app writes no
 * content to a log, so the emulator's own diagnostics — which quote escape sequences it did not
 * recognise — are dropped rather than passed on.
 */
internal class SessionCallbacks(
    private val context: Context,
    private val view: () -> TerminalView?,
) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        view()?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {}

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        clipboard()?.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return
        session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {
        view()?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    /** Null keeps the emulator's own cursor, the block SwiftTerm draws too. */
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}

    private fun clipboard(): ClipboardManager? = context.getSystemService(ClipboardManager::class.java)
}
