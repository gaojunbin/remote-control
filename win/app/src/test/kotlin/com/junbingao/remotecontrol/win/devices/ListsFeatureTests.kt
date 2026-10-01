package com.junbingao.remotecontrol.win.devices

import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.ModelHarness
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.sessions.drawer.NewSessionForm
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Mac's "Lists: feature" — what the lists keep for the life of the app: the agent filter goes
 * with the account that set it, as `useSessions.reset()` drops it on the web.
 */
class ListsFeatureTests {
    @Test
    fun signingOutPutsTheAgentFilterBackToAllAgents() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            sessions.agentFilter = "codex"
            signOut()
            assertNull(sessions.agentFilter)
        }
    }

    /**
     * The drawer opens the conversation it made, which the page can only draw once the list holds
     * it: the gateway announces a new session before it answers `session.create`.
     */
    @Test
    fun aStartedSessionIsInTheListWhenTheReplyArrives() = ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            assertTrue(harness.waitFor { connection.hasSnapshot })
            val devices = DeviceOrder.online(connection.devices)
            val form = NewSessionForm(devices = devices, preset = null)
            form.loadHome(channel = connection.channel)
            val device = form.device(devices)
            assertTrue(form.cwd.isNotEmpty())
            val session = assertNotNull(form.start(device = device, agent = form.agent(of = device), channel = connection.channel))
            harness.waitFor { connection.session(deviceID = session.deviceID, sessionID = session.sessionID) != null }
            assertNotNull(connection.session(deviceID = session.deviceID, sessionID = session.sessionID))
            signOut()
        }
    }
}
