package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The names and the decoding of RCCore's suite of this name are in `protocol/AgentsTests.kt`. Its
// cases that read the demo's devices, sessions and agents — `demoDeviceCarriesFour`,
// `commandCapability`, `demoSessions`, `grokSessionsFollowTheLeader`, `noCursorSessionSurvives`,
// `piShowsThePermissionControl`, `grokTerminalChips`, `terminalControlNotice`,
// `grokSessionNeverPromisesATakeover`, `filterOptions`, `searchByName` — arrive with the demo gateway.

/** Amendment A25: what an agent's empty lists take away from the composer, whoever the agent is. */
class AgentsTests {
    // What an empty list takes away

    /** An agent with no permission system still shows no chip. Amendment A17 is unchanged: an agent that lists no modes still draws no chip, whatever the session reports. */
    @Test
    fun anEmptyListStillTakesTheChipAway() {
        val bare = AgentInfo(agent = "aider", available = true, models = listOf(AgentOption(id = "m", label = "M")),
                             defaultModel = "m")
        val session = Session(sessionID = "s", deviceID = "d", agent = "aider", title = "Port", cwd = "/tmp",
                              control = SessionControl.terminal, model = "m", permissionMode = "default", updatedAt = 0)
        assertNull(TerminalSetting.permissionText(session, agent = bare))
        assertEquals(listOf("modelCard"), TerminalSetting.all(session, agent = bare).map { it.id })
    }

    /** An agent with no effort levels reads the model alone. */
    @Test
    fun anAgentWithoutEffortsReadsTheModelAlone() {
        val bare = AgentInfo(agent = "aider", available = true, models = listOf(AgentOption(id = "auto", label = "Auto")),
                             defaultModel = "auto",
                             permissionModes = listOf(AgentOption(id = "default", label = "Ask when needed")),
                             defaultPermissionMode = "default")
        val session = Session(sessionID = "s", deviceID = "d", agent = "aider", title = "Storybook", cwd = "/tmp",
                              control = SessionControl.terminal, model = "auto", permissionMode = "default",
                              effort = "high", updatedAt = 0)
        assertNull(TerminalSetting.effortText(session, agent = bare))
        assertEquals("Auto", TerminalSetting.modelCardText(session, agent = bare))
        assertEquals(listOf("Auto", "Ask when needed"), TerminalSetting.all(session, agent = bare).map { it.text })
    }

    /**
     * An unknown agent keeps whatever the device reported. Amendment A17 still holds for an agent the
     * app has never met: with no `AgentInfo` at hand there is no list to say the setting does not
     * exist, so the raw ids are shown rather than dropped.
     */
    @Test
    fun unknownAgentKeepsItsIDs() {
        val session = Session(sessionID = "s", deviceID = "d", agent = "aider", title = "Port", cwd = "/tmp",
                              control = SessionControl.terminal, model = "some-model", permissionMode = "yolo",
                              effort = "high", updatedAt = 0)
        assertEquals(listOf("some-model high", "yolo"), TerminalSetting.all(session, agent = null).map { it.text })
    }
}
