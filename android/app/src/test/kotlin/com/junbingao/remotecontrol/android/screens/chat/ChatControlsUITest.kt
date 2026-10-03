package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
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

/** The iPhone's UI tests of the session's own controls: the model card, the permission chip and the `/` menu. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class ChatControlsUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun run(test: String, arguments: List<String> = DemoApp.launchArguments, steps: ChatDriver.() -> Unit) =
        DemoApp(compose, test, arguments).use { app -> ChatDriver(compose, app).steps() }

    /**
     * Amendment A21 and `docs/DESIGN.md` § "The composer": one chip for the model, the effort and
     * the speed, opening a card that holds all three. The haptic on each stop cannot be asserted
     * from a test; the effort word following the thumb can.
     */
    @Test
    fun modelCardCarriesModelEffortAndSpeed() = run("testModelCardCarriesModelEffortAndSpeed") {
        openSession(DemoFixtures.codexSharedSessionID)
        waitFor("composer.modelCard", 15_000)
        assertEquals("reading the model with the effort after it", "GPT-5.4 Codex, effort Medium", value("composer.modelCard"))
        assertFalse("and the effort is no longer a chip of its own", exists("composer.effort"))
        tap("composer.modelCard")
        waitFor("composer.speed", 10_000)
        assertEquals("which starts at the standard speed", "Standard", value("composer.speed"))
        assertTrue("with the effort slider under it", exists("composer.effort"))
        val chipWidth = frame("composer.modelCard").width
        attach("44-model-card")

        // A tap on a stop moves there. The last stop is the far end of the track, so the tap lands
        // on it whatever width the card took.
        node("composer.effort").performTouchInput { click(Offset(width * 0.97f, height * 0.5f)) }
        await("the slider snaps to the agent's own levels") { value("composer.effort") == "High" }
        await("and the word in the first row follows the thumb", 10_000) { labelled("High").isNotEmpty() }
        await("the effort change finishes before changing speed") { isEnabled("composer.speed") }
        tap("composer.speed")
        await("one tap raises the tier the agent named, drawn before the device answers") { value("composer.speed") == "Fast" }
        attach("45-model-card-fast")
        // `docs/DESIGN.md` § "The control row": the gauge is an icon, so nothing beside it shifts
        // whatever the level and the tier.
        assertEquals("the gauge keeps its width through a level and a tier change", chipWidth, frame("composer.modelCard").width, 0.5f)
        await("and says the level and the tier the needle and the bolt draw") { value("composer.modelCard") == "GPT-5.4 Codex, effort High, Fast" }
        await("the speed change finishes before using the model card again") { isEnabled("composer.speed") }
    }

    /** An agent that lists no tier draws no speed control at all, rather than a disabled one with a caption explaining itself. */
    @Test
    fun modelCardHasNoSpeedToggleForClaude() = run("testModelCardHasNoSpeedToggleForClaude") {
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        tap("composer.modelCard")
        waitFor("composer.model", 10_000)
        assertFalse("and Claude, which has no faster tier, is offered none", exists("composer.speed"))
        attach("46-model-card-no-speed")
    }

    /** Amendment A26: pi's permission modes are the device's own, so the composer row carries the permission chip exactly as Codex's does. */
    @Test
    fun piSessionDrawsItsPermissionChip() = run("testPiSessionDrawsItsPermissionChip") {
        openSession(DemoFixtures.piSessionID)
        waitFor("composer.modelCard", 15_000)
        assertEquals("Model", label("composer.modelCard"))
        assertEquals("reading the model and the thinking level pi is set to", "Claude Sonnet 4.5, effort Medium", value("composer.modelCard"))
        waitFor("composer.permissions", 10_000)
        assertEquals("reading the mode the extension is enforcing", "Ask when needed", value("composer.permissions"))
        attach("85-pi-composer")
        // The levels pi does have are still a slider on the card.
        tap("composer.modelCard")
        waitFor("composer.model", 10_000)
        assertTrue("with the thinking-level slider on it", exists("composer.effort"))
        attach("86-pi-model-card")
    }

    /**
     * Amendment A27: `/` opens the terminal's own menu over the keyboard, the letters after it
     * filter the list, a tap writes the command into the field, and Send runs it.
     */
    @Test
    fun commandPanelOpensFiltersAndRunsOnAPiSession() = run("testCommandPanelOpensFiltersAndRunsOnAPiSession") {
        openSession(DemoFixtures.piSessionID)
        type("composer.prompt", "/")
        waitFor("composer.commands", 10_000)
        assertTrue("and pi distinguishes more than one source, so the rows are sectioned", labelled("Prompts").isNotEmpty())
        assertTrue("with this project's own prompt templates on it", exists("command.release-notes"))
        attach("90-commands-panel")
        // Letters after the slash filter the list by the name's prefix.
        type("composer.prompt", "com")
        waitFor("command.compact", 10_000)
        waitForAbsence("command.changelog", 10_000)
        attach("91-commands-filtered")
        // Taking a row writes the command, with the space that shows where its argument goes, and
        // hands over to the hint line under the card.
        tap("command.compact")
        waitFor("composer.commandHint", 10_000)
        waitForAbsence("composer.commands", 10_000)
        assertEquals("the field holds the command and the space after it", "/compact ", value("composer.prompt"))
        attach("92-command-hint")
        // Send runs it, and what it did comes back as a notice rather than as something the agent said.
        tap("composer.send")
        await("the transcript reports the compaction", 20_000) { hasText("compacted", ignoreCase = true) }
        attach("93-command-ran")
    }

    /** A command runs between turns, never inside one: the rows are dimmed, the card says why, and Send does not act until the turn is over. */
    @Test
    fun commandPanelWaitsForTheRunningTurnOnACodexThread() = run("testCommandPanelWaitsForTheRunningTurnOnACodexThread") {
        openSession(DemoFixtures.codexSharedSessionID)
        type("composer.prompt", "/usage")
        waitFor("composer.commands", 10_000)
        assertTrue("and says once, under the rows, why nothing can be run yet", labelled("Available when the turn finishes").isNotEmpty())
        assertFalse("Send does not act", isEnabled("composer.send"))
        attach("94-commands-waiting")
        // Codex has one source, so nothing is sectioned.
        assertTrue("one group draws no header at all", labelled("Built-in").isEmpty())
        tap("chat.stop")
        await("the turn ends and the rows come back", 20_000) { labelled("Available when the turn finishes").isEmpty() }
        assertTrue("and Send acts again", isEnabled("composer.send"))
        tap("composer.send")
        await("information a terminal would have printed arrives as a tool call", 20_000) { usageCard() != null }
        val card = usageCard()!!
        // XCUITest taps only once the app is idle: the scroll that brought the card on screen has
        // settled, and the card's growth is the content's rather than the scroll's.
        pause(600)
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(card), useUnmergedTree = true)[0].performTouchInput { click() }
        await("and opens on what it printed", 10_000) { hasText("5-hour window", ignoreCase = true) }
        attach("95-command-output")
    }

    /**
     * Amendment A40: Claude offers one command, `/compact`, so the panel lists that row and no
     * other, with no sections to draw for a single source.
     */
    @Test
    fun claudeSessionOffersOnlyCompact() = run("testClaudeSessionOffersOnlyCompact") {
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        type("composer.prompt", "/")
        waitFor("composer.commands", 10_000)
        waitFor("command.compact", 10_000)
        assertFalse("and nothing Claude does not offer", exists("command.review"))
        assertTrue("one source draws no section header", labelled("Built-in").isEmpty())
        attach("96-claude-command-panel")
        // `/compact` takes no argument, so taking the row leaves the command ready to run on Send,
        // with no space and no hint line to hand over to.
        tap("command.compact")
        waitFor("composer.send", 10_000)
        assertEquals("the field holds the command, ready to run", "/compact", value("composer.prompt"))
        assertTrue("and Send runs it", isEnabled("composer.send"))
    }

    /** The tag of the tool row whose label begins with `/usage`, once there is one. */
    private fun ChatDriver.usageCard(): String? =
        app.model.chat?.timeline?.entries?.firstOrNull { it.toolCall?.tool?.startsWith("/usage") == true }?.let { "chat.tool.${it.id}" }
            ?.takeIf { exists(it) && label(it).startsWith("/usage") }
}
