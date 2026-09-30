package com.junbingao.remotecontrol.core

import com.junbingao.remotecontrol.core.protocol.JSONValue
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * The canonical fixtures live in `protocol/`, next to the schema every other component validates
 * against. The tests read them from the repository rather than keeping a second copy that could
 * drift; the build passes the directory in as `rc.protocol.dir`.
 */
object FixtureSource {
    val root: File = File(checkNotNull(System.getProperty("rc.protocol.dir")) { "rc.protocol.dir is not set" })

    val fixtures: File get() = File(root, "fixtures")
    val invalid: File get() = File(root, "fixtures_invalid")

    /** The repository the protocol directory sits in, for the iPhone app's catalogue. */
    val repository: File get() = root.parentFile

    /** Every JSON file under a directory, recursively, in a stable order. */
    fun files(directory: File): List<File> =
        directory.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.toList()

    /** The path relative to `protocol/fixtures`, used as a check label. */
    fun label(file: File): String = file.relativeTo(fixtures).invariantSeparatorsPath

    fun data(relativePath: String): ByteArray = File(fixtures, relativePath).readBytes()

    fun json(relativePath: String): JsonElement = JSONValue.parse(data(relativePath))

    fun invalidJSON(name: String): JsonElement = JSONValue.parse(File(invalid, name).readBytes())
}
