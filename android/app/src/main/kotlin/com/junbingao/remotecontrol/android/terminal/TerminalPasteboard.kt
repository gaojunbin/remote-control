package com.junbingao.remotecontrol.android.terminal

import android.content.ClipboardManager
import android.content.Context

/**
 * What the clipboard holds, as bytes to type: the key bar's Paste. The phone is the only place
 * this is asked for, and nothing else in the app reads the clipboard.
 */
object TerminalPasteboard {
    /**
     * The clipboard's text as UTF-8, or null when there is none or it is larger than [maxBytes]
     * — the core's `TerminalLimits.maxInputBytes`, the most one input frame carries.
     */
    fun bytes(context: Context, maxBytes: Int): ByteArray? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= maxBytes) bytes else null
    }
}
