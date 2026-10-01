package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The iPhone's UI tests of dictation, on the scripted platform `--voice-preview` swaps in, so no
 * test ever opens the microphone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class ChatVoiceUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun run(test: String, arguments: List<String> = DemoApp.launchArguments, steps: ChatDriver.() -> Unit) =
        DemoApp(compose, test, arguments).use { app -> ChatDriver(compose, app).steps() }

    /**
     * `docs/DESIGN.md` § "The composer": dictation offers one button, Done. It stands where Send
     * stands, keeps the transcript in the message field, and leaves sending to the ordinary Send
     * button. There is no Cancel: a dictation nobody wants is edited or cleared like any other draft.
     */
    @Test
    fun dictationOffersOnlyDoneAndFillsTheField() = run("testDictationOffersOnlyDoneAndFillsTheField") {
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        val send = frame("composer.send")
        tap("composer.voice")
        waitFor("voice.done", 15_000)
        await("Done is live once the microphone is") { isEnabled("voice.done") }
        assertFalse("and it is the only way out", exists("voice.cancel"))
        assertFalse("nothing stops and sends in one tap", exists("voice.stop"))
        assertFalse("Send is not offered while listening", exists("composer.send"))
        assertFalse("and neither is anything else", exists("composer.attach"))
        assertFalse(exists("composer.voice"))
        assertFalse("the chips go with the row dictation replaced", exists("composer.modelCard"))
        assertTrue("one quiet line says what dictation is doing", exists("voice.status"))
        // Done takes the slot and the height Send had: it is the one primary in the row while
        // listening.
        val done = frame("voice.done")
        assertTrue("Done stands where Send stands, against the trailing edge", abs(done.right - send.right) < 2)
        assertTrue("on the same row", abs(done.center.y - send.center.y) < 12)
        assertEquals("at Send's size", send.height, done.height, 2f)
        attach("22-voice-listening")

        tap("voice.done")
        await("the transcript lands in the message field, not in a panel", 15_000) { value("composer.prompt").contains("auth suite") }
        val dictated = value("composer.prompt")
        waitFor("composer.send", 15_000)
        assertTrue("and sending it is the ordinary, separate tap", isEnabled("composer.send"))
        // Polish is off, so the field holds what will be sent the moment the transcript is final:
        // the slot is Send and nothing is spinning in it.
        assertFalse("with no spinner left in the slot Done stood in", exists("composer.working"))
        assertFalse("and no Done either", exists("voice.done"))
        attach("23-voice-done")

        // A second dictation adds to the draft rather than replacing it, and ending it is the same
        // one button.
        tap("composer.voice")
        waitFor("voice.done", 15_000)
        await("Done live again") { isEnabled("voice.done") }
        tap("voice.done")
        await("the draft the second dictation started from is still there", 15_000) {
            exists("composer.send") && value("composer.prompt").startsWith(dictated) && value("composer.prompt").length > dictated.length
        }
    }

    /**
     * `docs/DESIGN.md` § "The composer" → **While dictation runs, the field follows the words**: the
     * last line stays in view while the words arrive, and Done leaves the field where they ended.
     */
    @Test
    fun aLongDictationKeepsItsLastLineInView() = run(
        "testALongDictationKeepsItsLastLineInView",
        DemoApp.launchArguments + listOf("--voice-transcript=long", "--field-scroll-probe"),
    ) {
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        // What eight lines are worth, typed the way the cap is measured elsewhere and then taken
        // back out, so dictation starts from an empty draft and the height it settles at can be
        // held against this one.
        type("composer.prompt", "1\n2\n3\n4\n5\n6\n7\n8")
        assertEquals("eight lines stand in the field", 7, value("composer.prompt").count { it == '\n' })
        val eightLines = frame("composer.prompt").height
        node("composer.prompt").performTextClearance()
        await("the draft is empty again") { value("composer.prompt").isEmpty() }
        node("chat.transcript").performTouchInput { click(Offset(width * 0.5f, height * 0.25f)) }
        await("and the keyboard is down, as it is for dictation") { !isFocused("composer.prompt") }

        tap("composer.voice")
        waitFor("voice.done", 15_000)
        await("the scripted dictation reaches the draft, every partial of it", 20_000) { value("composer.prompt").contains("tomorrow morning") }
        assertEquals("the field grew to eight lines and no further", eightLines, frame("composer.prompt").height, 1f)
        await("and it is scrolled to the words that just arrived, not held on the first") { fieldShowsItsLastLine() }
        attach("ios-round31-dictation-follows")

        tap("voice.done")
        waitForAbsence("voice.done", 15_000)
        assertTrue("and leaves the field where the words ended", fieldShowsItsLastLine())
    }

    /**
     * `docs/DESIGN.md` § "The composer": the glow is meant to be seen, not found. The scripted
     * platform is pinned at rest, at conversational speech and at the top of its range, and each is
     * pictured.
     */
    @Test
    fun listeningGlowIsSeenAtEveryLevel() {
        for (level in listOf("0", "0.5", "1")) {
            run("testListeningGlowIsSeenAtEveryLevel", DemoApp.launchArguments + "--voice-level=$level") {
                openSession(DemoFixtures.liveSessionID)
                waitForLiveTurnToEnd()
                tap("composer.voice")
                waitFor("voice.done", 15_000)
                await("listening") { isEnabled("voice.done") }
                // The glow rises fast and falls slow; half a second is past both, so the frame is
                // this level's steady state rather than a rise.
                pause(500)
                attach("83-voice-glow-level-$level")
                tap("voice.done")
            }
        }
    }

    /**
     * `docs/DESIGN.md` § "Polishing what you dictated": the words land at once, the status line says
     * the model is working, and the dictated span alone is replaced with "Polished · Undo" under the
     * field until the next edit. § "The composer" → **Done becomes a spinner, and the spinner
     * becomes Send**.
     */
    @Test
    fun dictationIsPolishedAndOneUndoAway() = run("testDictationIsPolishedAndOneUndoAway") {
        turnPolishOn()
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        // Where Send stands before a word is spoken, so the spinner can be held against it.
        val send = frame("composer.send")
        tap("composer.voice")
        waitFor("voice.done", 15_000)
        await("listening") { isEnabled("voice.done") }
        tap("voice.done")

        // The words the recogniser produced are in the field the instant dictation ends, fillers
        // and all.
        await("the dictated words land unpolished", 15_000) { value("composer.prompt").contains("the the") }
        val dictated = value("composer.prompt")
        // The tap on Done was answered at once, and the slot is still not something anyone can tap.
        waitFor("composer.working", 15_000)
        val working = frame("composer.working")
        assertFalse("Send is not offered while the model is still writing", exists("composer.send"))
        await("the status line says the model is working", 15_000) { exists("chat.status") && label("chat.status").contains("Polishing") }
        attach("ios-round28-voice-working")
        assertTrue("the spinner stands where Send stands, against the trailing edge", abs(working.right - send.right) < 2)
        assertEquals("at Send's size", send.height, working.height, 2f)
        assertFalse("and Done went with the microphone", exists("voice.done"))

        waitFor("composer.polished", 20_000)
        val polished = value("composer.prompt")
        assertFalse("the doubled word is gone", polished.contains("the the"))
        assertFalse("and so is the filler", polished.contains("um "))
        // The field holds what will be sent, so the slot is Send again.
        waitFor("composer.send", 10_000)
        waitForAbsence("composer.working", 10_000)
        attach("ios-round28-voice-send")

        tap("composer.polishUndo")
        assertEquals("Undo puts the words back exactly as they were dictated", dictated, value("composer.prompt"))
        assertFalse("and the note goes with them", exists("composer.polished"))
    }

    /**
     * Settings, Voice group: dictation polish on, with a model chosen. The Settings screen is
     * another feature's, so this sets what its switch and its model row write.
     */
    private fun ChatDriver.turnPolishOn() {
        app.model.settings.polishEnabled = true
        app.model.settings.polishModel = "gpt-4.1-mini"
    }

    /** Where the message field is scrolled, as the probe beside it reports: "<offset>/<end>" in points. */
    private fun ChatDriver.fieldScroll(): Pair<Double, Double>? {
        if (!exists("composer.prompt.scroll")) return null
        val numbers = label("composer.prompt.scroll").split("/").mapNotNull { it.toDoubleOrNull() }
        return if (numbers.size == 2) numbers[0] to numbers[1] else null
    }

    /** Whether the last line of the draft is the one in view: there is more text than the field is tall, and the text has moved all the way down. */
    private fun ChatDriver.fieldShowsItsLastLine(): Boolean {
        val (offset, end) = fieldScroll() ?: return false
        return end > 1 && abs(offset - end) < 2
    }
}
