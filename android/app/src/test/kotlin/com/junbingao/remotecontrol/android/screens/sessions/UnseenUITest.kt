package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.screens.chat.ChatDriver
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The iPhone's UI test of amendment A47 (`ios/UITests/RemoteControlUITests.swift`), on the demo
 * through the same steps: a turn that ends while nobody has its conversation open leaves a red dot
 * in the row's leading gutter, opening the conversation takes it off, and a conversation on screen
 * when its turn ends never keeps one. The row says it in words for a screen reader, which is what is
 * read here; the gutter's pixels say the dot is drawn where the ruling puts it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class UnseenUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val unseen = "not yet opened"

    @Test
    fun aRedDotAppearsWhenATurnEndsUnwatchedAndGoesWhenTheConversationOpens() =
        DemoApp(compose, "testARedDotAppearsWhenATurnEndsUnwatchedAndGoesWhenTheConversationOpens").use { app ->
            val chat = ChatDriver(compose, app)
            val lists = ListsDriver(compose, app)
            app.waitFor("sessions.new")

            val waiting = "session.${DemoFixtures.approvalSessionID}"
            app.waitFor(waiting, 10_000)
            assertTrue("the demo opens with its dot, nobody having looked — ${lists.label(waiting)}", lists.label(waiting).contains(unseen))

            val row = "session.${DemoFixtures.piSessionID}"
            assertTrue("the pi session is listed", lists.scrollDown(row))
            assertFalse("a quiet session carries no dot — ${lists.label(row)}", lists.label(row).contains(unseen))
            assertTrue("and nothing red stands in its gutter", redness(beside = row, lists) < 0.2)

            // A turn starts, and the list is back on screen before it ends: the scripted device
            // takes three seconds over its echo under --ui-testing.
            chat.openSession(DemoFixtures.piSessionID)
            send(chat, "check the parser once more")
            app.back()
            app.waitFor("sessions.new", 15_000)
            assertTrue("with the pi session on it", lists.scrollDown(row))
            assertTrue("the turn ended with nobody looking, so the row says so — ${lists.label(row)}",
                       chat.within(20_000) { lists.label(row).contains(unseen) })
            assertTrue("and the red dot stands in the leading gutter, beside the title",
                       chat.within(5_000) { redness(beside = row, lists) > 0.4 })
            app.attach("47-red-dot")

            chat.openSession(DemoFixtures.piSessionID)
            app.back()
            app.waitFor("sessions.new", 15_000)
            assertTrue("the list is back", lists.scrollDown(row))
            assertTrue("opening the conversation took the dot off — ${lists.label(row)}",
                       chat.within(10_000) { !lists.label(row).contains(unseen) })
            assertTrue("from the gutter too", redness(beside = row, lists) < 0.2)
            app.attach("47-red-dot-gone")

            // On screen when the turn ends: the dot never stays.
            chat.openSession(DemoFixtures.piSessionID)
            send(chat, "and once more")
            chat.waitFor("chat.stop", 10_000)
            chat.waitForAbsence("chat.stop", 20_000)
            app.back()
            app.waitFor("sessions.new", 15_000)
            assertTrue("the list is back", lists.scrollDown(row))
            assertFalse("a conversation on screen when its turn ended keeps no dot — ${lists.label(row)}",
                        lists.label(row).contains(unseen))
        }

    /** Type a message into the open conversation and send it. */
    private fun send(chat: ChatDriver, message: String) {
        chat.type("composer.prompt", message)
        chat.await("the draft reached the composer") { chat.isEnabled("composer.send") }
        chat.tap("composer.send")
    }

    /**
     * How red the reddest pixel is in the leading gutter of a session row, on the title's line: 0
     * for ink, white and grey, about 0.6 for the Danger red. The band reaches from 16 points before
     * the row's frame to 16 into it, so it holds the gutter however the cell reports its frame.
     */
    private fun redness(beside: String, lists: ListsDriver): Float {
        val frame = lists.bounds(beside)
        val scale = compose.density.density
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val left = max(0, (frame.left - 16 * scale).roundToInt())
        val right = minOf(pixels.width, (frame.left + 16 * scale).roundToInt())
        val top = max(0, frame.top.roundToInt())
        val bottom = minOf(pixels.height, (frame.top + 36 * scale).roundToInt())
        var reddest = 0f
        for (x in left until right) {
            for (y in top until bottom) {
                val color = pixels[x, y]
                reddest = max(reddest, color.red - max(color.green, color.blue))
            }
        }
        return reddest
    }
}
