package com.junbingao.remotecontrol.core.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.math.floor

// RCCore's `JSONValue` is a JSON tree of its own; here it is kotlinx's `JsonElement`, which keeps
// an object's keys in the order they arrived. What follows are the accessors RCCore reads a tree
// with, under the same names.

/**
 * The one reader and writer every wire type goes through, as lenient as RCCore's decoders: a
 * field this build does not model is skipped, an absent or null field takes the value RCCore's
 * `decodeIfPresent … ??` gives it, and an optional left empty is left out of what is written.
 */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** `JSONValue`'s static half: encoding a typed value as a tree, and reading a frame's bytes. */
object JSONValue {
    /** An empty object: what an absent result or an absent raw frame reads as. */
    val emptyObject: JsonObject = JsonObject(emptyMap())

    /** A typed value as the tree it is written as. */
    inline fun <reified T> encode(value: T): JsonElement = WireJson.encodeToJsonElement(value)

    fun <T> encode(serializer: SerializationStrategy<T>, value: T): JsonElement =
        WireJson.encodeToJsonElement(serializer, value)

    /** The tree a frame's UTF-8 bytes hold. Throws when they are not JSON. */
    fun parse(data: ByteArray): JsonElement =
        WireJson.parseToJsonElement(data.decodeToString(throwOnInvalidSequence = true))
}

/** A tree read as a typed value, as leniently as the type's own decoder allows. */
inline fun <reified T> JsonElement.decode(): T = WireJson.decodeFromJsonElement(this)

fun <T> JsonElement.decode(deserializer: DeserializationStrategy<T>): T =
    WireJson.decodeFromJsonElement(deserializer, this)

val JsonElement.objectValue: JsonObject? get() = this as? JsonObject
val JsonElement.arrayValue: JsonArray? get() = this as? JsonArray
val JsonElement.stringValue: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
val JsonElement.boolValue: Boolean? get() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

val JsonElement.doubleValue: Double?
    get() = numberContent?.toDoubleOrNull()

/**
 * The value as a Kotlin `Int`, when it is a whole number in range. RCCore's `intValue` is a Swift
 * `Int`, which is 64 bits wide; [longValue] is the one that reads milliseconds.
 */
val JsonElement.intValue: Int?
    get() = longValue?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

/** The value as a 64-bit whole number: `5`, `5.0` and `5e0` all read as five, `5.5` as nothing. */
val JsonElement.longValue: Long?
    get() {
        val content = numberContent ?: return null
        content.toLongOrNull()?.let { return it }
        val value = content.toDoubleOrNull() ?: return null
        return if (value == floor(value) && value >= -9.2e18 && value <= 9.2e18) value.toLong() else null
    }

private val JsonElement.numberContent: String?
    get() = (this as? JsonPrimitive)?.takeUnless { it.isString || it is JsonNull }?.content

/** The member under [key], when this is an object. */
operator fun JsonElement.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

fun Map<String, JsonElement>.string(key: String): String? = this[key]?.stringValue
fun Map<String, JsonElement>.int(key: String): Int? = this[key]?.intValue
fun Map<String, JsonElement>.long(key: String): Long? = this[key]?.longValue
fun Map<String, JsonElement>.double(key: String): Double? = this[key]?.doubleValue
fun Map<String, JsonElement>.bool(key: String): Boolean? = this[key]?.boolValue
fun Map<String, JsonElement>.array(key: String): List<JsonElement> = this[key]?.arrayValue ?: emptyList()
fun Map<String, JsonElement>.`object`(key: String): JsonObject? = this[key]?.objectValue

/**
 * RCCore's JSON literals (`["seq": 1, "kind": "notice"]`): a Kotlin value as a tree. Strings,
 * booleans, whole numbers, doubles, null, maps with string keys, lists and trees are accepted.
 */
fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int, is Long, is Short, is Byte -> JsonPrimitive((value as Number).toLong())
    is Number -> JsonPrimitive(value.toDouble())
    is Map<*, *> -> JsonObject(value.entries.associate { (key, member) -> key as String to jsonOf(member) })
    is Iterable<*> -> JsonArray(value.map(::jsonOf))
    is Array<*> -> JsonArray(value.map(::jsonOf))
    else -> throw IllegalArgumentException("${value::class.simpleName} is not a JSON value")
}

/** An object literal, its keys in the order they are written. */
fun jsonObjectOf(vararg members: Pair<String, Any?>): JsonObject =
    JsonObject(members.associate { (key, member) -> key to jsonOf(member) })

/** An array literal. */
fun jsonArrayOf(vararg values: Any?): JsonArray = JsonArray(values.map(::jsonOf))
