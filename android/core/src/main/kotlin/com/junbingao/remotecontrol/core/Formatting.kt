package com.junbingao.remotecontrol.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * `String(format:)`: a C format string — `%@`, `%lld`, `%.1f`, `%02d`, `%2$@` — filled in as
 * Foundation fills it, in the POSIX locale, with a fraction that falls exactly between two
 * printable values rounded to the even one, as C's `printf` rounds it.
 *
 * The keys of the core's string table keep RCCore's specifiers, so a key reads the same in both
 * sources; this is where they become Java's.
 */
internal fun formatted(format: String, vararg arguments: Any?): String {
    val out = StringBuilder()
    var sequential = 0
    var index = 0
    while (index < format.length) {
        val character = format[index]
        if (character != '%') {
            out.append(character)
            index += 1
            continue
        }
        val spec = FormatSpec.parse(format, index + 1)
        if (spec == null) {
            out.append(format, index, format.length)
            break
        }
        if (spec.conversion == '%') {
            out.append('%')
        } else {
            val position = spec.position ?: (sequential + 1)
            sequential = position
            out.append(spec.render(arguments.getOrNull(position - 1)))
        }
        index = spec.end
    }
    return out.toString()
}

/** One `%…` specifier: where its value comes from, how it is laid out, and what it converts. */
private class FormatSpec(
    val position: Int?,
    val flags: String,
    val width: String,
    val precision: Int?,
    val conversion: Char,
    val end: Int,
) {
    fun render(argument: Any?): String {
        val layout = flags + width
        return when (conversion) {
            '@', 's' -> String.format(Locale.ROOT, "%${layout}s", argument?.toString() ?: "(null)")
            'd', 'i', 'u' -> String.format(Locale.ROOT, "%${layout}d", (argument as? Number)?.toLong() ?: 0L)
            'x', 'X', 'o' -> String.format(Locale.ROOT, "%$layout$conversion", (argument as? Number)?.toLong() ?: 0L)
            'c' -> String.format(Locale.ROOT, "%${layout}c", (argument as? Char) ?: ' ')
            'f', 'F' -> {
                val digits = precision ?: 6
                val value = BigDecimal((argument as? Number)?.toDouble() ?: 0.0).setScale(digits, RoundingMode.HALF_EVEN)
                String.format(Locale.ROOT, "%$layout.${digits}f", value)
            }
            'e', 'E', 'g', 'G' -> {
                val places = precision?.let { ".$it" } ?: ""
                String.format(Locale.ROOT, "%$layout$places$conversion", (argument as? Number)?.toDouble() ?: 0.0)
            }
            else -> ""
        }
    }

    companion object {
        private const val FLAGS = "-+ #0"
        private const val LENGTHS = "hlqLzjt"

        /** The specifier starting after a `%` at [start], or null where the format ends inside one. */
        fun parse(format: String, start: Int): FormatSpec? {
            var cursor = start
            if (cursor < format.length && format[cursor] == '%') {
                return FormatSpec(null, "", "", null, '%', cursor + 1)
            }
            var position: Int? = null
            val digitsStart = cursor
            while (cursor < format.length && format[cursor] in '0'..'9') cursor += 1
            if (cursor > digitsStart && cursor < format.length && format[cursor] == '$') {
                position = format.substring(digitsStart, cursor).toInt()
                cursor += 1
            } else {
                cursor = digitsStart
            }
            val flagsStart = cursor
            while (cursor < format.length && (format[cursor] in FLAGS || format[cursor] == '\'')) cursor += 1
            val flags = format.substring(flagsStart, cursor).replace("'", "")
            val widthStart = cursor
            while (cursor < format.length && format[cursor] in '0'..'9') cursor += 1
            val width = format.substring(widthStart, cursor)
            var precision: Int? = null
            if (cursor < format.length && format[cursor] == '.') {
                cursor += 1
                val precisionStart = cursor
                while (cursor < format.length && format[cursor] in '0'..'9') cursor += 1
                precision = format.substring(precisionStart, cursor).toIntOrNull() ?: 0
            }
            while (cursor < format.length && format[cursor] in LENGTHS) cursor += 1
            if (cursor >= format.length) return null
            return FormatSpec(position, flags, width, precision, format[cursor], cursor + 1)
        }
    }
}
