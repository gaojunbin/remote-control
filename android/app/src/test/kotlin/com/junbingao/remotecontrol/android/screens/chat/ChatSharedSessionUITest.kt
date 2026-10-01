package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The iPhone's UI tests of sessions a terminal holds or shares: what the composer offers, what it
 * shows instead, and how a question and an approval are answered from here.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class ChatSharedSessionUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun run(test: String, arguments: List<String> = DemoApp.launchArguments, steps: ChatDriver.() -> Unit) =
        DemoApp(compose, test, arguments).use { app -> ChatDriver(compose, app).steps() }

    /**
     * Amendment A10: an attached terminal session takes a message from here and its relayed
     * permission request is answered from the app. Amendment A19: while the device holds the
     * message it is a queue entry and nothing else, and the bubble appears when the CLI takes it.
     */
    @Test
    fun sharedSessionDeliversAndApproves() = run("testSharedSessionDeliversAndApproves") {
        openSession(DemoFixtures.sharedSessionID)
        // Amendment A20: the attached CLI asks a question the moment this opens, and the composer
        // answers it rather than sending anything.
        answerTheSharedQuestion("Remote control for your terminal agents")
        assertTrue("the composer is enabled on an attached session", isEnabled("composer.prompt"))
        assertFalse("an attached session never offers a takeover", exists("chat.takeover"))
        // The header already says the terminal owns this session, so the composer adds nothing.
        assertFalse("nothing is printed above the field", exists("composer.terminalNote"))
        assertFalse("a channel cannot hand bytes to a live CLI, so there is no attach button", exists("composer.attach"))
        assertTrue("what is left still sends", exists("composer.send"))
        // Amendment A40: the device types `/model` and `/effort` into the pseudo-terminal the shim
        // gave it, so the card is a control here.
        assertTrue("the model and the effort are typed into the terminal, so the card is live", exists("composer.modelCard"))
        assertFalse("and nothing stands in for a control that works (A17)", exists("composer.readonly.modelCard"))
        // Amendment A17: the permission mode has no command the device could type, so the value
        // is shown where the picker would be.
        assertFalse("the permission mode stays the terminal's", exists("composer.permissions"))
        assertTrue("and is shown as the value the terminal set", exists("composer.readonly.permissionMode"))
        assertEquals("by its raw id, because the agent's list does not know it", "auto", value("composer.readonly.permissionMode"))
        assertTrue("the model and the effort read as one value, not as two chips", value("composer.modelCard").endsWith(", effort High"))
        attach("06-shared-idle")

        type("composer.prompt", "mention the iOS app too")
        tap("composer.send")
        // Amendment A19: nothing is drawn for a message the device is only holding. It is one entry
        // in the queue until the CLI takes it.
        waitFor("composer.queue", 10_000)
        assertEquals("exactly one of them", "1 message", value("composer.queue"))
        assertFalse("and no bubble claims it reached the terminal", exists("chat.message.sending"))
        attach("07-shared-queued")

        waitFor("chat.message.delivered", 15_000)
        assertTrue("with what was typed", label("chat.message.delivered").contains("mention the iOS app too"))
        waitForAbsence("composer.queue", 10_000)
        attach("08-shared-delivered")

        waitFor("approval.primary", 15_000)
        attach("09-shared-approval")
        tap("approval.primary")
        waitForAbsence("approval.primary", 15_000)
        attach("10-shared-answered")
    }

    /**
     * Amendment A20: the terminal shows Claude's own dialog and this card at the same moment, and
     * whichever is answered first wins. The message field is the free-text answer, the button reads
     * Answer, and an earlier question the terminal answered says so.
     */
    @Test
    fun questionIsAnsweredFromTheComposer() = run("testQuestionIsAnsweredFromTheComposer") {
        openSession(DemoFixtures.sharedSessionID)
        waitFor("question.resolution", 15_000)
        assertEquals("answered in the terminal", label("question.resolution"))
        attach("32-question-answered-in-terminal")
        // The live one turns the composer into an answer.
        waitFor("chat.question", 15_000)
        await("the one primary in the row answers it rather than sending") { label("composer.send") == "Answer" }
        assertEquals("with the status line saying what is expected", "Waiting for your answer", label("chat.status"))
        type("composer.prompt", "Remote control for your terminal agents")
        attach("33-composer-answer")
        tap("composer.send")
        await("answering gives the composer back", 15_000) { label("composer.send") == "Send" }
        assertEquals("and the draft went with the answer", "", value("composer.prompt"))
        waitForAbsence("question.submit", 10_000)
        attach("34-question-answered-here")
    }

    /**
     * Amendment A42: the device types Escape into the terminal, so a shared Claude session has Stop
     * — refused in the device's words while a prompt is on screen, and ending the turn otherwise.
     */
    @Test
    fun sharedClaudeSessionStopsFromThePhone() = run("testSharedClaudeSessionStopsFromThePhone") {
        openSession(DemoFixtures.sharedSessionID)
        answerTheSharedQuestion("Remote control for your terminal agents")
        type("composer.prompt", "run the checks")
        tap("composer.send")
        // The terminal asks for approval; Stop cannot escape a prompt.
        waitFor("approval.primary", 30_000)
        waitFor("chat.stop", 5_000)
        tap("chat.stop")
        await("the refusal is the device's own sentence", 10_000) { labelled("answer the prompt first").isNotEmpty() }
        attach("97-stop-refused-over-a-prompt")
        // Answered, the turn runs on, and Stop ends it.
        tap("approval.primary")
        waitFor("chat.stop", 10_000)
        tap("chat.stop")
        waitForAbsence("chat.stop", 15_000)
        attach("98-stop-ended-the-turn")
    }

    /**
     * Amendment A40: the shim runs the Claude CLI inside a pseudo-terminal the device owns, so the
     * device types `/model` and `/effort` into it. The card is a control again; the permission mode,
     * which no command sets, is still the value the terminal chose (A17).
     */
    @Test
    fun sharedClaudeSessionTypesItsModelAndShowsItsPermissionMode() = run("testSharedClaudeSessionTypesItsModelAndShowsItsPermissionMode") {
        openSession(DemoFixtures.sharedSessionID)
        answerTheSharedQuestion("the device types it in")
        waitFor("composer.modelCard", 15_000)
        assertFalse("so nothing stands in for a control that works", exists("composer.readonly.modelCard"))
        assertFalse("while no command sets the permission mode", exists("composer.permissions"))
        assertTrue("which is shown as the value the terminal chose", exists("composer.readonly.permissionMode"))
        assertEquals("by its raw id, because the agent's own list does not know it", "auto", value("composer.readonly.permissionMode"))
        attach("ios-round47-shared-claude")

        tap("composer.modelCard")
        waitFor("composer.model", 10_000)
        assertTrue("with the slider the device would type /effort for", exists("composer.effort"))
        assertFalse("and no tier, because Claude names none", exists("composer.speed"))
        attach("ios-round47-shared-claude-card")

        // Choosing one sends `session.set`; the device types it into the terminal and answers once
        // the transcript has confirmed it.
        assertEquals("the terminal is on Opus to begin with", "Opus 4.1", value("composer.model"))
        tap("composer.model")
        waitFor("composer.model.claude-sonnet-4-5", 10_000)
        tap("composer.model.claude-sonnet-4-5")
        // Amendment A40: nothing is drawn before the terminal has taken it. The control waits,
        // disabled, while the device types the change in.
        await("the control waits rather than drawing the change", 5_000) { !isEnabled("composer.model") }
        assertEquals("and still reads the model that terminal is running", "Opus 4.1", value("composer.model"))
        attach("ios-round47-shared-claude-typing")
        await("the reply is what the card follows", 20_000) { value("composer.model") == "Sonnet 4.5" }
        assertTrue("and the wait ends with it", isEnabled("composer.model"))
        assertEquals("so the gauge says what that terminal now runs", "Sonnet 4.5, effort High", value("composer.modelCard"))
        attach("ios-round47-shared-claude-typed")
    }

    /**
     * Amendment A11: a Codex thread shared through the app-server daemon. The attachment carries
     * the settings, the attachments and an interrupt, so nothing is dimmed, and the daemon's four
     * decisions all reach the card.
     */
    @Test
    fun sharedCodexSessionKeepsEveryControl() = run("testSharedCodexSessionKeepsEveryControl") {
        openSession(DemoFixtures.codexSharedSessionID)
        assertFalse("the header names the attachment; the composer repeats nothing", exists("composer.terminalNote"))
        waitFor("composer.attach")
        assertTrue("and shared_settings keeps the chips", exists("composer.modelCard"))
        assertFalse("and A17 shows nothing where the control is live", exists("composer.readonly.modelCard"))
        waitFor("chat.stop", 10_000)
        // The field's placeholder is its accessible name.
        assertEquals("a steering agent joins the running turn instead of queueing behind it", "Message · will steer the turn", label("composer.prompt"))
        // The daemon's four decisions all render, stacked, with Allow primary.
        waitFor("approval.primary", 15_000)
        assertTrue("the session-wide option is offered", labelled("Allow for this session").isNotEmpty())
        assertTrue("and the execpolicy amendment", labelled("Always allow commands like this").isNotEmpty())
        assertTrue("with Deny kept apart from Allow", exists("approval.danger"))
        attach("12-codex-shared")

        // `docs/DESIGN.md` § "The model card": after the card comes the permission-mode picker, a
        // plain list of the agent's modes with the current one marked and nothing else.
        assertEquals("the chip reads the mode the daemon is on", "Ask when needed", value("composer.permissions"))
        tap("composer.permissions")
        await("shared_settings opens the agent's own modes", 10_000) { labelled("Never ask").isNotEmpty() }
        assertTrue("every one it lists", labelled("Ask for everything").isNotEmpty())
        attach("13-codex-permissions")
        tapButton("Never ask")
        await("and choosing one is drawn at once") { value("composer.permissions") == "Never ask" }

        waitFor("approval.primary", 10_000)
        tap("approval.primary")
        waitForAbsence("approval.primary", 15_000)
        attach("14-codex-answered")
    }

    /**
     * Amendment A28: Grok Build on a machine that is in the leader. The turn the terminal set off is
     * stoppable here and the pickers are live — and the attachment button is gone, because a Grok
     * prompt carries no images.
     */
    @Test
    fun sharedGrokSessionStopsAndRetunesButTakesNoAttachments() = run("testSharedGrokSessionStopsAndRetunesButTakesNoAttachments") {
        openSession(DemoFixtures.grokSharedSessionID)
        waitFor("chat.stop", 10_000)
        assertTrue("shared_settings keeps the model card a control", exists("composer.modelCard"))
        assertTrue("and the permission chip with it", exists("composer.permissions"))
        assertFalse("so nothing is shown where a control stands (A17)", exists("composer.readonly.modelCard"))
        assertFalse("while shared_attachments is false, so there is no attach button", exists("composer.attach"))
        assertFalse("an attached session explains nothing; it works", exists("chat.attachHint"))
        attach("90-grok-shared-composer")
        // The card behind the chip is the agent's own: its models, its four effort levels, and no
        // speed tier, because Grok Build lists none.
        tap("composer.modelCard")
        waitFor("composer.model", 10_000)
        assertTrue("with the effort the leader would set for every client", exists("composer.effort"))
        assertFalse("and no tier this agent never named", exists("composer.speed"))
        attach("91-grok-shared-model")
    }

    /** Amendment A10: a terminal session the device cannot attach says what the machine is missing instead of pretending the composer will work. */
    @Test
    fun terminalSessionExplainsHowToAttach() = run("testTerminalSessionExplainsHowToAttach") {
        openSession(DemoFixtures.attachHintSessionID)
        waitFor("chat.attachHint", 15_000)
        // Claude does advertise `takeover`, so the line above the field names the way out — and
        // the field itself still says the short sentence, which is the half that is the same on
        // every agent.
        assertTrue("the status line names the way out, because this agent has one", labelled("Controlled by the terminal · take over to send").isNotEmpty())
        assertEquals("and the field does not repeat the clause", "Controlled by the terminal", label("composer.prompt"))
        attach("11-attach-hint")
    }

    /**
     * Amendment A25: Grok Build writes its own update log, so a session a terminal started is
     * mirrored here — readable, not writable. Amendment A28: it is mirrored at all only because this
     * machine leaves `[cli] use_leader` off, and the hint under the status line says so.
     */
    @Test
    fun grokTerminalSessionShowsWhatItsLogKnows() = run("testGrokTerminalSessionShowsWhatItsLogKnows") {
        openSession(DemoFixtures.grokSessionID)
        waitFor("composer.readonly.modelCard", 15_000)
        assertEquals("Grok 4.6, effort High", value("composer.readonly.modelCard"))
        assertFalse("and the update log knows no permission mode, so no chip claims one", exists("composer.readonly.permissionMode"))
        assertFalse("nothing on this row is a control", exists("composer.modelCard"))
        // `docs/DESIGN.md` § "The composer": the way out is named only where the agent has one,
        // and Grok Build advertises no `takeover`.
        assertEquals("the field says who has this session and promises nothing else", "Controlled by the terminal", label("composer.prompt"))
        assertFalse("and nothing on the screen invites a tap that would be refused", hasText("take over", ignoreCase = true))
        attach("88-grok-terminal-composer")
        // Amendment A28: the hint is the only place the leader is named, and it names the command.
        waitFor("chat.attachHint", 15_000)
        assertTrue("which is the setup command and a restart, in one line", labelled("Run rc-client grok setup on the device, then restart Grok").isNotEmpty())
        attach("89-grok-leader-hint")
        // Amendment A44: the same gauge as a live session, and a tap shows the value the terminal
        // set in a menu with nothing to choose.
        tap("composer.readonly.modelCard")
        await("the tap shows what the terminal chose", 10_000) { labelled("Grok 4.6 High").isNotEmpty() }
        val value = labelled("Grok 4.6 High").first()
        assertFalse("as a value, not a choice", ChatDriver.isEnabled(value))
        assertTrue("and says where it was set", labelled("Set in the terminal").isNotEmpty())
        attach("ios-a44-terminal-value")
    }
}
