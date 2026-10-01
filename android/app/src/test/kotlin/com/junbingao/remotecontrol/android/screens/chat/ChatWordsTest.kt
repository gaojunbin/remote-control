package com.junbingao.remotecontrol.android.screens.chat

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.MessageDelivery
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload
import com.junbingao.remotecontrol.core.state.OptimisticMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the conversation's rows and the model card say and measure, without a screen. */
class ChatWordsTest {
    private val sent = UserMessagePayload(text = "run the suite again")

    @Test
    fun aMessageNamesWhereItIsOnItsWay() {
        val pending = OptimisticMessage(id = "r1", text = "run the suite again")
        assertEquals("You said: run the suite again, sending", UserMessageWords.spoken(sent, pending, isUnconfirmed = false))
        assertEquals("chat.message.sending", UserMessageWords.identifier(sent, pending, isUnconfirmed = false))
        assertEquals("Delivery unconfirmed", UserMessageWords.pendingLabel(pending, isUnconfirmed = true))
        assertEquals("chat.message.unconfirmed", UserMessageWords.identifier(sent, pending, isUnconfirmed = true))
    }

    @Test
    fun aSteeredMessageIsWaitingForTheAgentNotTheDevice() {
        val steering = OptimisticMessage(id = "r2", text = "run the suite again", isSteering = true)
        assertEquals("the agent will read it at its next step", UserMessageWords.pendingLabel(steering, isUnconfirmed = true))
        assertEquals("chat.message.steering", UserMessageWords.identifier(sent, steering, isUnconfirmed = true))
    }

    @Test
    fun theDevicesWordForItWins() {
        val delivered = sent.copy(delivery = MessageDelivery.delivered)
        assertEquals("chat.message.delivered", UserMessageWords.identifier(delivered, pending = null, isUnconfirmed = false))
        assertEquals("You said: run the suite again", UserMessageWords.spoken(delivered, pending = null, isUnconfirmed = false))
        val absorbed = sent.copy(delivery = MessageDelivery.absorbed)
        assertEquals("You said: run the suite again, will be re-sent", UserMessageWords.spoken(absorbed, pending = null, isUnconfirmed = false))
        val resumed = sent.copy(source = EventSource.resume)
        assertEquals("You said: run the suite again, sent for you after the limit reset", UserMessageWords.spoken(resumed, pending = null, isUnconfirmed = false))
    }

    @Test
    fun theModelCardMeasuresEveryModelWithEveryLevel() {
        val pairs = ModelCardSizing.pairs(DemoFixtures.claude, model = "Sonnet 4.5")
        assertEquals("two models by two levels", 4, pairs.size)
        assertEquals(ModelCardSizing.Pair("Opus 4.1", "High"), pairs.last())
        val pi = ModelCardSizing.pairs(DemoFixtures.pi, model = "Claude Sonnet 4.5")
        assertEquals("every model pi lists, with each thinking level", DemoFixtures.pi.models.size * DemoFixtures.pi.efforts.size, pi.size)
        assertEquals("an agent that lists no model measures the one the row draws", listOf(ModelCardSizing.Pair("Grok", null)), ModelCardSizing.pairs(null, model = "Grok"))
    }
}
