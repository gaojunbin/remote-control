package com.junbingao.remotecontrol.win.terminal

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.NaturalLine
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.SystemFace
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithFont
import com.junbingao.remotecontrol.win.design.hex
import com.junbingao.remotecontrol.win.platform.TerminalFeed
import com.junbingao.remotecontrol.win.platform.TerminalTheme
import org.jetbrains.skia.Font
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * What a render draws where the emulator goes. The emulator is a Swing component and a render has
 * no window to put one in, so this stands in for it: it measures the grid the emulator would lay
 * out in the same box, in the emulator's face and size, and draws what the shell wrote as lines of
 * text with the unfocused cursor after them — characters, line ends, a backspace and a full
 * reset, which is everything the demo's and the mock's shells write. The app never draws it.
 */
@Composable
internal fun TerminalStandIn(feed: TerminalFeed, onSize: (cols: Int, rows: Int) -> Unit, modifier: Modifier) {
    val grid = remember { StandInGrid() }
    val reportSize by rememberUpdatedState(onSize)
    val density = LocalDensity.current
    DisposableEffect(feed) {
        feed.writer = grid::write
        onDispose { feed.writer = null }
    }
    Box(
        modifier.onSizeChanged { size ->
            val cols = (size.width / density.density / StandInGrid.cellWidth).toInt()
            val rows = (size.height / density.density / StandInGrid.rowHeight).toInt()
            if (cols > 0 && rows > 0) reportSize(cols, rows)
        },
    ) {
        WithFont(TerminalTheme.fontSize, mono = true) {
            VStack(spacing = 0.dp, alignment = Alignment.Start) {
                for (line in grid.lines) Text(line, color = Palette.ink, softWrap = false)
            }
        }
        Box(
            Modifier
                .offset(x = (grid.column * StandInGrid.cellWidth).dp, y = (grid.row * StandInGrid.rowHeight).dp)
                .size(StandInGrid.cellWidth.dp, StandInGrid.rowHeight.dp)
                .border(1.5.dp, unfocusedCursor),
        )
    }
}

/** The emulator's cursor while it does not have the keyboard: the block's outline, in a faint ink. */
private val unfocusedCursor = Color.hex(0xCECECD)

/** The shell's screen as plain lines: what was written where, and where the cursor is. */
private class StandInGrid {
    var lines by mutableStateOf(listOf(""))
        private set
    var row by mutableStateOf(0)
        private set
    var column by mutableStateOf(0)
        private set

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteBuffer.allocate(0)
    private var escaped = false

    /** Bytes from the device; a character split across two writes waits for its last byte. */
    fun write(bytes: ByteArray) {
        val input = ByteBuffer.allocate(pending.remaining() + bytes.size).put(pending).put(bytes).flip()
        val output = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, output, false)
        pending = ByteBuffer.allocate(input.remaining()).put(input).flip()
        output.flip()
        val next = lines.toMutableList()
        while (output.hasRemaining()) apply(output.get(), next)
        lines = next
    }

    private fun apply(character: Char, screen: MutableList<String>) {
        when {
            escaped -> {
                escaped = false
                // RIS, the full reset the page asks for before it draws a scrollback.
                if (character == 'c') {
                    screen.clear()
                    screen += ""
                    row = 0
                    column = 0
                }
            }
            character == '\u001b' -> escaped = true
            character == '\r' -> column = 0
            character == '\n' -> {
                row += 1
                while (screen.size <= row) screen += ""
            }
            character == '\b' -> column = maxOf(0, column - 1)
            character < ' ' -> Unit
            else -> {
                val line = screen[row].padEnd(column)
                screen[row] = line.substring(0, column) + character + line.substring(minOf(line.length, column + 1))
                column += 1
            }
        }
    }

    companion object {
        /** One cell of the grid: a character of the emulator's face as these lines draw it, and a line of it. */
        val cellWidth: Float by lazy {
            val face = SystemFace.face(TerminalTheme.fontSize, FontWeight.Normal, mono = true)
            Font(face.typeface, TerminalTheme.fontSize).measureTextWidth("W") + face.tracking
        }
        val rowHeight: Float by lazy { NaturalLine.of(TerminalTheme.fontSize, FontWeight.Normal, mono = true).height }
    }
}
