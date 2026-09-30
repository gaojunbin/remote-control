package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Transport. */
class TransportTests {
    /** An https origin canonicalises. */
    @Test
    fun canonicalOrigins() {
        val table = listOf(
            "HTTPS://RC.Example.com:443/" to "https://rc.example.com",
            "rc.example.com" to "https://rc.example.com",
            "https://rc.example.com:8443" to "https://rc.example.com:8443",
        )
        for ((input, expected) in table) assertEquals(expected, GatewayEndpoint(input).origin, input)
    }

    /** Plain http is accepted only for a development host. */
    @Test
    fun developmentOrigins() {
        for (input in listOf("http://127.0.0.1:8787", "http://localhost:8787", "http://192.168.1.20:8787",
                             "http://10.0.0.5:8787", "http://172.20.0.4:8787", "http://mac-studio.local:8787")) {
            val endpoint = GatewayEndpoint(input)
            assertFalse(endpoint.isSecure, input)
            assertEquals("ws", endpoint.socketURL(path = "/ws/app").scheme, input)
        }
    }

    /** A public host over plain http is refused. */
    @Test
    fun publicHttpRefused() {
        for (input in listOf("http://rc.example.com", "http://8.8.8.8", "http://172.32.0.1")) {
            assertFailsWith<TransportError>(input) { GatewayEndpoint(input) }
        }
    }

    /** Credentials, paths and queries are refused in an origin. */
    @Test
    fun malformedOrigins() {
        for (input in listOf("https://user:pass@rc.example.com", "https://rc.example.com/path",
                             "https://rc.example.com?token=abc", "ftp://rc.example.com", "")) {
            assertFailsWith<TransportError>(input) { GatewayEndpoint(input) }
        }
    }

    /** A session link round-trips and rejects a web URL. */
    @Test
    fun sessionLinks() {
        val link = SessionLink(deviceID = "d", sessionID = "s")
        val url = assertNotNull(link.url)
        assertEquals("remotecontrol://session?device=d&id=s", url.toString())
        assertEquals(link, SessionLink(url = url))
        assertNull(SessionLink(url = URI("https://example.com/session?device=d&id=s")))
    }

    /** A push payload from a newer protocol is refused. */
    @Test
    fun pushVersionGate() {
        assertTrue(runCatching {
            PushRoute(userInfo = """{"rc":{"v":2,"device_id":"d","session_id":"s"}}""".encodeToByteArray())
        }.isFailure)
    }

    /** A secret round-trips through the in-memory store. */
    @Test
    fun secretStore() = runTest {
        val store = MemorySecretStore()
        store.write("token".encodeToByteArray(), key = "k")
        assertTrue(store.read(key = "k").contentEquals("token".encodeToByteArray()))
        store.remove(key = "k")
        assertNull(store.read(key = "k"))
    }
}
