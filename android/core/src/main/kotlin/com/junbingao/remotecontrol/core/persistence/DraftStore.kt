package com.junbingao.remotecontrol.core.persistence

import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.protocol.WireJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Per-session composer drafts, scoped to the account that wrote them.
 *
 * Drafts survive backgrounding and a dropped connection, and switching accounts or gateways never
 * shows another account's text. The app hands in the directory they live in — its own
 * application-support directory's `RemoteControl/Drafts`, as RCCore's default is.
 *
 * RCCore's actor, kept as one: the cache and the files are touched on [isolation] alone.
 */
class DraftStore(private val directory: File) {
    @Serializable
    private data class Record(val schema: Int, val drafts: Map<String, String>)

    private val isolation: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val cache = mutableMapOf<String, Map<String, String>>()

    suspend fun draft(account: String, key: String): String = withContext(isolation) {
        drafts(account)[key] ?: ""
    }

    suspend fun setDraft(text: String, account: String, key: String): Unit = withContext(isolation) {
        val current = drafts(account).toMutableMap()
        if (text.isEmpty()) current.remove(key) else current[key] = text
        cache[account] = current
        persist(account, current)
    }

    suspend fun clear(account: String): Unit = withContext(isolation) {
        cache[account] = emptyMap()
        attempt { fileURL(account).delete() }
    }

    /**
     * Every account's drafts, gone: what `--reset-state` asks for. A UI test that typed into a
     * session and did not send left its words on disk, and every later launch opened that session
     * with them already in the field.
     */
    suspend fun clearAll(): Unit = withContext(isolation) {
        cache.clear()
        attempt { directory.deleteRecursively() }
    }

    /**
     * Forget the drafts of sessions this account no longer has.
     *
     * The `hello` snapshot is the whole list of what exists, so a key it does not name belongs to a
     * session that has been deleted on the device. Left alone, its words would sit on disk for the
     * life of the install.
     */
    suspend fun retain(keys: Set<String>, account: String): Unit = withContext(isolation) {
        val current = drafts(account)
        val kept = current.filterKeys { it in keys }
        if (kept.size == current.size) return@withContext
        cache[account] = kept
        persist(account, kept)
    }

    private fun drafts(account: String): Map<String, String> {
        cache[account]?.let { return it }
        val record = attempt { WireJson.decodeFromString(Record.serializer(), fileURL(account).readText()) }
        if (record == null || record.schema != schemaVersion) {
            cache[account] = emptyMap()
            return emptyMap()
        }
        cache[account] = record.drafts
        return record.drafts
    }

    private fun persist(account: String, drafts: Map<String, String>) {
        val data = attempt { WireJson.encodeToString(Record.serializer(), Record(schemaVersion, drafts)) } ?: return
        ProtectedFile.write(data.encodeToByteArray(), fileURL(account), directory)
    }

    private fun fileURL(account: String): File = File(directory, "${LocalCache.slug(account)}.json")

    private companion object {
        const val schemaVersion = 1
    }
}
