package com.junbingao.remotecontrol.android.terminal

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View.MeasureSpec
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The terminal view and its remote session, driven the way the terminal screen drives them:
 * bytes in, typed bytes out, one report per size, and a pinch that steps the type size.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w402dp-h874dp-xxhdpi")
class TerminalBridgeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val feed = TerminalFeed()
    private val sizes = mutableListOf<TerminalGrid>()
    private val typed = mutableListOf<ByteArray>()
    private val pinchedTo = mutableListOf<Double>()

    // The core's `TerminalTypeSize.scaled`, which stage 2 passes in: rounded, and 8 to 24 points.
    private val bridge = TerminalBridge(context, feed, 12.0).apply {
        onSize = { cols, rows -> sizes += TerminalGrid(cols, rows) }
        onInput = { bytes -> typed += bytes }
        onFontSize = { points -> pinchedTo += points }
        scaledFontSize = { base, scale -> (base * scale).roundToInt().toDouble().coerceIn(8.0, 24.0) }
    }

    private fun layOut(width: Int = 1206, height: Int = 2000) {
        bridge.view.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        bridge.view.layout(0, 0, width, height)
    }

    private fun typedText(): String = typed.joinToString("") { String(it, Charsets.UTF_8) }

    @Test
    fun bytesFromTheShellAreDrawnWithTheirColours() {
        layOut()
        feed.write("hello\r\n\u001b[31mred\u001b[0m".toByteArray())
        val screen = bridge.view.mEmulator.screen
        assertEquals("hello\nred", screen.transcriptText)
        assertEquals("the escape sequence colours the word red", 1,
            TextStyle.decodeForeColor(screen.getStyleAt(1, 0)))
    }

    @Test
    fun bytesThatArriveBeforeTheFirstLayoutAreDrawnWhenItHappens() {
        feed.write("early\r\n".toByteArray())
        layOut()
        assertEquals("early", bridge.view.mEmulator.screen.transcriptText)
    }

    @Test
    fun typedTextAndKeysLeaveAsTheEmulatorEncodesThem() {
        layOut()
        val keyboard = bridge.view.onCreateInputConnection(EditorInfo())
        keyboard.commitText("ls", 1)
        bridge.view.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        bridge.view.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("ls\r\u001b[A", typedText())
    }

    @Test
    fun theEmulatorsOwnRepliesGoOutToo() {
        layOut()
        // A device status report: the shell asks where the cursor is and the emulator answers.
        feed.write("ab\u001b[6n".toByteArray())
        assertEquals("\u001b[1;3R", typedText())
    }

    @Test
    fun aSizeIsReportedOncePerSize() {
        layOut()
        assertEquals(1, sizes.size)
        val first = sizes.single()
        assertEquals(first, feed.size)

        // The same grid again, from a second layout and from the session itself, says nothing.
        layOut()
        bridge.view.mTermSession.updateSize(first.cols, first.rows, 1, 1)
        assertEquals(1, sizes.size)

        layOut(width = 900)
        assertEquals(2, sizes.size)
        assertTrue("a narrower view holds fewer columns", sizes.last().cols < first.cols)
        assertEquals(first.rows, sizes.last().rows)
        assertEquals(sizes.last(), feed.size)
    }

    @Test
    fun aPinchStepsTheTypeSizeFromWhereTheGestureBegan() {
        layOut()
        val atTwelve = sizes.last()
        bridge.view.mClient.onScale(1.5f)
        bridge.view.mClient.onScale(1.1f)
        assertEquals(listOf(18.0, 20.0), pinchedTo)
        assertTrue("larger type holds fewer columns", sizes.last().cols < atTwelve.cols)

        // The fingers lift; the next pinch is measured from 20 points, not from 12.
        val now = SystemClock.uptimeMillis()
        bridge.view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 10f, 10f, 0))
        bridge.view.mClient.onScale(0.5f)
        assertEquals(10.0, pinchedTo.last(), 0.0)

        // A step that lands on the size already shown changes nothing.
        val reports = pinchedTo.size
        bridge.view.mClient.onScale(1.02f)
        assertEquals(reports, pinchedTo.size)
    }

    @Test
    fun aHostThatTookTheFeedOverKeepsItWhenTheOldOneIsReleased() {
        val next = TerminalBridge(context, feed, 12.0)
        next.view.measure(
            MeasureSpec.makeMeasureSpec(1206, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(2000, MeasureSpec.EXACTLY),
        )
        next.view.layout(0, 0, 1206, 2000)
        bridge.release()
        feed.write("still here\r\n".toByteArray())
        assertEquals("still here", next.view.mEmulator.screen.transcriptText)
    }

    @Test
    fun aReleasedBridgeDrawsAndSendsNothing() {
        layOut()
        bridge.release()
        feed.write("after\r\n".toByteArray())
        bridge.view.mTermSession.writeCodePoint(false, 'x'.code)
        assertEquals("", bridge.view.mEmulator.screen.transcriptText)
        assertTrue(typed.isEmpty())
    }
}
