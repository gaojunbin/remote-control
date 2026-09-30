package com.junbingao.remotecontrol.core.persistence

import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.isFoundationAlphanumeric
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.WireJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The cached snapshot for one (gateway origin, username) pair. */
@Serializable
data class CachedWorkspace(
    @Required val devices: List<Device> = emptyList(),
    @Required val sessions: List<Session> = emptyList(),
    /** Final events for the sessions the user opened most recently. */
    @Required val transcripts: Map<String, List<SessionEvent>> = emptyMap(),
    @Required val savedAt: Long = 0,
)

/**
 * A small versioned JSON cache so the session list paints before the socket connects. The app
 * hands in the directory it lives in — its own application-support directory's
 * `RemoteControl/Cache`, as RCCore's default is.
 *
 * The schema version is deliberately independent of the wire protocol: a protocol bump must not
 * silently throw away every cached transcript and draft. Bump [schemaVersion] only when this
 * file's own shape changes.
 *
 * RCCore's actor, kept as one: its files are touched on [isolation] alone.
 */
class LocalCache(private val directory: File) {
    @Serializable
    private data class Record(val schema: Int, val workspace: CachedWorkspace)

    private val isolation: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun load(origin: String, username: String): CachedWorkspace? = withContext(isolation) {
        val record = attempt {
            WireJson.decodeFromString(Record.serializer(), fileURL(origin, username).readText())
        }
        if (record == null || record.schema != schemaVersion) null else record.workspace
    }

    suspend fun save(workspace: CachedWorkspace, origin: String, username: String): Unit = withContext(isolation) {
        val trimmed = workspace.copy(transcripts = trim(workspace.transcripts, keeping = workspace.sessions.map { it.id }))
        val data = attempt { WireJson.encodeToString(Record.serializer(), Record(schemaVersion, trimmed)) }
            ?: return@withContext
        ProtectedFile.write(data.encodeToByteArray(), fileURL(origin, username), directory)
    }

    suspend fun clear(origin: String, username: String): Unit = withContext(isolation) {
        attempt { fileURL(origin, username).delete() }
    }

    /** One file per account, so signing in as someone else never paints the previous account's transcripts. */
    private fun fileURL(origin: String, username: String): File =
        File(directory, "${slug(origin)}_${slug(username)}.json")

    companion object {
        const val schemaVersion = 1

        /** Recent sessions kept on disk, and how much of each. */
        const val sessionLimit = 12
        const val eventLimit = 200

        /** Keep the newest events for at most [sessionLimit] sessions. */
        fun trim(transcripts: Map<String, List<SessionEvent>>, keeping: List<String>): Map<String, List<SessionEvent>> {
            val ranked = keeping.filter { it in transcripts }.take(sessionLimit)
            val keys = ranked.ifEmpty { transcripts.keys.take(sessionLimit) }
            val result = LinkedHashMap<String, List<SessionEvent>>()
            for (key in keys) {
                val events = transcripts[key] ?: continue
                result[key] = events.takeLast(eventLimit)
            }
            return result
        }

        /**
         * A readable prefix plus a digest, so two origins that differ only in punctuation or past
         * the 60th character cannot share a file.
         */
        fun slug(value: String): String {
            val mapped = value.codePoints().limit(60).toArray()
                .joinToString("") { if (isFoundationAlphanumeric(it)) String(Character.toChars(it)) else "-" }
            return "$mapped-${digest(value)}"
        }

        /** FNV-1a over the UTF-8 bytes, in base 36. */
        private fun digest(value: String): String {
            var hash = 0xcbf29ce484222325uL
            for (byte in value.encodeToByteArray()) {
                hash = hash xor byte.toUByte().toULong()
                hash *= 0x100000001b3uL
            }
            return hash.toString(36)
        }
    }
}

/**
 * Writes app-private JSON. The cache holds prompts, tool output and diffs — exactly the content
 * the product keeps out of push payloads and diagnostics — so it goes into the directory the app
 * handed in, which is its own private storage, and whole or not at all. Keeping it out of device
 * backups is the app's to declare (Android's backup rules, the Windows profile's local data).
 */
internal object ProtectedFile {
    fun write(data: ByteArray, to: File, directory: File) {
        attempt { directory.mkdirs() }
        attempt {
            val staged = File.createTempFile(".${to.name}", ".tmp", directory)
            try {
                staged.writeBytes(data)
                try {
                    Files.move(staged.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(staged.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                staged.delete()
            }
        }
    }
}
