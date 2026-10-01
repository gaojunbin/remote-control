package com.junbingao.remotecontrol.android.strings

import java.util.Locale

/**
 * `String(format:)` as Foundation reads a catalogue value, so a translation written for the
 * iPhone — `%@`, `%lld`, `%.1f`, `%%`, and positional `%2$@` where Chinese puts the values in
 * another order — says the same thing here.
 *
 * Positional and sequential specifiers are counted apart, as Foundation counts them: `%2$@` names
 * the second argument whatever came before it, and each plain specifier takes the next one. A
 * specifier with no argument left prints nothing rather than throwing, because a word on screen
 * that lost a number is better than a screen that stopped drawing.
 */
object IosFormat {
    fun format(template: String, arguments: List<Any?>): String {
        if (!template.contains('%')) return template
        val out = StringBuilder(template.length + 16)
        var next = 0
        var index = 0
        while (index < template.length) {
            val character = template[index]
            if (character != '%') {
                out.append(character)
                index += 1
                continue
            }
            val spec = Specifier.read(template, index + 1)
            if (spec == null) {
                out.append(character)
                index += 1
                continue
            }
            if (spec.conversion == '%') {
                out.append('%')
            } else {
                val position = spec.position ?: next++
                if (position in arguments.indices) out.append(render(spec, arguments[position]))
            }
            index = spec.end
        }
        return out.toString()
    }

    private fun render(spec: Specifier, argument: Any?): String {
        val body = when (spec.conversion) {
            '@', 's', 'S' -> argument?.toString() ?: if (spec.conversion == '@') "(null)" else ""
            'd', 'i', 'D' -> integer(argument)?.toString() ?: ""
            'u', 'U' -> integer(argument)?.let { java.lang.Long.toUnsignedString(it) } ?: ""
            'x' -> integer(argument)?.let { java.lang.Long.toHexString(it) } ?: ""
            'X' -> integer(argument)?.let { java.lang.Long.toHexString(it).uppercase(Locale.ROOT) } ?: ""
            'o', 'O' -> integer(argument)?.let { java.lang.Long.toOctalString(it) } ?: ""
            'c', 'C' -> integer(argument)?.let { String(Character.toChars(it.toInt())) } ?: ""
            'f', 'F', 'e', 'E', 'g', 'G' -> decimal(argument)?.let { floating(spec, it) } ?: ""
            else -> ""
        }
        return pad(spec, body)
    }

    private fun floating(spec: Specifier, value: Double): String {
        val precision = spec.precision ?: 6
        // Foundation formats with the POSIX locale unless it is handed another, so the decimal
        // point is a point in every language, as it is on the iPhone.
        val pattern = "%" + (if (spec.flags.contains('+')) "+" else "") + "." + precision + spec.conversion
        return String.format(Locale.ROOT, pattern, value)
    }

    private fun pad(spec: Specifier, body: String): String {
        val width = spec.width ?: return body
        if (body.length >= width) return body
        val fill = width - body.length
        return when {
            spec.flags.contains('-') -> body + " ".repeat(fill)
            spec.flags.contains('0') && spec.conversion !in "@sS" -> zeroPad(body, fill)
            else -> " ".repeat(fill) + body
        }
    }

    private fun zeroPad(body: String, fill: Int): String =
        if (body.startsWith("-") || body.startsWith("+")) body[0] + "0".repeat(fill) + body.substring(1)
        else "0".repeat(fill) + body

    private fun integer(argument: Any?): Long? = when (argument) {
        is Long -> argument
        is Int -> argument.toLong()
        is Short -> argument.toLong()
        is Byte -> argument.toLong()
        is Double -> argument.toLong()
        is Float -> argument.toLong()
        is Number -> argument.toLong()
        is Boolean -> if (argument) 1 else 0
        else -> null
    }

    private fun decimal(argument: Any?): Double? = (argument as? Number)?.toDouble()

    /** One `%…` specifier: `%[n$][flags][width][.precision][length]conversion`. */
    private class Specifier(
        val position: Int?,
        val flags: String,
        val width: Int?,
        val precision: Int?,
        val conversion: Char,
        val end: Int,
    ) {
        companion object {
            private const val FLAGS = "-+ #0'"
            private const val CONVERSIONS = "@sSdDiuUxXoOcCfFeEgGaAp%"

            fun read(template: String, start: Int): Specifier? {
                var index = start
                var position: Int? = null
                val digits = digitsAt(template, index)
                if (digits != null && template.getOrNull(index + digits.length) == '$') {
                    position = digits.toInt() - 1
                    index += digits.length + 1
                }
                val flags = StringBuilder()
                while (index < template.length && template[index] in FLAGS) flags.append(template[index++])
                val width = digitsAt(template, index)?.also { index += it.length }?.toInt()
                var precision: Int? = null
                if (template.getOrNull(index) == '.') {
                    index += 1
                    val places = digitsAt(template, index)
                    precision = places?.toInt() ?: 0
                    index += places?.length ?: 0
                }
                while (index < template.length && template[index] in "hlqLztj") index += 1
                val conversion = template.getOrNull(index) ?: return null
                if (conversion !in CONVERSIONS) return null
                return Specifier(position, flags.toString(), width, precision, conversion, index + 1)
            }

            private fun digitsAt(template: String, index: Int): String? {
                var end = index
                while (end < template.length && template[end].isDigit()) end += 1
                return if (end > index) template.substring(index, end) else null
            }
        }
    }
}
