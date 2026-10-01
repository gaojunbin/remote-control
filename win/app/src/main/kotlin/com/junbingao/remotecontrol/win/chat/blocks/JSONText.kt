package com.junbingao.remotecontrol.win.chat.blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * `JSON.stringify(value, null, 2)`, which is how the web prints a tool's input and an approval's:
 * two-space indents, `"key": value`, empty objects and arrays as `{}` and `[]`, and JavaScript's
 * own escapes and numbers.
 *
 * An object's keys are printed in the order JavaScript keeps them for the object the device's
 * JSON parses into: integer-like keys first, in ascending order, then the rest in the order the
 * device wrote them. The core keeps that order, so the Mac's alphabetical one — RCCore keeps an
 * object as a dictionary — does not come back here.
 */
object JSONText {
    fun stringify(value: JsonElement): String = StringBuilder().also { write(value, indent = "", out = it) }.toString()

    private fun write(value: JsonElement, indent: String, out: StringBuilder) {
        when (value) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(primitive(value))
            is JsonArray -> {
                if (value.isEmpty()) {
                    out.append("[]")
                    return
                }
                val inner = "$indent  "
                out.append("[\n")
                for ((index, item) in value.withIndex()) {
                    out.append(inner)
                    write(item, inner, out)
                    out.append(if (index == value.size - 1) "\n" else ",\n")
                }
                out.append(indent).append("]")
            }
            is JsonObject -> {
                if (value.isEmpty()) {
                    out.append("{}")
                    return
                }
                val inner = "$indent  "
                val keys = orderedKeys(value.keys)
                out.append("{\n")
                for ((index, key) in keys.withIndex()) {
                    out.append(inner).append(quoted(key)).append(": ")
                    write(value[key] ?: JsonNull, inner, out)
                    out.append(if (index == keys.size - 1) "\n" else ",\n")
                }
                out.append(indent).append("}")
            }
        }
    }

    /** A string, a boolean or a number as JavaScript writes it. */
    private fun primitive(value: JsonPrimitive): String {
        if (value.isString) return quoted(value.content)
        val content = value.content
        if (content == "true" || content == "false") return content
        // A whole number the wire wrote as one is printed as it came, as RCCore's `.integer` is.
        if (content.all { it.isDigit() || it == '-' }) content.toLongOrNull()?.let { return it.toString() }
        return content.toDoubleOrNull()?.let(::numberText) ?: content
    }

    /** Integer-like keys ("0", "12") come first in ascending order, as a JavaScript object orders them. */
    fun orderedKeys(keys: Collection<String>): List<String> {
        val indices = keys.mapNotNull { key ->
            val number = key.toLongOrNull() ?: return@mapNotNull null
            if (number < 0 || number >= 0xFFFF_FFFFL || number.toString() != key) null else number to key
        }
        val integers = indices.map { it.second }.toSet()
        return indices.sortedBy { it.first }.map { it.second } + keys.filter { it !in integers }
    }

    /**
     * `Number.prototype.toString`: the shortest digits that round-trip — the same digits the JVM
     * finds — in JavaScript's notation: fixed from 1e-6 up to 1e21, and `1.5e+21` or `1e-7` outside it.
     */
    fun numberText(number: Double): String {
        if (!number.isFinite()) return "null"
        if (number == 0.0) return "0"
        val described = abs(number).toString()
        val parts = described.split('E')
        val exponent = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
        val mantissa = parts[0]
        val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
        var digits = mantissa.replace(".", "")
        var n = point + exponent
        while (digits.startsWith("0") && digits.length > 1) {
            digits = digits.drop(1)
            n -= 1
        }
        while (digits.endsWith("0") && digits.length > 1) digits = digits.dropLast(1)
        val k = digits.length
        val sign = if (number < 0) "-" else ""
        if (n in k..21) return sign + digits + "0".repeat(n - k)
        if (n in 1..21) return sign + digits.take(n) + "." + digits.drop(n)
        if (n in -5..0) return sign + "0." + "0".repeat(-n) + digits
        val tail = if (k > 1) "." + digits.drop(1) else ""
        val power = n - 1
        return sign + digits.take(1) + tail + "e" + (if (power >= 0) "+" else "-") + abs(power)
    }

    /**
     * A JSON string as `JSON.stringify` writes it: `"` and `\` escaped, the control characters as
     * `\n`, `\t` … or `\u00XX`, everything else as is.
     */
    fun quoted(text: String): String {
        val out = StringBuilder("\"")
        for (char in text) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char == '\b' -> out.append("\\b")
                char == '\u000C' -> out.append("\\f")
                char.code < 0x20 -> out.append("\\u%04x".format(char.code))
                else -> out.append(char)
            }
        }
        return out.append('"').toString()
    }
}
