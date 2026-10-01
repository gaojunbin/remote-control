package com.junbingao.remotecontrol.android.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import com.junbingao.remotecontrol.core.state.TerminalSize
import com.junbingao.remotecontrol.core.state.TerminalTypeSize
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlin.math.roundToInt

/**
 * Termux's terminal view and a remote session, joined the way the iPhone's `TerminalHost`
 * coordinator joins SwiftTerm to the screen: bytes from the feed are drawn, bytes the person
 * types go out, every new size is reported once (clamped to what a device accepts, the core's
 * `TerminalSize`), and a pinch changes the type size by the core's `TerminalTypeSize.scaled`.
 *
 * It renders, selects, scrolls and produces key sequences; what to do with those bytes and what
 * the status line says belong to the screen around it.
 */
@SuppressLint("ClickableViewAccessibility")
internal class TerminalBridge(context: Context, private val feed: TerminalFeed, fontSize: Double) {
    var onSize: (TerminalSize) -> Unit = {}
    var onInput: (ByteArray) -> Unit = {}
    var onFontSize: (Double) -> Unit = {}

    val view = TerminalView(context, null)

    private val session = TerminalSession(null, SessionCallbacks(context) { view })
    private val density = context.resources.displayMetrics.density
    private var points = 0.0
    private var reported: TerminalSize? = null
    private var colors: IntArray? = null
    private var pinchBase: Double? = null
    private var pinchScale = 1.0
    private val writer: (ByteArray) -> Unit = { bytes -> session.feed(bytes) }

    init {
        session.setOutput(object : TerminalSession.Output {
            override fun onBytes(data: ByteArray, offset: Int, count: Int) {
                // The session reuses its encoding buffer, so what leaves is a copy.
                onInput(data.copyOfRange(offset, offset + count))
            }

            override fun onResize(columns: Int, rows: Int) {
                report(columns, rows)
            }
        })
        view.setTerminalViewClient(ViewCallbacks({ view }, ::pinched))
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        setFontSize(fontSize)
        view.attachSession(session)
        // A second finger coming down starts a pinch and the last one lifting ends it, which is
        // what the iPhone's pinch recogniser calls began and ended. The view still handles every
        // event itself.
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> beginPinch()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pinchBase = null
            }
            false
        }
        feed.writer = writer
    }

    /** The type size in points, which is density-independent pixels and ignores the font scale. */
    fun setFontSize(size: Double) {
        if (size == points || size <= 0) return
        points = size
        view.setTextSize((size * density).roundToInt())
    }

    /**
     * The app's ink on the app's surface, for the default text, the page behind it and the
     * cursor. Termux keeps its defaults in one scheme every emulator starts from and a reset
     * returns to, so the colours go there as well as into the live emulator; one terminal is on
     * screen at a time.
     */
    fun setColors(foreground: Int, background: Int, cursor: Int) {
        val wanted = intArrayOf(foreground, background, cursor)
        if (wanted.contentEquals(colors)) return
        colors = wanted
        val defaults = TerminalColors.COLOR_SCHEME.mDefaultColors
        defaults[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
        defaults[TextStyle.COLOR_INDEX_BACKGROUND] = background
        defaults[TextStyle.COLOR_INDEX_CURSOR] = cursor
        view.setBackgroundColor(background)
        view.mEmulator?.mColors?.let { current ->
            current.reset(TextStyle.COLOR_INDEX_FOREGROUND)
            current.reset(TextStyle.COLOR_INDEX_BACKGROUND)
            current.reset(TextStyle.COLOR_INDEX_CURSOR)
        }
        view.invalidate()
    }

    /**
     * Let go of the screen: nothing more is drawn, typed or reported through this bridge. The
     * feed is let go only if it still writes here, since a host that replaced this one may have
     * taken it over before this one was released.
     */
    fun release() {
        if (feed.writer === writer) feed.writer = null
        session.setOutput(null)
        view.setOnTouchListener(null)
    }

    /** One report per size, whichever of the first layout or a later change got there. */
    private fun report(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        val size = TerminalSize(cols = cols, rows = rows)
        if (size == reported) return
        reported = size
        feed.size = size
        onSize(size)
    }

    private fun beginPinch() {
        pinchBase = points
        pinchScale = 1.0
    }

    /** One step of a pinch, measured from the size the gesture began at. */
    private fun pinched(step: Float) {
        val base = pinchBase ?: points.also { beginPinch() }
        pinchScale *= step
        val size = TerminalTypeSize.scaled(base, by = pinchScale)
        if (size == points) return
        setFontSize(size)
        onFontSize(size)
    }
}
