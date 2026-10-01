package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.RealTime
import com.junbingao.remotecontrol.core.state.TimelineDetail
import java.util.concurrent.TimeUnit
import org.robolectric.shadows.ShadowLooper

/**
 * The steps the iPhone's UI tests take, over [DemoApp]: finding an element by the iPhone's
 * accessibility identifier, reading its label and value as XCUITest does, tapping, typing, and
 * waiting for what a step looks at.
 *
 * Waiting runs everything on real time — the demo's scripts on their own threads already do, and
 * this moves the main looper's clock and the composition's with them — so a delay the app schedules,
 * a polish answer or a partial of a dictation, arrives as it would on a phone.
 */
class ChatDriver(private val compose: ComposeTestRule, val app: DemoApp) {
    private var clock = System.currentTimeMillis()

    /**
     * Wait until [condition] holds, failing with [what] after [timeoutMillis], stretched for a
     * loaded machine ([RealTime]) — `waitForExistence` and its friends.
     */
    fun await(what: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val bound = RealTime.bound(timeoutMillis)
        val end = System.currentTimeMillis() + bound
        while (true) {
            tick()
            if (condition()) return
            check(System.currentTimeMillis() < end) { "still waiting after $bound ms for $what" }
            Thread.sleep(40)
        }
    }

    /** Whether [condition] comes to hold within [timeoutMillis], stretched as [await]'s; false rather than a failure — `waitFor` in the iPhone's tests. */
    fun within(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + RealTime.bound(timeoutMillis)
        while (System.currentTimeMillis() < end) {
            tick()
            if (condition()) return true
            Thread.sleep(40)
        }
        tick()
        return condition()
    }

    /** Let real time pass for [millis], with everything that runs on it. */
    fun pause(millis: Long) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            tick()
            Thread.sleep(40)
        }
        tick()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val elapsed = (now - clock).coerceIn(0, 1_000)
        clock = now
        if (elapsed > 0) {
            ShadowLooper.idleMainLooper(elapsed, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(elapsed)
        }
        compose.waitForIdle()
    }

    // Elements

    fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

    fun exists(tag: String): Boolean {
        compose.waitForIdle()
        return nodes(tag).isNotEmpty()
    }

    fun waitFor(tag: String, timeoutMillis: Long = 20_000) = await("“$tag” on screen", timeoutMillis) { nodes(tag).isNotEmpty() }

    fun waitForAbsence(tag: String, timeoutMillis: Long = 20_000) = await("“$tag” gone", timeoutMillis) { nodes(tag).isEmpty() }

    fun node(tag: String): SemanticsNodeInteraction = compose.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst()

    fun nodeAt(tag: String, index: Int): SemanticsNodeInteraction = compose.onAllNodesWithTag(tag, useUnmergedTree = true)[index]

    fun tap(tag: String) {
        waitFor(tag)
        node(tag).performClick()
        tick()
    }

    /** A text anywhere on screen that contains [fragment] — `staticTexts.containing(label CONTAINS …)`. */
    fun texts(fragment: String, ignoreCase: Boolean = false): List<SemanticsNode> =
        compose.onAllNodes(hasText(fragment, substring = true, ignoreCase = ignoreCase), useUnmergedTree = true).fetchSemanticsNodes()

    fun hasText(fragment: String, ignoreCase: Boolean = false): Boolean {
        compose.waitForIdle()
        return texts(fragment, ignoreCase).isNotEmpty()
    }

    /** A text or a control whose words are exactly [words], as `app.buttons["…"]` finds one by its label. */
    fun labelled(words: String): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher("labelled “$words”") { label(it) == words }, useUnmergedTree = true).fetchSemanticsNodes()

    /**
     * Taps the first control with [words] that is on screen. XCUITest has no element for a button a
     * swipe has not uncovered yet; Compose lays such a button out at no size, and it is passed over.
     */
    fun tapLabelled(words: String) {
        val matcher = SemanticsMatcher("labelled “$words”") { label(it) == words && !it.boundsInRoot.isEmpty }
        await("“$words” on screen") { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(matcher, useUnmergedTree = true).onFirst().performClick()
        tick()
    }

    /** XCUITest's `label`: what a screen reader names the element. */
    fun label(tag: String): String = label(nodes(tag).first())

    /** XCUITest's `value`: a control's value, or what a field holds. */
    fun value(tag: String): String {
        val node = nodes(tag).first()
        node.config.getOrNull(SemanticsProperties.EditableText)?.let { return it.text }
        return node.config.getOrNull(SemanticsProperties.StateDescription) ?: ""
    }

    fun isEnabled(tag: String): Boolean = isEnabled(nodes(tag).first())

    fun isFocused(tag: String): Boolean = nodes(tag).first().config.getOrNull(SemanticsProperties.Focused) == true

    /** Where the element is on screen, in points. */
    fun frame(tag: String): Rect = frame(nodes(tag).first())

    fun frame(node: SemanticsNode): Rect {
        val density = node.layoutInfo.density.density
        val bounds = node.boundsInRoot
        return Rect(bounds.left / density, bounds.top / density, bounds.right / density, bounds.bottom / density)
    }

    // Steps

    fun type(tag: String, text: String) {
        waitFor(tag)
        node(tag).performTextInput(text)
        tick()
    }

    /** Opens a session from the list, scrolling to its row first when it is below the fold. */
    fun openSession(sessionID: String) {
        val row = "session.$sessionID"
        await("the sessions list") { nodes("sessions.new").isNotEmpty() || nodes(row).isNotEmpty() || scrollables().isNotEmpty() }
        if (nodes(row).isEmpty()) {
            await("“$row” reached") {
                runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag(row)) }
                nodes(row).isNotEmpty()
            }
        }
        bringClearOfTheTabBar(row)
        tap(row)
        waitFor("composer.prompt", 20_000)
    }

    private fun scrollables(): List<SemanticsNode> = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()

    /**
     * `scrollDown(to:)` scrolls until the row is hittable. A tap is a touch at the row's centre, and
     * the tab bar and the New session bar above it float over the bottom of the list, so the row is
     * moved up until it stands clear of the higher of the two.
     */
    private fun bringClearOfTheTabBar(tag: String) {
        val list = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
        await("“$tag” clear of the tab bar") {
            val bounds = nodes(tag).firstOrNull()?.boundsInRoot ?: return@await false
            val bars = listOf("tab.sessions", "sessions.new").mapNotNull { nodes(it).firstOrNull()?.boundsInRoot?.top }
            val floor = bars.minOrNull() ?: list.fetchSemanticsNode().boundsInRoot.bottom
            if (bounds.bottom <= floor) return@await true
            list.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, bounds.bottom - floor) }
            false
        }
    }

    /**
     * Thinking, tool calls and the task list are drawn at Detailed and nowhere else, and Simple is
     * what a fresh install reads at. The iPhone's test turns the preference over in Settings; the
     * Settings screen is another feature's, so this sets the same preference the control writes.
     */
    fun chooseDetailedTranscript() {
        app.model.settings.timelineDetail = TimelineDetail.detailed
        tick()
    }

    /** The demo plays one scripted turn on opening the live session. Waiting for its last words keeps a scroll assertion off a moving transcript. */
    fun waitForLiveTurn(timeoutMillis: Long = 30_000) = await("the scripted turn's last words", timeoutMillis) { hasText("source of the flake") }

    /**
     * The same turn, over. XCUITest's steps are slow enough that it is by the time the iPhone's
     * tests reach the composer — their pictures show the session done — so a port that runs faster
     * waits for it rather than typing into a running turn.
     */
    fun waitForLiveTurnToEnd(timeoutMillis: Long = 30_000) {
        await("the scripted turn over", timeoutMillis) { hasText("source of the flake") && app.model.chat?.isRunning == false }
        // The time under the title is read once a second, as the iPhone's timer reads it, so a
        // turn that just ended is off the subtitle at the next tick.
        pause(1_100)
    }

    /** The attached session asks a question as it opens, and the composer answers it before anything else is typed. */
    fun answerTheSharedQuestion(text: String) {
        await("the attached CLI's question", 20_000) { nodes("composer.send").isNotEmpty() && label("composer.send") == "Answer" }
        type("composer.prompt", text)
        tap("composer.send")
        await("the composer given back", 15_000) { label("composer.send") == "Send" }
    }

    /**
     * The iPhone test's `attach(name:)`. Every XCUITest query before it waits for the app to go
     * idle, so the iPhone's pictures are taken with a scroll the transcript started already over;
     * half a second of real time stands for that wait.
     */
    fun attach(name: String) {
        pause(500)
        app.attach(name)
    }

    /** Scrolls the list tagged [tag] by [points], down its content for a positive amount, as a finger would. */
    fun scrollBy(tag: String, points: Float) {
        val pixels = points * nodes(tag).first().layoutInfo.density.density
        node(tag).performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, pixels) }
        tick()
    }

    /** Taps a menu's item, a swipe's button or a picker's row by its words. */
    fun tapButton(words: String) = tapLabelled(words)

    companion object {
        fun label(node: SemanticsNode): String {
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { return it.joinToString(" ") }
            node.config.getOrNull(SemanticsProperties.Text)?.let { return it.joinToString(" ") { text: AnnotatedString -> text.text } }
            return ""
        }

        fun hasClick(node: SemanticsNode): Boolean = node.config.contains(SemanticsActions.OnClick)

        /** Whether an element, or the control it is the words of, takes a tap. */
        fun isEnabled(node: SemanticsNode): Boolean {
            var current: SemanticsNode? = node
            while (current != null) {
                if (current.config.contains(SemanticsProperties.Disabled)) return false
                if (hasClick(current)) return true
                current = current.parent
            }
            return true
        }

        /** Every word an element and what is inside it say: what a merged element reads out. */
        fun words(node: SemanticsNode): String {
            val words = mutableListOf<String>()
            fun walk(current: SemanticsNode) {
                current.config.getOrNull(SemanticsProperties.ContentDescription)?.let(words::addAll)
                current.config.getOrNull(SemanticsProperties.Text)?.forEach { words.add(it.text) }
                current.children.forEach(::walk)
            }
            walk(node)
            return words.joinToString(" ")
        }
    }
}
