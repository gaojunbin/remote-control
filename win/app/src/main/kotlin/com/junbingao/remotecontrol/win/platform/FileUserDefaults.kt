package com.junbingao.remotecontrol.win.platform

import com.junbingao.remotecontrol.core.state.UserDefaults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The core's `UserDefaults` on Windows, where the Mac reads and writes the standard defaults: the
 * preferences in one JSON file in the app's data folder (`AppData.defaults`), read once at launch
 * and written whole after every change, so a crash leaves the file as it was before the change or
 * after it. The readers answer as Foundation's do — a key that holds nothing, or a value of
 * another type, reads as null, false or zero — and a file that cannot be read is a fresh install's.
 */
class FileUserDefaults(private val file: Path) : UserDefaults {
    private val values: LinkedHashMap<String, JsonElement> = load()

    override fun string(forKey: String): String? = primitive(forKey)?.takeIf { it.isString }?.content

    override fun stringArray(forKey: String): List<String>? = synchronized(values) {
        (values[forKey] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
    }

    override fun bool(forKey: String): Boolean = primitive(forKey)?.takeIf { !it.isString }?.booleanOrNull ?: false

    override fun double(forKey: String): Double = primitive(forKey)?.takeIf { !it.isString }?.doubleOrNull ?: 0.0

    override fun set(value: String, forKey: String) = store(JsonPrimitive(value), forKey)

    override fun set(value: Boolean, forKey: String) = store(JsonPrimitive(value), forKey)

    override fun set(value: Double, forKey: String) = store(JsonPrimitive(value), forKey)

    override fun set(value: List<String>, forKey: String) = store(JsonArray(value.map(::JsonPrimitive)), forKey)

    override fun removeObject(forKey: String) {
        synchronized(values) {
            if (values.remove(forKey) != null) save()
        }
    }

    override val keys: Set<String> get() = synchronized(values) { values.keys.toSet() }

    private fun primitive(key: String): JsonPrimitive? = synchronized(values) { values[key] as? JsonPrimitive }

    private fun store(value: JsonElement, key: String) {
        synchronized(values) {
            values[key] = value
            save()
        }
    }

    private fun load(): LinkedHashMap<String, JsonElement> {
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return LinkedHashMap()
        val stored = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return LinkedHashMap()
        return LinkedHashMap(stored)
    }

    private fun save() {
        val text = Json.encodeToString(JsonObject.serializer(), JsonObject(values))
        runCatching {
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, ".defaults", ".tmp")
            try {
                Files.writeString(temporary, text)
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
