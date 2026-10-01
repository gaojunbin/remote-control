package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The iPhone's UI tests of the conversation itself (`ios/UITests/RemoteControlUITests.swift`),
 * driving the demo through the same steps and asserting the same things, with a picture wherever
 * the iPhone's test takes one.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class ChatConversationUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun run(test: String, arguments: List<String> = DemoApp.launchArguments, steps: ChatDriver.() -> Unit) =
        DemoApp(compose, test, arguments).use { app -> ChatDriver(compose, app).steps() }

    @Test
    fun sessionsAndChat() = run("testSessionsAndChat") {
        // The sessions list paints from the demo hello.
        waitFor("session.${DemoFixtures.liveSessionID}")
        attach("01-sessions")
        // Opening a session shows the transcript and the composer.
        openSession(DemoFixtures.liveSessionID)
        assertTrue("the send button is a separate control", exists("composer.send"))
        attach("02-chat")
        // The iPhone's steps outlast the scripted turn: its message starts a turn of its own.
        waitForLiveTurnToEnd()
        // A message goes out and the draft is cleared.
        type("composer.prompt", "run the suite again")
        assertTrue("send is enabled once there is a draft", isEnabled("composer.send"))
        tap("composer.send")
        attach("03-sent")
    }

    /**
     * Amendment A12: the message is on screen the moment Send is tapped, under the request id the
     * device will echo, and the device's own event replaces it in place rather than adding a second
     * copy. The scripted device takes three seconds over its echo under `--ui-testing`.
     */
    @Test
    fun sentMessageAppearsBeforeTheDeviceConfirmsIt() = run("testSentMessageAppearsBeforeTheDeviceConfirmsIt") {
        openSession(DemoFixtures.erroredSessionID)
        type("composer.prompt", "and one more thing")
        tap("composer.send")
        // Not "eventually": the bubble is drawn from the app's own state, so it is there before the
        // device has been heard from at all.
        waitFor("chat.message.sending", 2_000)
        assertTrue("with the words that were typed", label("chat.message.sending").contains("and one more thing"))
        assertTrue("and a word saying it is on its way", label("chat.message.sending").contains("sending"))
        assertEquals("the field is clear, so the next message can be typed at once", "", value("composer.prompt"))
        attach("40-send-pending")
        // The device's event arrives under the same id and takes the row over.
        waitForAbsence("chat.message.sending", 15_000)
        waitFor("chat.message", 10_000)
        assertEquals(
            "exactly once: the echo replaced the bubble rather than adding one",
            1,
            nodes("chat.message").count { ChatDriver.label(it).contains("and one more thing") },
        )
        attach("41-send-confirmed")
    }

    /** A tap that lands anywhere but the message field puts the keyboard away, and the draft it was holding survives. */
    @Test
    fun tappingOutsideTheFieldPutsTheKeyboardAway() = run("testTappingOutsideTheFieldPutsTheKeyboardAway") {
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        type("composer.prompt", "half a thought")
        assertTrue("the field has the keyboard", isFocused("composer.prompt"))
        attach("25-keyboard-up")
        // A quarter of the way down the transcript is content, not a control.
        node("chat.transcript").performTouchInput { click(Offset(width * 0.5f, height * 0.25f)) }
        await("the keyboard put away") { !isFocused("composer.prompt") }
        assertEquals("and the draft it was holding survives", "half a thought", value("composer.prompt"))
        attach("26-keyboard-dismissed")
    }

    /** A tap on a control still acts on the first tap, keyboard up or not: the tap that puts the keyboard away consumes nothing. */
    @Test
    fun toolCardOpensOnTheFirstTapWhileTheKeyboardIsUp() = run("testToolCardOpensOnTheFirstTapWhileTheKeyboardIsUp") {
        // Simple is the reading default and draws no tool call at all, so the card this is about
        // exists only at Detailed.
        chooseDetailedTranscript()
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        tap("composer.prompt")
        assertTrue("the field has the keyboard", isFocused("composer.prompt"))
        waitFor("chat.tool.tool-5", 10_000)
        tap("chat.tool.tool-5")
        await("one tap opened the card rather than only putting the keyboard away", 10_000) { hasText("100 passed in 52.4s") }
        attach("29-tool-card-keyboard-up")
    }

    /** Scrolling away from the foot of the transcript offers the way back down, and taking it returns to the newest message. */
    @Test
    fun jumpToLatestAppearsWhenTheReaderLeavesTheBottom() = run("testJumpToLatestAppearsWhenTheReaderLeavesTheBottom") {
        // At Simple the demo transcript is shorter than the screen and there is nowhere to scroll
        // away to; Detailed is the level with a tail.
        chooseDetailedTranscript()
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurn()
        pause(500)
        assertFalse("a conversation that opens at its newest message offers nothing", exists("chat.jumpToLatest"))
        node("chat.transcript").performTouchInput { swipeDown() }
        node("chat.transcript").performTouchInput { swipeDown() }
        waitFor("chat.jumpToLatest", 10_000)
        assertEquals("Jump to latest", label("chat.jumpToLatest"))
        attach("27-jump-to-latest")
        tap("chat.jumpToLatest")
        waitForAbsence("chat.jumpToLatest", 10_000)
        assertTrue("and the newest message is what is on screen", onScreen("source of the flake"))
        attach("28-jump-tapped")
    }

    /**
     * The way back down lands at the very end however far up the reader went; the button leaves
     * only once the tail is really on screen.
     */
    @Test
    fun jumpToLatestLandsAtTheTailFromFarUp() = run("testJumpToLatestLandsAtTheTailFromFarUp") {
        chooseDetailedTranscript()
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurn()
        val last = growTranscript(messages = 15)
        // The transcript measures itself, and a keyboard takes half of it.
        node("chat.transcript").performTouchInput { click(Offset(width * 0.5f, height * 0.2f)) }
        await("the keyboard away before the transcript is measured") { !isFocused("composer.prompt") }
        repeat(12) { node("chat.transcript").performTouchInput { swipeDown(durationMillis = 120) } }
        waitFor("chat.jumpToLatest", 10_000)
        attach("30-far-above-the-tail")
        tap("chat.jumpToLatest")
        waitForAbsence("chat.jumpToLatest", 15_000)
        await("the newest message is drawn", 10_000) { hasText(last) }
        assertTrue("and one tap landed at the very end of the transcript", onScreen(last))
        attach("31-jump-from-far-up")
    }

    /**
     * `docs/DESIGN.md` § "The timeline" → "Messages from other agents": what a teammate session
     * reported is the agent's side of the conversation, drawn on the left as a muted block and
     * hidden at Simple with the rest of the agent's working.
     */
    @Test
    fun agentMessageSitsOnTheAgentsSideAndSimpleHidesIt() = run("testAgentMessageSitsOnTheAgentsSideAndSimpleHidesIt") {
        openSession(DemoFixtures.sharedSessionID)
        await("the transcript around it", 20_000) { hasText("Read the fact sheet") }
        assertFalse("what another agent filed is not drawn at Simple", exists("chat.message.agent"))
        attach("ios-agent-message-simple")
        tap("nav.back")
        chooseDetailedTranscript()
        openSession(DemoFixtures.sharedSessionID)
        waitFor("chat.transcript")
        // The transcript opens at its newest message and lays its rows out lazily, and the report
        // sits near the top, so it is scrolled into view rather than waited for.
        await("Detailed draws it with the agent's other workings", 20_000) {
            if (!exists("chat.message.agent")) node("chat.transcript").performTouchInput { swipeDown() }
            exists("chat.message.agent")
        }
        assertTrue("and never says the person said it", label("chat.message.agent").contains("From another agent"))
        assertTrue("the caption stands above the words nobody typed", hasText("from another agent"))
        // Compose has no frame for a row it has not laid out, so each row is measured while it is
        // on screen: across the screen is all the assertions compare.
        val agent = frame("chat.message.agent")
        await("the person's own message", 10_000) {
            if (!exists("chat.message")) node("chat.transcript").performTouchInput { swipeDown() }
            exists("chat.message")
        }
        val mine = frame("chat.message")
        assertTrue("the agent's block starts at the leading margin", agent.left < mine.left)
        assertTrue("at the width assistant text uses rather than hugging its words", agent.width > mine.width)
        // The iPhone's picture is taken where its scroll up stopped, with the report just under the
        // top of the transcript.
        // A scroll with no fling after it, so the row is still where it was measured.
        pause(600)
        node("chat.transcript").performScrollToNode(hasTestTag("chat.message.agent"))
        scrollBy("chat.transcript", frame("chat.message.agent").top - frame("chat.transcript").top - 45f)
        attach("ios-agent-message-detailed")
    }

    /**
     * `docs/DESIGN.md` § "Status vocabulary" → **A notification opens its session in place**: a link
     * for session B while session A is open replaces A with B, Back returns to the list rather than
     * to A, and B is streaming with its composer the moment it is on screen.
     */
    @Test
    fun aLinkOpensItsSessionOverAnOpenOneAndKeepsItsComposer() = run("testALinkOpensItsSessionOverAnOpenOneAndKeepsItsComposer") {
        openSession(DemoFixtures.liveSessionID)
        app.model.handle(URI("remotecontrol://session?device=${DemoFixtures.macDeviceID}&id=${DemoFixtures.approvalSessionID}"))
        await("the linked session on screen", 30_000) { app.model.chat?.sessionID == DemoFixtures.approvalSessionID && exists("composer.prompt") }
        waitFor("chat.${DemoFixtures.macDeviceID}/${DemoFixtures.approvalSessionID}")
        assertTrue("with its composer, not a spinner that waits for a tap", exists("composer.prompt"))
        attach("ios-link-opens-in-place")
        // One conversation on the stack, so Back is the list.
        tap("nav.back")
        await("Back returns to the list, not to the session it replaced", 15_000) {
            exists("session.${DemoFixtures.liveSessionID}") && !exists("composer.prompt")
        }
    }

    /**
     * A session the five-hour window stopped: the notice above the transcript names the time it
     * comes back, Change opens a picker with the bounds, and Cancel takes the resume away at once.
     */
    @Test
    fun pausedSessionShowsItsResumeAndCancelsIt() = run("testPausedSessionShowsItsResumeAndCancelsIt") {
        openSession(DemoFixtures.pausedSessionID)
        waitFor("chat.resumeNotice", 15_000)
        assertTrue("naming what happened and when it comes back", label("chat.resumeNotice").startsWith("Paused by the usage limit"))
        await("the turn that ran into the limit ends with it") { hasText("Ended at the usage limit") }
        assertTrue("with the device's own row under it", hasText("Resume scheduled for"))
        attach("ios-round33-resume-banner")
        // Change opens the smallest time picker the platform has.
        tap("notice.action")
        waitFor("resume.picker", 10_000)
        assertTrue("with the bounds under it", hasText("eight days away"))
        tapButton("Close")
        await("closing it leaves the resume alone", 10_000) { exists("chat.resumeNotice") && !exists("resume.picker") }
        // Cancel removes it at once, with no confirmation.
        tap("notice.secondaryAction")
        waitForAbsence("chat.resumeNotice", 10_000)
        await("the timeline records it") { hasText("Resume cancelled") }
    }

    /**
     * Sends [messages] messages and returns the text of the last one: a dozen more exchanges put the
     * tail pages away, and every third one is long enough to wrap over several lines.
     */
    private fun ChatDriver.growTranscript(messages: Int): String {
        val long = "and this one runs on, because a row that wraps over half a screen is what " +
            "a list guessing the height of what it has not laid out gets wrong"
        for (index in 1..messages) {
            val text = if (index % 3 == 0) "again $index $long" else "again $index"
            type("composer.prompt", text)
            await("the draft reached the composer") { isEnabled("composer.send") }
            tap("composer.send")
        }
        await("the last of the messages is in the transcript", 20_000) { hasText("again $messages") }
        return "again $messages"
    }

    /** Whether a text is within the transcript's visible part — the iPhone's `isHittable`. */
    private fun ChatDriver.onScreen(fragment: String): Boolean {
        val transcript = frame("chat.transcript")
        return texts(fragment).any { frame(it).overlaps(transcript) && frame(it).bottom <= transcript.bottom + 1 }
    }
}
