package com.junbingao.remotecontrol.android.screens.shell

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.RealTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The helpers of `RemoteControlUITests.swift`, for the tests ported from it: the same scrolling,
 * the same way of flipping a switch, the same sign-in, so a step here does what the iPhone's step
 * does and a picture is taken where the iPhone's was.
 *
 * An element is there when it is laid out, as an iPhone row is there when its cell is loaded: a
 * lazy list composes the item after the last one on screen ahead of time without placing it, and
 * that one is not there yet.
 */
class Driving(private val compose: ComposeTestRule, val app: DemoApp) {
    private val density get() = compose.density

    /** Every laid-out node [matcher] finds, each element on its own: `descendants(matching: .any)`. */
    fun all(matcher: SemanticsMatcher): List<SemanticsNode> =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }

    fun exists(matcher: SemanticsMatcher): Boolean = all(matcher).isNotEmpty()

    fun exists(tag: String): Boolean = exists(hasTestTag(tag))

    /** `app.staticTexts[text].exists`: something laid out reads exactly this. */
    fun reads(text: String): Boolean = exists(hasText(text))

    /** `waitForExistence(timeout:)`, answering rather than failing. */
    fun waitFor(matcher: SemanticsMatcher, timeoutMillis: Long): Boolean = within(timeoutMillis) { exists(matcher) }

    /** `waitForNonExistence(timeout:)`, answering rather than failing. */
    fun waitForAbsence(matcher: SemanticsMatcher, timeoutMillis: Long): Boolean = within(timeoutMillis) { !exists(matcher) }

    /**
     * Whether [condition] comes to hold within [timeoutMillis] of real time, stretched for a loaded
     * machine ([RealTime]). The screens' own clock is moved on with it, so a pause a screen takes —
     * the sign-in form waiting for the typing to settle before it asks the gateway — passes as it
     * would on a phone.
     */
    private fun within(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + RealTime.bound(timeoutMillis)
        while (true) {
            compose.waitForIdle()
            if (condition()) return true
            if (System.currentTimeMillis() >= end) return false
            Thread.sleep(STEP_MILLIS)
            compose.mainClock.advanceTimeBy(STEP_MILLIS)
        }
    }

    /**
     * What an element says to a reader, as the iPhone's `label` does: the name it was given, or
     * else every word it holds, joined.
     */
    fun label(tag: String): String {
        val node = merged(tag)
        val described = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        if (described.isNotEmpty()) return described.joinToString(", ")
        return node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(", ") { it.text }
    }

    /** The iPhone's `value`: a switch's "1" or "0", any other control's state in words. */
    fun value(tag: String): String {
        val config = merged(tag).config
        return when (config.getOrNull(SemanticsProperties.ToggleableState)) {
            ToggleableState.On -> "1"
            ToggleableState.Off -> "0"
            else -> config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
        }
    }

    fun isEnabled(tag: String): Boolean = !merged(tag).config.contains(SemanticsProperties.Disabled)

    /**
     * The element as a reader meets it: merged with what it holds, or, where a control around it
     * took its words into its own, the element by itself.
     */
    private fun merged(tag: String): SemanticsNode =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = false).fetchSemanticsNodes().firstOrNull { it.layoutInfo.isPlaced }
            ?: all(hasTestTag(tag)).first()

    /** Where an element is, in points from the top left of the screen, whether or not all of it is on screen. */
    fun frame(matcher: SemanticsMatcher): Rect = all(matcher).first().frame()

    fun frame(tag: String): Rect = frame(hasTestTag(tag))

    private fun SemanticsNode.frame(): Rect = with(density) {
        Rect(positionInRoot.x.toDp().value, positionInRoot.y.toDp().value,
             (positionInRoot.x + size.width).toDp().value, (positionInRoot.y + size.height).toDp().value)
    }

    /**
     * Elements carrying [tag] that a person can see: a swipe's buttons are laid out under the row
     * until it slides, where the iPhone has not made them yet.
     */
    fun shown(tag: String): List<SemanticsNode> = all(hasTestTag(tag)).filter { it.boundsInRoot.width > 1f && it.boundsInRoot.height > 1f }

    /** Tap the one of [tag]'s elements a person can see. */
    fun tapShown(tag: String) {
        val node = shown(tag).first()
        compose.onNode(SemanticsMatcher("node ${node.id}") { it.id == node.id }, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    /**
     * `scrollDown(to:)`: measured drags of 320 points, never flings, until the element is laid
     * out, then a nudge that brings it clear of the bar at the top and the bars at the foot, as the
     * iPhone's does (round 46).
     */
    fun scrollDown(to: SemanticsMatcher, swipes: Int = 8): Boolean {
        repeat(swipes + 1) {
            if (waitFor(to, 1_000)) return clearOfBars(to)
            drag(320.dp)
        }
        return false
    }

    fun scrollDown(toTag: String, swipes: Int = 8): Boolean = scrollDown(hasTestTag(toTag), swipes)

    fun scrollDown(toText: String): Boolean = scrollDown(hasText(toText))

    private fun clearOfBars(element: SemanticsMatcher): Boolean {
        val list = list().frame()
        val top = list.top + 160f
        val bottom = list.bottom - 140f
        val frame = frame(element)
        if (frame.top < top) {
            drag((-(top - frame.top)).dp)
        } else if (frame.bottom > bottom) {
            drag((frame.bottom - bottom).dp)
        }
        return exists(element)
    }

    /** The list on screen: the largest thing that scrolls. */
    private fun list(): SemanticsNode = all(hasScrollAction()).maxBy { it.size.height }

    /** Moves the list's content up by [distance], as a finger dragging it that far does. */
    fun drag(distance: Dp) {
        if (kotlin.math.abs(distance.value) < 1f) return
        val node = list()
        val pixels = with(density) { distance.toPx() }
        compose.onNode(SemanticsMatcher("list ${node.id}") { it.id == node.id }, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
        compose.waitForIdle()
    }

    /** `turnOn(_:)`: a tap on the switch, and the value it then reads. */
    fun turnOn(tag: String) {
        app.tap(tag)
        app.await("“$tag” on") { value(tag) == "1" }
    }

    fun turnOff(tag: String) {
        app.tap(tag)
        app.await("“$tag” off") { value(tag) == "0" }
    }

    // Accounts

    /** `typeGateway()`: the address the offline account tests sign in to, typed into an emptied field. */
    fun typeGateway() {
        app.waitFor("login.gateway")
        val field = app.node("login.gateway")
        field.performTextClearance()
        field.performTextInput(TEST_GATEWAY)
        compose.waitForIdle()
        assertEquals("the address field holds exactly what was typed", TEST_GATEWAY, text("login.gateway"))
    }

    /** `signIn(username:password:)`: a form that remembers the gateway and the last username has both cleared first. */
    fun signIn(username: String, password: String) {
        typeGateway()
        val name = app.node("login.username")
        name.performTextClearance()
        name.performTextInput(username)
        app.node("login.password").performTextInput(password)
        compose.waitForIdle()
        app.tap("login.connect")
    }

    /** What a field holds. */
    fun text(tag: String): String =
        app.node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /**
     * `openSettingsTab()`. The app is up once its first device list is in, which is also when the
     * landing rule picks a tab once and for all; a tap before that would be undone by it.
     */
    fun openSettingsTab() {
        assertTrue("the app is up", waitFor(hasTestTag("tab.settings"), 25_000))
        app.await("the first device list", 25_000) { app.model.connection.hasSnapshot }
        app.tap("tab.settings")
    }

    companion object {
        /**
         * The gateway the offline account tests sign in to. It is deliberately not the field's own
         * placeholder, so an empty field can be told from a filled one.
         */
        const val TEST_GATEWAY = "https://rc.test.example"

        private const val STEP_MILLIS = 50L
    }
}
