package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.swipeOpen
import kotlin.math.abs
import kotlin.math.sign

/**
 * What the lists' ported UI tests ask of the screen beyond [DemoApp]'s own: elements actually on
 * screen (a row's swipe buttons are laid out off its edge until it is swiped), what a row reads
 * aloud, and the iPhone tests' own measured drags, which bring a row clear of the bars.
 */
class ListsDriver(private val compose: ComposeTestRule, val app: DemoApp) {
    /** The nodes carrying [tag] that are inside the screen and not clipped away. */
    fun shown(tag: String): List<SemanticsNode> =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }

    fun isShown(tag: String): Boolean = shown(tag).isNotEmpty()

    /** Whether a control carrying [tag] is on screen and would act on a tap: `isEnabled`. */
    fun isEnabled(tag: String): Boolean = shown(tag).any { !it.config.contains(SemanticsProperties.Disabled) }

    fun waitShown(tag: String, timeoutMillis: Long = 20_000) = app.await("“$tag” shown", timeoutMillis) { isShown(tag) }

    /** Tap the one of [tag]'s nodes that is on screen, as a finger would. */
    fun tapShown(tag: String) {
        waitShown(tag)
        val node = shown(tag).first()
        node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: error("“$tag” cannot be tapped")
        compose.waitForIdle()
    }

    /** Where a shown node's leading edge is, to read an order off the screen. */
    fun left(tag: String): Float = shown(tag).first().boundsInRoot.left

    fun bounds(tag: String) = app.node(tag).fetchSemanticsNode().boundsInRoot

    /** Everything an element reads aloud: its own label and the words merged into it. */
    fun label(tag: String): String = words(compose.onAllNodesWithTag(tag).onFirst().fetchSemanticsNode()).joinToString(", ")

    /** The words of an element and of everything under it, merged or not. */
    fun allText(tag: String): String = collect(app.node(tag).fetchSemanticsNode()).joinToString(" ")

    /** What a field holds: the iPhone test's `textField.value`. */
    fun editable(tag: String): String? = app.node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    /** What a control says it is set to: the iPhone's `accessibilityValue`. */
    fun state(tag: String): String? = compose.onAllNodesWithTag(tag).onFirst().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    fun text(tag: String): String = app.node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    /** Whether any element on screen holds these words, asked once. */
    fun onScreen(words: String): Boolean = collect(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()).any { it.contains(words) }

    fun waitText(words: String, timeoutMillis: Long = 20_000) = app.await("“$words” on screen", timeoutMillis) { onScreen(words) }

    fun hasText(words: String): Boolean = compose.onAllNodesWithText(words, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Swipe a row to uncover its trailing actions, as XCUITest's `swipeLeft()` does ([swipeOpen]). */
    fun swipeLeft(tag: String) {
        app.node(tag).performTouchInput { swipeOpen() }
        compose.waitForIdle()
    }

    /**
     * Uncover a row's trailing actions without crossing the full-swipe threshold, which would run
     * the first action instead of offering it: a third of the row's width is enough to open them.
     */
    fun revealRowActions(tag: String) {
        app.node(tag).performTouchInput { swipeLeft(startX = width * 0.92f, endX = width * 0.58f, durationMillis = 400) }
        compose.waitForIdle()
    }

    fun swipeUp(tag: String) {
        app.node(tag).performTouchInput { swipeUp() }
        compose.waitForIdle()
    }

    /**
     * The iPhone tests' `scrollDown(to:)`: until [tag] is on screen, drag the list on by 320 points;
     * once it is, nudge it clear of the bars that float over the list (`clearOfBars`). Answers
     * whether it ended up where a tap reaches it.
     */
    fun scrollDown(tag: String, swipes: Int = 8): Boolean {
        repeat(swipes + 1) {
            if (app.exists(tag)) return clearOfBars(tag)
            drag(320f)
        }
        return false
    }

    /**
     * `clearOfBars(_:in:)`: a row found under the bars at the top — within 160 points of the
     * screen's top — or under the bar at the foot — within 140 of its foot — is dragged clear.
     */
    private fun clearOfBars(tag: String): Boolean {
        val density = compose.density.density
        val screen = compose.onRoot().fetchSemanticsNode().size.height
        val top = 160f * density
        val bottom = screen - 140f * density
        val node = app.node(tag).fetchSemanticsNode()
        val minY = node.positionInRoot.y
        val maxY = minY + node.size.height
        if (minY < top) drag(-(top - minY) / density) else if (maxY > bottom) drag((maxY - bottom) / density)
        return isShown(tag)
    }

    /**
     * `drag(_:by:)`: a press 8 points in from the screen's edge, moved [points] up — down when
     * negative — at a walking pace and held before it lets go, so the list follows the finger and is
     * never flung. The move carries the touch slop on top, so the list travels the whole distance.
     */
    fun drag(points: Float) {
        if (abs(points) < 1f) return
        compose.onRoot().performTouchInput {
            val start = Offset(8.dp.toPx(), height * if (points > 0f) 0.6f else 0.4f)
            val distance = points.dp.toPx() + sign(points) * viewConfiguration.touchSlop
            down(start)
            advanceEventTime(200)
            for (step in 1..DRAG_STEPS) {
                advanceEventTime(20)
                moveTo(start - Offset(0f, distance * step / DRAG_STEPS))
            }
            advanceEventTime(400)
            up()
        }
        compose.waitForIdle()
    }

    /** Let [millis] of real time pass with the app running, for what the demo scripts on a timer. */
    fun pause(millis: Long) {
        val until = System.currentTimeMillis() + millis
        app.await("$millis ms to pass", millis + 5_000) { System.currentTimeMillis() >= until }
    }

    /** Scroll a list back to its top. */
    fun scrollToTop(list: String) {
        app.node(list).performScrollToIndex(0)
        compose.waitForIdle()
    }

    /** The tags under [tag] in the order the screen lays them out, for an order read off the tree. */
    fun tagsInOrder(tag: String): List<String> = tags(app.node(tag).fetchSemanticsNode())

    private fun tags(node: SemanticsNode): List<String> =
        listOfNotNull(node.config.getOrNull(SemanticsProperties.TestTag)) + node.children.flatMap(::tags)

    fun swipeDown(tag: String) {
        app.node(tag).performTouchInput { swipeDown() }
        compose.waitForIdle()
    }

    fun tap(node: SemanticsNodeInteraction) {
        node.performClick()
        compose.waitForIdle()
    }

    private fun words(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)

    private fun collect(node: SemanticsNode): List<String> = words(node) + node.children.flatMap(::collect)
}

/** The moves a measured drag is made of. */
private const val DRAG_STEPS = 20
