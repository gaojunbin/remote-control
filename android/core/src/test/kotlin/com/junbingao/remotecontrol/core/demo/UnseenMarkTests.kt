@file:OptIn(ExperimentalCoroutinesApi::class)

package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.core.state.StubGateway
import com.junbingao.remotecontrol.core.state.scratchDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Amendment A47 at the demo gateway, which keeps the mark as the gateway does, so every app's demo
 * and pictures show a red dot. RCCore's suite of this name also holds the fixtures
 * (`protocol/UnseenMarkTests.kt`) and the rule, the count and `session.seen`
 * (`state/UnseenMarkTests.kt`).
 */
class UnseenMarkTests {
    private val directories = mutableListOf<File>()

    @AfterTest
    fun removeDirectories() {
        for (directory in directories) directory.deleteRecursively()
    }

    /** The demo starts with one session marked, so the list and the badge show it. */
    @Test
    fun demoStartsWithOneMark() = runTest {
        assertEquals(listOf(DemoFixtures.approvalSessionID), DemoFixtures.sessions.filter { it.unseen }.map { it.sessionID })
        val connection = connected(demoGateway())
        assertEquals(1, connection.unseenCount)
    }

    /** A scripted turn that ends marks the session, and `session.seen` takes it off once. */
    @Test
    fun demoMarksAndClears() = runTest {
        val gateway = demoGateway(echoDelay = Duration.ZERO)
        var published = 0
        val connection = connected(gateway) { store ->
            store.onSessionTransition = { _, current -> if (current.sessionID == DemoFixtures.piSessionID) published += 1 }
        }
        val pi = { connection.sessions.firstOrNull { it.sessionID == DemoFixtures.piSessionID } }

        gateway.request(GatewayRequest.send(sessionID = DemoFixtures.piSessionID, text = "go"))
        runCurrent()
        assertEquals(SessionState.running, pi()?.state)
        assertEquals(false, pi()?.unseen, "working is never marked")
        settle()
        assertEquals(SessionState.idle, pi()?.state)
        assertEquals(true, pi()?.unseen, "the turn ended and nobody was looking")
        assertEquals(2, connection.unseenCount)

        val before = published
        connection.markSeen(deviceID = DemoFixtures.macDeviceID, sessionID = DemoFixtures.piSessionID)
        settle()
        assertEquals(false, pi()?.unseen, "seen takes the mark off")
        assertEquals(before + 1, published, "in one session.updated")
        gateway.request(GatewayRequest.seen(sessionID = DemoFixtures.piSessionID))
        settle()
        assertEquals(before + 1, published, "and a second seen publishes nothing: there was nothing to take off")
    }

    /** The demo clears the mark when the session works again and when it is closed. */
    @Test
    fun demoClears() = runTest {
        val gateway = demoGateway(echoDelay = Duration.ZERO)
        val connection = connected(gateway)
        val vite = { connection.sessions.firstOrNull { it.sessionID == DemoFixtures.approvalSessionID } }
        val marked = assertNotNull(vite())
        assertTrue(marked.unseen)

        // Approving from somewhere else is carrying on, which takes the dot away.
        gateway.request(GatewayRequest.approve(sessionID = marked.sessionID, requestID = "demo-approval-1", optionID = "approved"))
        runCurrent()
        assertEquals(SessionState.running, vite()?.state)
        assertEquals(false, vite()?.unseen)

        val pi = { connection.sessions.firstOrNull { it.sessionID == DemoFixtures.piSessionID } }
        gateway.request(GatewayRequest.send(sessionID = DemoFixtures.piSessionID, text = "go"))
        settle()
        assertEquals(true, pi()?.unseen)
        connection.close(assertNotNull(pi()))
        settle()
        assertEquals(true, pi()?.archived)
        assertEquals(false, pi()?.unseen, "a closed session is filed without its dot")
    }

    /** The demo answers `not_found` for a session it does not have. */
    @Test
    fun demoUnknownSession() = runTest {
        val refusal = assertFailsWith<GatewayErrorBody> { demoGateway().request(GatewayRequest.seen(sessionID = "no-such-session")) }
        assertEquals(GatewayErrorCode.notFound, refusal.code)
    }

    /** A store in the demo, as the apps enter it, with whatever the test hooks onto it first. */
    private suspend fun TestScope.connected(gateway: DemoGateway, prepare: (ConnectionStore) -> Unit = {}): ConnectionStore {
        val directory = scratchDirectory("unseen-demo").also { directories.add(it) }
        val connection = ConnectionStore(tasks = backgroundScope, cache = LocalCache(directory), makeAPI = { StubGateway(it) })
        prepare(connection)
        connection.enterDemo(api = gateway, channel = gateway)
        settle()
        assertTrue(connection.hasSnapshot)
        return connection
    }
}
