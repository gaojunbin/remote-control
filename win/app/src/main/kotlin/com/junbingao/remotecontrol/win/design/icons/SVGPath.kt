package com.junbingao.remotecontrol.win.design.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.PathNode

/**
 * SVG path data, read into Compose path nodes: every command of the `d` attribute (M L H V C S Q
 * T A Z, absolute and relative) with the shorthand SVG allows — implicit repeats, numbers run
 * together (`1.5.5`, `2-3`) and arc flags written without a separator (`a1 1 0 011 1`). The
 * icons and the agent logos are the web's own SVG, so the app draws them from the same data, and
 * arcs become the curves the Mac's reader makes of them (`SVGArc`).
 */
object SVGPath {
    fun parse(data: String): List<PathNode> {
        val reader = Reader(data)
        val builder = Builder()
        var command = 0.toChar()
        while (true) {
            val next = reader.nextCommand(command) ?: break
            command = next
            builder.apply(command, reader)
            // After a moveto, further coordinate pairs are linetos.
            if (command == 'M') command = 'L'
            if (command == 'm') command = 'l'
            if (reader.failed) break
        }
        return builder.nodes
    }

    /** A `points` attribute: `x,y x,y …`. */
    fun points(list: String): List<Offset> {
        val reader = Reader(list)
        val points = mutableListOf<Offset>()
        while (true) {
            val x = reader.number() ?: break
            val y = reader.number() ?: break
            points += Offset(x, y)
        }
        return points
    }
}

private class Reader(private val text: String) {
    private var index = 0
    var failed = false
        private set

    private fun skipSeparators() {
        while (index < text.length && text[index].let { it == ' ' || it == ',' || it == '\n' || it == '\r' || it == '\t' }) index++
    }

    /** The next command letter, or the current one again when numbers follow. */
    fun nextCommand(current: Char): Char? {
        skipSeparators()
        if (index >= text.length) return null
        val c = text[index]
        if (c in COMMANDS) {
            index++
            return c
        }
        if (current == 0.toChar() || current == 'Z' || current == 'z') {
            failed = true
            return null
        }
        return current
    }

    fun number(): Float? {
        skipSeparators()
        val start = index
        if (index < text.length && (text[index] == '-' || text[index] == '+')) index++
        var digits = false
        while (index < text.length && text[index].isAsciiDigit()) { index++; digits = true }
        if (index < text.length && text[index] == '.') {
            index++
            while (index < text.length && text[index].isAsciiDigit()) { index++; digits = true }
        }
        if (digits && index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            var probe = index + 1
            if (probe < text.length && (text[probe] == '-' || text[probe] == '+')) probe++
            if (probe < text.length && text[probe].isAsciiDigit()) {
                index = probe
                while (index < text.length && text[index].isAsciiDigit()) index++
            }
        }
        val value = if (digits) text.substring(start, index).toDoubleOrNull() else null
        if (value == null) {
            index = start
            return null
        }
        return value.toFloat()
    }

    /** An arc flag: a single 0 or 1, which may touch the next number. */
    fun flag(): Boolean? {
        skipSeparators()
        if (index >= text.length || (text[index] != '0' && text[index] != '1')) return null
        return text[index++] == '1'
    }

    fun point(): Offset? {
        val x = number() ?: return null
        val y = number() ?: return null
        return Offset(x, y)
    }

    fun fail() {
        failed = true
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    companion object {
        const val COMMANDS = "MmLlHhVvCcSsQqTtAaZz"
    }
}

private class Builder {
    val nodes = mutableListOf<PathNode>()
    private var current = Offset.Zero
    private var start = Offset.Zero

    /** The last control point of a C/S or Q/T, for the reflection S and T use. */
    private var lastCubic: Offset? = null
    private var lastQuad: Offset? = null

    fun apply(command: Char, reader: Reader) {
        val relative = command.isLowerCase()
        val base = if (relative) current else Offset.Zero
        fun offset(p: Offset) = Offset(p.x + base.x, p.y + base.y)
        var cubic: Offset? = null
        var quad: Offset? = null
        when (command.lowercaseChar()) {
            'm' -> {
                val p = reader.point() ?: return reader.fail()
                current = offset(p)
                start = current
                nodes += PathNode.MoveTo(current.x, current.y)
            }
            'l' -> {
                val p = reader.point() ?: return reader.fail()
                current = offset(p)
                nodes += PathNode.LineTo(current.x, current.y)
            }
            'h' -> {
                val x = reader.number() ?: return reader.fail()
                current = Offset(if (relative) current.x + x else x, current.y)
                nodes += PathNode.LineTo(current.x, current.y)
            }
            'v' -> {
                val y = reader.number() ?: return reader.fail()
                current = Offset(current.x, if (relative) current.y + y else y)
                nodes += PathNode.LineTo(current.x, current.y)
            }
            'c' -> {
                val c1 = reader.point() ?: return reader.fail()
                val c2 = reader.point() ?: return reader.fail()
                val p = reader.point() ?: return reader.fail()
                val a = offset(c1)
                val b = offset(c2)
                val end = offset(p)
                nodes += PathNode.CurveTo(a.x, a.y, b.x, b.y, end.x, end.y)
                cubic = b
                current = end
            }
            's' -> {
                val c2 = reader.point() ?: return reader.fail()
                val p = reader.point() ?: return reader.fail()
                val a = lastCubic?.let { Offset(2 * current.x - it.x, 2 * current.y - it.y) } ?: current
                val b = offset(c2)
                val end = offset(p)
                nodes += PathNode.CurveTo(a.x, a.y, b.x, b.y, end.x, end.y)
                cubic = b
                current = end
            }
            'q' -> {
                val c = reader.point() ?: return reader.fail()
                val p = reader.point() ?: return reader.fail()
                val control = offset(c)
                val end = offset(p)
                nodes += PathNode.QuadTo(control.x, control.y, end.x, end.y)
                quad = control
                current = end
            }
            't' -> {
                val p = reader.point() ?: return reader.fail()
                val control = lastQuad?.let { Offset(2 * current.x - it.x, 2 * current.y - it.y) } ?: current
                val end = offset(p)
                nodes += PathNode.QuadTo(control.x, control.y, end.x, end.y)
                quad = control
                current = end
            }
            'a' -> {
                val rx = reader.number() ?: return reader.fail()
                val ry = reader.number() ?: return reader.fail()
                val rotation = reader.number() ?: return reader.fail()
                val large = reader.flag() ?: return reader.fail()
                val sweep = reader.flag() ?: return reader.fail()
                val p = reader.point() ?: return reader.fail()
                val end = offset(p)
                SVGArc.add(nodes, current, end, rx, ry, rotation, large, sweep)
                current = end
            }
            'z' -> {
                nodes += PathNode.Close
                current = start
            }
            else -> reader.fail()
        }
        lastCubic = cubic
        lastQuad = quad
    }
}
