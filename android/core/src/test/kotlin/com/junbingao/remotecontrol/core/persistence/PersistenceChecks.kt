package com.junbingao.remotecontrol.core.persistence

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.NoticeLevel
import com.junbingao.remotecontrol.core.protocol.NoticePayload
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.objectValue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** `ios/Verification/PersistenceChecks.swift`. */
class PersistenceChecks {
    private val directories = mutableListOf<File>()

    private fun temporaryDirectory(): File =
        Files.createTempDirectory("rc-verify-").toFile().also { directories.add(it) }

    @AfterTest
    fun removeDirectories() {
        for (directory in directories) directory.deleteRecursively()
    }

    @Test
    fun cache() = runTest {
        val checks = CheckRunner("persistence")
        val directory = temporaryDirectory()
        val cache = LocalCache(directory)
        val session = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "T", cwd = "/tmp")
        val device = Device(deviceID = "d", name = "mac", platform = DevicePlatform.macos, hostname = "h", arch = "arm64",
                            clientVersion = "0.1.0", online = true, lastSeen = 1, createdAt = 1)
        cache.save(CachedWorkspace(devices = listOf(device), sessions = listOf(session)),
                   origin = "https://rc.example.com", username = "admin")

        val loaded = cache.load(origin = "https://rc.example.com", username = "admin")
        checks.equal(loaded?.sessions?.size, 1, "the cached session list round-trips")
        checks.equal(loaded?.devices?.firstOrNull()?.name, "mac", "the cached device list round-trips")
        checks.expect(cache.load(origin = "https://rc.example.com", username = "someone-else") == null,
                      "a different account never reads this account's cache")
        checks.expect(cache.load(origin = "https://other.example.com", username = "admin") == null,
                      "a different gateway never reads this gateway's cache")

        // The cache version is independent of the wire protocol version: a protocol bump must not
        // silently discard every cached transcript.
        checks.equal(LocalCache.schemaVersion, 1, "the cache schema version is its own number")
        val path = directory.listFiles().orEmpty().firstOrNull { it.name.endsWith(".json") }
        val record = path?.let { runCatching { JSONValue.parse(it.readBytes()).objectValue }.getOrNull() }
        if (path != null && record != null) {
            path.writeText(JsonObject(record + ("schema" to JsonPrimitive(LocalCache.schemaVersion + 1))).toString())
            checks.expect(cache.load(origin = "https://rc.example.com", username = "admin") == null,
                          "a record from a newer cache schema is ignored")
        } else {
            checks.expect(false, "the cache record is readable JSON")
        }

        cache.clear(origin = "https://rc.example.com", username = "admin")
        checks.expect(cache.load(origin = "https://rc.example.com", username = "admin") == null, "signing out clears the cache")
        checks.assertAll()
    }

    @Test
    fun drafts() = runTest {
        val checks = CheckRunner("persistence")
        val directory = temporaryDirectory()
        DraftStore(directory).setDraft("half a thought", account = "https://rc|admin", key = "d/s")
        val reloaded = DraftStore(directory)
        checks.equal(reloaded.draft(account = "https://rc|admin", key = "d/s"), "half a thought", "a draft survives a relaunch")
        checks.equal(reloaded.draft(account = "https://rc|other", key = "d/s"), "", "drafts never cross accounts")
        checks.equal(reloaded.draft(account = "https://rc|admin", key = "d/other"), "", "drafts never cross sessions")
        reloaded.setDraft("", account = "https://rc|admin", key = "d/s")
        checks.equal(reloaded.draft(account = "https://rc|admin", key = "d/s"), "", "an emptied draft is removed")
        checks.assertAll()
    }

    @Test
    fun secrets() = runTest {
        val checks = CheckRunner("persistence")
        val store = MemorySecretStore()
        store.write("token".encodeToByteArray(), key = "k")
        checks.expect(store.read(key = "k")?.contentEquals("token".encodeToByteArray()) == true, "a secret round-trips")
        store.remove(key = "k")
        checks.expect(store.read(key = "k") == null, "a removed secret is gone")
        checks.assertAll()
    }

    @Test
    fun trimming() {
        val checks = CheckRunner("persistence")
        val event = SessionEvent(seq = 1, ts = 1, kind = "notice",
                                 body = SessionEventBody.Notice(NoticePayload(level = NoticeLevel.info, text = "x")))
        val transcripts = (0 until 30).associate { "s$it" to List(500) { event } }
        val trimmed = LocalCache.trim(transcripts, keeping = (0 until 30).map { "s$it" })
        checks.equal(trimmed.size, LocalCache.sessionLimit, "only the most recent sessions are kept")
        checks.equal(trimmed["s0"]?.size, LocalCache.eventLimit, "each transcript is capped")
        checks.expect(trimmed["s29"] == null, "the least recent session is evicted")
        checks.assertAll()
    }
}
