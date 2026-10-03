package com.junbingao.remotecontrol.win.unseen

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.app.FrontmostWindow
import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.ModelHarness
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.StringTable
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Mac's `UnseenTests`, case for case (A47, `docs/DESIGN.md` § "A red dot for a session that
 * stopped and waits for you"): the conversation open in the window that has focus is seen — when it
 * opens there, when the window comes forward, and when a mark arrives while it is there — the
 * taskbar badge is the number of dots and nothing at zero, and signing out takes it away.
 */
class UnseenTests {
    private val mac = DemoFixtures.macDeviceID

    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private fun signedIn(): ModelHarness = ModelHarness(LaunchOptions(demo = true, ephemeral = true))

    private fun WinAppModel.session(id: String): Session? = connection.sessions.firstOrNull { it.sessionID == id }

    private fun WinAppModel.taskbar(): InertTaskbarBadge = UnseenFeature.state(of = this).badge.platform as InertTaskbarBadge

    /** Send to a session the demo device drives and wait for its turn to end. */
    private suspend fun WinAppModel.turn(harness: ModelHarness, id: String) {
        connection.channel?.request(GatewayRequest.send(sessionID = id, text = "go"))
        harness.waitFor { session(id)?.state == SessionState.running }
        harness.waitFor { session(id)?.state == SessionState.idle }
    }

    @Test
    fun theBadgeIsTheNumberOfDotsAndNothingAtZero() {
        assertNull(TaskbarBadgeKeeper.label(0))
        assertEquals("1", TaskbarBadgeKeeper.label(1))
        assertEquals("12", TaskbarBadgeKeeper.label(12))
    }

    @Test
    fun theRowsSayItInBothLanguages() {
        assertEquals("not yet opened", StringTable.en.sessions.unseen)
        assertEquals("未查看", StringTable.zhHans.sessions.unseen)
    }

    @Test
    fun theConversationInFrontIsTheRoutesWhileItsWindowHasFocus() = signedIn().use { harness ->
        harness.run {
            restoreOrPrompt()
            harness.waitFor { connection.hasSnapshot }
            router.go(Route.Chat(deviceId = mac, sessionId = "s1"))
            isWindowActive = false
            assertNull(conversationInFront, "a window behind another is in front of nobody")
            isWindowActive = true
            assertEquals("$mac/s1", conversationInFront)
            router.go(Route.Sessions)
            assertNull(conversationInFront, "and the list is not a conversation")
            signOut()
        }
    }

    @Test
    fun theRowOfTheConversationInFrontDrawsNoDot() = signedIn().use { harness ->
        harness.run {
            restoreOrPrompt()
            harness.waitFor { connection.hasSnapshot }
            val open = Session(sessionID = "s1", deviceID = mac, agent = "claude", title = "t", cwd = "/", unseen = true)
            val other = Session(sessionID = "s2", deviceID = mac, agent = "claude", title = "t", cwd = "/", unseen = true)
            val quiet = Session(sessionID = "s3", deviceID = mac, agent = "claude", title = "t", cwd = "/")
            router.go(Route.Chat(deviceId = mac, sessionId = "s1"))
            isWindowActive = true
            assertFalse(showsUnseenDot(open), "it is being looked at while its session.seen is on the way")
            assertTrue(showsUnseenDot(other), "while every other marked row keeps its dot")
            assertFalse(showsUnseenDot(quiet))
            isWindowActive = false
            assertTrue(showsUnseenDot(open), "and behind another window nobody is looking at it")
            signOut()
        }
    }

    @Test
    fun aConversationOpenBehindAnotherWindowIsSeenWhenItComesForward() = signedIn().use { harness ->
        harness.run {
            restoreOrPrompt()
            harness.waitFor { connection.hasSnapshot }
            val taskbar = taskbar()
            harness.waitFor { taskbar.labels.lastOrNull() == "1" }
            assertEquals("1", taskbar.labels.last(), "the demo opens with one dot, and the taskbar says so")

            isWindowActive = false
            router.go(Route.Chat(deviceId = mac, sessionId = DemoFixtures.approvalSessionID))
            delay(200)
            assertTrue(session(DemoFixtures.approvalSessionID)?.unseen == true, "open in a window without focus, it has not been looked at")

            isWindowActive = true
            harness.waitFor { session(DemoFixtures.approvalSessionID)?.unseen == false }
            assertFalse(session(DemoFixtures.approvalSessionID)?.unseen ?: true, "the window coming forward sends session.seen")
            harness.waitFor { taskbar.labels.isNotEmpty() && taskbar.labels.last() == null }
            assertNull(taskbar.labels.last(), "and with no dot left the icon carries no badge")
            signOut()
        }
    }

    @Test
    fun aTurnThatEndsUnwatchedIsCountedAndOneOnScreenIsNot() = signedIn().use { harness ->
        harness.run {
            restoreOrPrompt()
            harness.waitFor { connection.hasSnapshot }
            val taskbar = taskbar()
            isWindowActive = true
            router.go(Route.Sessions)

            turn(harness, DemoFixtures.piSessionID)
            harness.waitFor { session(DemoFixtures.piSessionID)?.unseen == true }
            assertTrue(session(DemoFixtures.piSessionID)?.unseen == true, "nothing had it open")
            harness.waitFor { taskbar.labels.lastOrNull() == "2" }
            assertEquals("2", taskbar.labels.last(), "so it joins the count")

            router.go(Route.Chat(deviceId = mac, sessionId = DemoFixtures.piSessionID))
            harness.waitFor { session(DemoFixtures.piSessionID)?.unseen == false }
            assertFalse(session(DemoFixtures.piSessionID)?.unseen ?: true, "opening it clears it")

            turn(harness, DemoFixtures.piSessionID)
            harness.waitFor { session(DemoFixtures.piSessionID)?.unseen == false }
            assertFalse(session(DemoFixtures.piSessionID)?.unseen ?: true,
                        "a mark arriving for the conversation on screen is cleared at once")
            harness.waitFor { taskbar.labels.lastOrNull() == "1" }
            assertEquals("1", taskbar.labels.last(), "and only the session nobody opened is counted")
            signOut()
        }
    }

    @Test
    fun signingOutTakesTheBadgeOff() = signedIn().use { harness ->
        harness.run {
            restoreOrPrompt()
            harness.waitFor { connection.hasSnapshot }
            val taskbar = taskbar()
            harness.waitFor { taskbar.labels.lastOrNull() == "1" }
            signOut()
            harness.waitFor { taskbar.labels.last() == null }
            assertNull(taskbar.labels.last())
            assertEquals(0, UnseenFeature.state(of = this).badge.shown)
        }
    }

    @Test
    fun theRenderersWindowStandsForTheWindowInFront() = signedIn().use { harness ->
        val model = harness.model
        val scene = ImageComposeScene(300, 200, Density(1f)) { FrontmostWindow(model) }
        scene.render(0)
        assertTrue(harness.run { isWindowActive }, "a render is a picture of the window the person is looking at")
        scene.close()
        assertFalse(harness.run { isWindowActive }, "and when it is gone, nothing is in front")
    }
}
