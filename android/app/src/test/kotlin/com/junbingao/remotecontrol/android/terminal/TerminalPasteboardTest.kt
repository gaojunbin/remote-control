package com.junbingao.remotecontrol.android.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The key bar's Paste: the clipboard's text as bytes, and nothing past one input frame's cap. */
@RunWith(AndroidJUnit4::class)
class TerminalPasteboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun theClipboardsTextIsTypedAsUtf8() {
        clipboard.setPrimaryClip(ClipData.newPlainText(null, "echo 你好"))
        assertArrayEquals("echo 你好".toByteArray(Charsets.UTF_8), TerminalPasteboard.bytes(context, 64))
    }

    @Test
    fun textLargerThanTheCapIsNotTypedAtAll() {
        clipboard.setPrimaryClip(ClipData.newPlainText(null, "echo 你好"))
        // Eleven bytes: the two characters are three each.
        assertNull(TerminalPasteboard.bytes(context, 10))
        assertArrayEquals("echo 你好".toByteArray(Charsets.UTF_8), TerminalPasteboard.bytes(context, 11))
    }

    @Test
    fun anEmptyClipboardTypesNothing() {
        clipboard.clearPrimaryClip()
        assertNull(TerminalPasteboard.bytes(context, 64))
        clipboard.setPrimaryClip(ClipData.newPlainText(null, ""))
        assertNull(TerminalPasteboard.bytes(context, 64))
    }
}
