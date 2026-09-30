package com.junbingao.remotecontrol.core.persistence

import com.junbingao.remotecontrol.core.protocol.NoticeLevel
import com.junbingao.remotecontrol.core.protocol.NoticePayload
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Persistence. */
class PersistenceTests {
    private val directories = mutableListOf<File>()

    private fun temporaryDirectory(): File =
        Files.createTempDirectory("rc-tests-").toFile().also { directories.add(it) }

    @AfterTest
    fun removeDirectories() {
        for (directory in directories) directory.deleteRecursively()
    }

    /** The cache is keyed by gateway and account. */
    @Test
    fun cacheIsolation() = runTest {
        val cache = LocalCache(temporaryDirectory())
        val session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp")
        cache.save(CachedWorkspace(sessions = listOf(session)), origin = "https://a.example.com", username = "admin")
        assertEquals(1, cache.load(origin = "https://a.example.com", username = "admin")?.sessions?.size)
        assertNull(cache.load(origin = "https://a.example.com", username = "other"))
        assertNull(cache.load(origin = "https://b.example.com", username = "admin"))
    }

    /** The cache schema version is independent of the wire protocol. */
    @Test
    fun cacheVersioning() {
        assertEquals(1, LocalCache.schemaVersion)
        assertEquals(1, RemoteProtocol.version)
        // Bumping one must never be forced by the other; this test exists so a future change has
        // to state its intent in both places.
    }

    /** Transcripts are trimmed to the most recent sessions and events. */
    @Test
    fun trimming() {
        val event = SessionEvent(seq = 1, ts = 1, kind = "notice",
                                 body = SessionEventBody.Notice(NoticePayload(level = NoticeLevel.info, text = "x")))
        val transcripts = (0 until 30).associate { "s$it" to List(500) { event } }
        val trimmed = LocalCache.trim(transcripts, keeping = (0 until 30).map { "s$it" })
        assertEquals(LocalCache.sessionLimit, trimmed.size)
        assertEquals(LocalCache.eventLimit, trimmed["s0"]?.size)
    }

    /** Drafts never cross accounts or sessions. */
    @Test
    fun draftScoping() = runTest {
        val directory = temporaryDirectory()
        DraftStore(directory).setDraft("half a thought", account = "a|admin", key = "d/s")
        val reloaded = DraftStore(directory)
        assertEquals("half a thought", reloaded.draft(account = "a|admin", key = "d/s"))
        assertEquals("", reloaded.draft(account = "b|admin", key = "d/s"))
        assertEquals("", reloaded.draft(account = "a|admin", key = "d/other"))
    }

    /** clearAll forgets every account's drafts, on disk as well. */
    @Test
    fun clearAllForgetsEveryDraft() = runTest {
        val directory = temporaryDirectory()
        val store = DraftStore(directory)
        store.setDraft("/usage", account = "a|admin", key = "d/s")
        store.setDraft("other words", account = "b|member", key = "d/t")
        store.clearAll()
        assertEquals("", store.draft(account = "a|admin", key = "d/s"))
        assertEquals("", store.draft(account = "b|member", key = "d/t"))
        assertEquals("", DraftStore(directory).draft(account = "a|admin", key = "d/s"),
                     "and a store reading the same directory finds nothing")
    }

    /** A draft goes when the session it belongs to is no longer there. */
    @Test
    fun draftsFollowTheSessionList() = runTest {
        val directory = temporaryDirectory()
        val store = DraftStore(directory)
        store.setDraft("still typing", account = "a|admin", key = "d/live")
        store.setDraft("abandoned", account = "a|admin", key = "d/deleted")

        // What the `hello` snapshot still lists.
        store.retain(setOf("d/live"), account = "a|admin")
        assertEquals("still typing", store.draft(account = "a|admin", key = "d/live"))
        assertEquals("", store.draft(account = "a|admin", key = "d/deleted"))

        val reloaded = DraftStore(directory)
        assertEquals("", reloaded.draft(account = "a|admin", key = "d/deleted"), "and it is gone from disk, not just from memory")
        assertEquals("still typing", reloaded.draft(account = "a|admin", key = "d/live"))
    }

    /** Signing out takes the account's drafts with it. */
    @Test
    fun draftsGoOnSignOut() = runTest {
        val directory = temporaryDirectory()
        val store = DraftStore(directory)
        store.setDraft("half a thought", account = "a|admin", key = "d/s")
        store.clear(account = "a|admin")
        assertEquals("", DraftStore(directory).draft(account = "a|admin", key = "d/s"))
    }
}
