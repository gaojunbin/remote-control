package com.junbingao.remotecontrol.win.chat.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One node of the hast tree the web hands React: what react-markdown 10 makes of a message with
 * remark-gfm and rehype-highlight, after its own `post` step. The bundled pipeline writes it
 * compactly — an element is `[tag, properties, children]`, a text node is a string — and this
 * reads it back.
 */
sealed interface HastNode {
    data class Text(val value: String) : HastNode
    data class Element(val element: HastElement) : HastNode

    /** The text a node holds, as `textContent` reads it. */
    val textContent: String
        get() = when (this) {
            is Text -> value
            is Element -> element.textContent
        }

    companion object {
        /** The nodes of a JSON document the pipeline wrote, or null when it is not one. */
        fun decode(json: String): List<HastNode>? {
            val array = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return null
            return array.mapNotNull(::node)
        }

        private fun node(value: JsonElement): HastNode? {
            if (value is JsonPrimitive && value.isString) return Text(value.content)
            val parts = value as? JsonArray ?: return null
            if (parts.size != 3) return null
            val tag = (parts[0] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val properties = parts[1] as? JsonObject ?: JsonObject(emptyMap())
            val children = (parts[2] as? JsonArray)?.mapNotNull(::node) ?: emptyList()
            return Element(HastElement(tag = tag, properties = HastProperties(properties), children = children))
        }
    }
}

data class HastElement(val tag: String, val properties: HastProperties, val children: List<HastNode>) {
    val textContent: String get() = children.joinToString("") { it.textContent }

    /** The children that are elements, without the whitespace between them. */
    val elements: List<HastElement> get() = children.mapNotNull { (it as? HastNode.Element)?.element }
}

/** The properties the chat's Markdown reads. */
data class HastProperties(
    val className: List<String> = emptyList(),
    val href: String? = null,
    val src: String? = null,
    val alt: String? = null,
    val title: String? = null,
    val checked: Boolean? = null,
    val start: Int? = null,
    val align: String? = null,
    val id: String? = null,
) {
    companion object {
        operator fun invoke(raw: JsonObject): HastProperties = HastProperties(
            className = (raw["className"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: emptyList(),
            href = raw.string("href"),
            src = raw.string("src"),
            alt = raw.string("alt"),
            title = raw.string("title"),
            checked = (raw["checked"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
            start = (raw["start"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toInt(),
            align = raw.string("align"),
            id = raw.string("id"),
        )

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
