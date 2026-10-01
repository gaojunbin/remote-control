package com.junbingao.remotecontrol.android.screens.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A tool's input as its card prints it: a string as it is, anything else as the iPhone prints it
 * — `JSONSerialization` with `.prettyPrinted` and `.sortedKeys` — so the two apps show the same
 * text: two spaces a level, `" : "` between a key and its value, keys in order, and the forward
 * slash escaped, as Foundation escapes it.
 */
internal object ToolInputText {
    fun render(value: JsonElement): String {
        if (value is JsonPrimitive && value.isString) return value.content
        // A bare number or flag is not a JSON document to Foundation, and the iPhone prints the
        // encoder's own bytes for it.
        if (value is JsonPrimitive) return scalar(value)
        return StringBuilder().also { write(value, it, 0) }.toString()
    }

    private fun write(value: JsonElement, out: StringBuilder, depth: Int) {
        when (value) {
            is JsonObject -> {
                out.append("{\n")
                val keys = value.keys.sorted()
                if (keys.isEmpty()) out.append('\n')
                keys.forEachIndexed { index, key ->
                    indent(out, depth + 1)
                    out.append(quoted(key)).append(" : ")
                    write(value.getValue(key), out, depth + 1)
                    if (index < keys.size - 1) out.append(',')
                    out.append('\n')
                }
                indent(out, depth)
                out.append('}')
            }
            is JsonArray -> {
                out.append("[\n")
                if (value.isEmpty()) out.append('\n')
                value.forEachIndexed { index, item ->
                    indent(out, depth + 1)
                    write(item, out, depth + 1)
                    if (index < value.size - 1) out.append(',')
                    out.append('\n')
                }
                indent(out, depth)
                out.append(']')
            }
            is JsonPrimitive -> out.append(if (value.isString) quoted(value.content) else scalar(value))
        }
    }

    private fun scalar(value: JsonPrimitive): String {
        if (value is JsonNull) return "null"
        value.booleanOrNull?.let { return it.toString() }
        value.longOrNull?.let { return it.toString() }
        val number = value.doubleOrNull ?: return value.content
        return if (number == Math.floor(number) && kotlin.math.abs(number) < 1e15) number.toLong().toString() else number.toString()
    }

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append("  ") }
    }

    private fun quoted(text: String): String = buildString {
        append('"')
        for (character in text) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '/' -> append("\\/")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
