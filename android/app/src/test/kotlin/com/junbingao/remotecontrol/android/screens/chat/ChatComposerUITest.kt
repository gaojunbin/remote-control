package com.junbingao.remotecontrol.android.screens.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The iPhone's UI tests of the message bar: its rows, its field, its menus and the Up next list. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class ChatComposerUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @After
    fun ordinaryText() {
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun run(test: String, arguments: List<String> = DemoApp.launchArguments, steps: ChatDriver.() -> Unit) =
        DemoApp(compose, test, arguments).use { app -> ChatDriver(compose, app).steps() }

    /**
     * The field owns a row of its own and grows with the draft; everything else — the icons, the
     * session's controls and Send — shares the one row underneath it.
     */
    @Test
    fun composerFieldOwnsItsRowAndGrows() = run("testComposerFieldOwnsItsRowAndGrows") {
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        waitFor("composer.modelCard")
        for (tag in listOf("composer.attach", "composer.voice", "composer.modelCard", "composer.permissions", "composer.send")) {
            assertTrue("$tag is on the row below the field", exists(tag))
        }
        // Amendment A44: how you speak, what runs, what it may do — icons, in that order, each on
        // a target a thumb can hit. A fresh install transcribes on this phone, so the language is
        // among them.
        assertTrue("the phone listens, so the row offers its language", exists("composer.language"))
        val language = frame("composer.language")
        val model = frame("composer.modelCard")
        val permissions = frame("composer.permissions")
        val attachments = frame("composer.attach")
        val voice = frame("composer.voice")
        val send = frame("composer.send")
        val field = frame("composer.prompt")
        assertTrue("before the model card", language.center.x < model.center.x)
        assertTrue("which comes before the permissions", model.center.x < permissions.center.x)
        for ((tag, control) in listOf("composer.language" to language, "composer.modelCard" to model, "composer.permissions" to permissions)) {
            assertTrue("$tag is a 44-point target", control.width >= 43.5f && control.height >= 43.5f)
        }
        assertEquals("the gauge names itself", "Model", label("composer.modelCard"))
        assertEquals("and says what it draws", "Sonnet 4.5, effort High", value("composer.modelCard"))
        assertEquals("the language is Chinese until another is picked", "Chinese", value("composer.language"))
        assertEquals("Auto-accept edits", value("composer.permissions"))
        assertTrue("the field takes the whole width rather than sharing it", field.width > attachments.width * 4)
        assertTrue("the controls sit under the field, not beside it", attachments.top > field.bottom - 1)
        assertTrue("the + and the microphone lead, then the session's controls", attachments.left < language.left)
        assertTrue("and Send is pinned past all of them at the trailing edge", model.right < send.left)
        // One row means one row: nothing the composer draws sits below Send.
        for (control in listOf(attachments, voice, model, send)) {
            assertTrue("every control shares the one row under the field", abs(control.center.y - send.center.y) < 12)
        }
        assertTrue("Send sits against the trailing margin, not floating inside the row", 402 - send.right < 24)
        attach("20-composer-one-line")

        type("composer.prompt", "one")
        val oneLine = frame("composer.prompt").height
        type("composer.prompt", "\ntwo\nthree\nfour\nfive")
        attach("21-composer-five-lines")
        assertEquals("Return inserts a newline rather than sending", 4, value("composer.prompt").count { it == '\n' })
        val fiveLines = frame("composer.prompt").height
        assertTrue("five lines of draft make the field grow", fiveLines > oneLine * 2)
        // Eight lines is where growing stops and the text scrolls inside.
        type("composer.prompt", "\nsix\nseven\neight")
        val eightLines = frame("composer.prompt").height
        assertTrue("it is still growing at eight", eightLines > fiveLines)
        type("composer.prompt", "\nnine\nten\neleven\ntwelve")
        assertEquals("past the cap the field stops growing and scrolls instead", eightLines, frame("composer.prompt").height, 1f)
        attach("24-composer-capped")
    }

    /**
     * `docs/DESIGN.md` § "The composer": once the field scrolls, the scroll indicator runs down its
     * trailing edge while the draft is being scrolled. The field's fill is flat, so a dark pixel in
     * the strip along that edge is the indicator and nothing else.
     */
    @Test
    fun composerFieldShowsItsScrollIndicator() = run("testComposerFieldShowsItsScrollIndicator") {
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        type("composer.prompt", "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten")
        val capped = frame("composer.prompt").height
        // Typing leaves the draft at its end, so the scroll that has somewhere to go is the one
        // back towards the first line. The picture is taken while the indicator is up.
        node("composer.prompt").performTouchInput { swipeDown(durationMillis = 600) }
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        attach("31-composer-scroll-indicator")
        assertEquals("scrolling the draft does not resize the field", capped, frame("composer.prompt").height, 1f)
        val edge = frame("composer.prompt")
        assertTrue("the scroll indicator runs down the field's trailing edge", darkest(shot, edge.right - 12, edge.top + 4, edge.right - 1, edge.bottom - 4) < 0.9)
    }

    /** Fixed frames clip at the largest text sizes. The Send circle scales with the type, as the command panel's rows already do. */
    @Test
    fun sendCircleGrowsWithAccessibilityText() {
        RuntimeEnvironment.setFontScale(2f)
        run("testSendCircleGrowsWithAccessibilityText") {
            openSession(DemoFixtures.liveSessionID)
            waitFor("composer.send")
            assertTrue("and its circle is bigger than the default 48 at the largest text size", frame("composer.send").height > 48)
            attach("ios-send-circle-accessibility-size")
        }
    }

    /**
     * Amendment A43: what waits behind a turn is one control — a notepad carrying the count (A44) —
     * and its list takes a message back into the composer to be edited, and back to its own place
     * on Queue, the draft that was in the field set aside and returned. A swipe removes a row, and a
     * message that carries files is removed and never edited.
     */
    @Test
    fun queuedMessagesCanBeEditedAndRemoved() = run("testQueuedMessagesCanBeEditedAndRemoved", DemoApp.launchArguments + "--demo-queue") {
        openSession(DemoFixtures.liveSessionID)
        type("composer.prompt", "a note of my own")
        waitFor("composer.queue", 10_000)
        assertEquals("Up next", label("composer.queue"))
        assertEquals("counting the three messages", "3 messages", value("composer.queue"))
        assertTrue("first in the row, before how you speak (A44)", frame("composer.queue").center.x < frame("composer.language").center.x)
        assertTrue("and never a stack of them over the field", queueRow("Then run the full test suite") == null)
        tap("composer.queue")

        await("the chip opens the list", 10_000) { queueRow("Then run the full test suite") != null }
        val suite = queueRow("Then run the full test suite")!!
        val regression = queueRow("Add a regression test for the refresh race")
        val evidence = queueRow("CI log and a screenshot")
        assertNotNull("with every message in it", regression)
        assertNotNull(evidence)
        assertTrue("in the order they will go", frame(suite).top < frame(regression!!).top)
        assertTrue(frame(regression).top < frame(evidence!!).top)
        attach("99-queue-list")

        // Its files are on the device and nothing brings them back, so a tap on that row does
        // nothing at all.
        tapQueueRow("CI log and a screenshot")
        assertFalse("a message with files is not edited", within(2_000) { exists("composer.editingQueued") })
        assertTrue("and the list stays open", queueRow("Then run the full test suite") != null)

        tapQueueRow("Add a regression test for the refresh race")
        waitFor("composer.editingQueued", 10_000)
        assertTrue("with Cancel beside it", exists("composer.cancelEdit"))
        await("the message's words are in the field, in place of the draft") { value("composer.prompt") == "Add a regression test for the refresh race." }
        await("and it has left the line") { value("composer.queue") == "2 messages" }
        await("the field has the keyboard") { isFocused("composer.prompt") }
        // Typing carries on after the last word: that is where the caret is.
        type("composer.prompt", " And one for logout.")
        assertEquals("Add a regression test for the refresh race. And one for logout.", value("composer.prompt"))
        assertEquals("behind a running turn the edit goes back into the line", "Queue", label("composer.send"))
        attach("100-editing-a-queued-message")
        tap("composer.send")

        waitForAbsence("composer.editingQueued", 10_000)
        await("and the draft set aside is back in the field") { value("composer.prompt") == "a note of my own" }
        await("three in the line again") { value("composer.queue") == "3 messages" }

        tap("composer.queue")
        await("the list has the new words", 10_000) { queueRow("And one for logout.") != null }
        val edited = queueRow("And one for logout.")!!
        assertTrue("in the place the message left", frame(queueRow("Then run the full test suite")!!).top < frame(edited).top)
        assertTrue(frame(edited).top < frame(queueRow("CI log and a screenshot")!!).top)
        attach("101-back-in-its-place")

        // XCUITest's `swipeLeft()` uncovers the row's buttons; a swipe the length of the row is a
        // full swipe, which runs Remove at once.
        queueRowNode("Then run the full test suite").performTouchInput { swipeLeft(startX = width - 10f, endX = width - 10f - 120.dp.toPx()) }
        await("a swipe offers Remove", 5_000) { labelled("Remove").any { !it.boundsInRoot.isEmpty } }
        tapButton("Remove")
        await("and nothing asks before it goes", 10_000) { queueRow("Then run the full test suite") == null }
        tapButton("Done")
        await("the count drops with it") { value("composer.queue") == "2 messages" }
    }

    /**
     * `docs/DESIGN.md` § "The composer" → **Attachments are named for what they are**: "The Camera
     * item is offered only where a camera exists." Robolectric's phone has none.
     */
    @Test
    fun attachMenuOffersNoCameraWhereThereIsNone() = run("testAttachMenuOffersNoCameraWhereThereIsNone") {
        openSession(DemoFixtures.liveSessionID)
        waitForLiveTurnToEnd()
        tap("composer.attach")
        await("the menu offers Files", 10_000) { labelled("Files").isNotEmpty() }
        assertTrue("and Photos", labelled("Photos").isNotEmpty())
        assertTrue("and nothing for a camera this phone does not have", labelled("Camera").isEmpty())
        attach("ios-attach-menu-no-camera")
    }

    /**
     * The Photos item in the `+` menu opens the picker: the menu's item only sets the composer's
     * own picker going, because a picker built inside a menu leaves with the menu. The system's
     * picker runs out of the app, so what is checked is that it was asked for.
     */
    @Test
    fun photosOpensThePickerFromTheAttachMenu() = run("testPhotosOpensThePickerFromTheAttachMenu") {
        openSession(DemoFixtures.liveSessionID)
        tap("composer.attach")
        await("the menu offers Photos", 10_000) { labelled("Photos").isNotEmpty() }
        val instrumentation = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
        instrumentation.clearNextStartedActivities()
        tapButton("Photos")
        val started = instrumentation.nextStartedActivityForResult?.intent
        assertNotNull("tapping it opens the photo picker", started)
        assertTrue(
            "the system's photo picker, images only",
            started!!.action in setOf("android.provider.action.PICK_IMAGES", "androidx.activity.result.contract.action.PICK_IMAGES", "android.intent.action.OPEN_DOCUMENT", "android.intent.action.PICK"),
        )
        attach("68-photos-picker")
    }

    /** A row of the Up next list, found by its words. */
    private fun ChatDriver.queueRow(text: String) = nodes("queue.entry").firstOrNull { ChatDriver.words(it).contains(text) }

    private fun ChatDriver.queueRowNode(text: String): SemanticsNodeInteraction {
        val index = nodes("queue.entry").indexOfFirst { ChatDriver.words(it).contains(text) }
        check(index >= 0) { "no queued row with “$text”" }
        return nodeAt("queue.entry", index)
    }

    private fun ChatDriver.tapQueueRow(text: String) {
        queueRowNode(text).performTouchInput { click() }
        compose.waitForIdle()
    }

    /** The darkest pixel in a rectangle of the screen, given in points, as a fraction of white. */
    private fun darkest(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Double {
        val scale = bitmap.width / 402f
        var darkest = 1.0
        for (y in (top * scale).toInt() until (bottom * scale).toInt()) {
            for (x in (left * scale).toInt() until (right * scale).toInt()) {
                if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) continue
                val pixel = bitmap.getPixel(x, y)
                val luma = (0.3 * android.graphics.Color.red(pixel) + 0.6 * android.graphics.Color.green(pixel) + 0.1 * android.graphics.Color.blue(pixel)) / 255
                darkest = minOf(darkest, luma)
            }
        }
        return darkest
    }
}
