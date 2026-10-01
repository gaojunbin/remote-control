package com.junbingao.remotecontrol.android.design

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** `ios/VerificationUI` § "Amendments A25 and A26": four names, four logos. */
class AgentLogoTest {
    @Test
    fun eachAgentIsDrawnByItsOwnLogo() {
        for (agent in listOf("claude", "codex", "grok", "pi")) assertNotNull("$agent has a logo", AgentLogo.vector(agent))
    }

    @Test
    fun anAgentNobodyKnowsHasNoLogoToDraw() {
        assertNull(AgentLogo.vector("aider"))
    }
}
