package com.junbingao.remotecontrol.android.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * `ios/VerificationUI/UnseenChecks.swift` through the Android model (`docs/DESIGN.md` § "A red dot
 * for a session that stopped and waits for you"): the conversation on screen with the app in front
 * is seen, one opened while the app is away or behind its lock is seen when it comes forward, a
 * session on screen when its turn ends keeps no dot, and the launcher badge follows the dots while
 * Notify me is on and goes with the account.
 */
@RunWith(AndroidJUnit4::class)
class UnseenModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val harness by lazy { AppModelHarness(context, folder.root) }

    @Test
    fun theConversationInFrontIsSeenAndTheBadgeFollowsTheDots() = runTest {
        val model = harness.model(this)
        val badges = harness.badges
        model.perform { enterDemo() }
        settle { model.connection.hasSnapshot }
        fun session(id: String): Session? = model.connection.sessions.firstOrNull { it.sessionID == id }
        suspend fun TestScope.turn(text: String) {
            model.connection.channel?.request(GatewayRequest.send(sessionID = DemoFixtures.piSessionID, text = text))
            settle { session(DemoFixtures.piSessionID)?.state == SessionState.running }
            settle { session(DemoFixtures.piSessionID)?.state == SessionState.idle }
        }
        val vite = checkNotNull(session(DemoFixtures.approvalSessionID)) { "the demo carries the marked approval session" }
        val pi = checkNotNull(session(DemoFixtures.piSessionID)) { "and the pi session" }

        // The badge belongs to Notify me: with the switch off nothing could keep a number true once
        // the app closed, so the app leaves none behind.
        assertEquals("the badge starts at nothing while Notify me is off", listOf(0), badges.counts)
        model.settings.notificationsEnabled = true
        settle { badges.counts.last() == 1 }
        assertEquals("Notify me on, the badge is the demo's one red dot", 1, badges.counts.last())

        // Opened while the app is away: not in front of anyone yet.
        model.open(vite)
        drain()
        assertTrue("a conversation opened behind the app's back is not seen", session(vite.sessionID)?.unseen == true)
        model.setSceneActive(true)
        settle { session(vite.sessionID)?.unseen == false }
        assertFalse("the app coming forward with it open sends session.seen", session(vite.sessionID)?.unseen ?: true)
        settle { badges.counts.last() == 0 }
        assertEquals("and the badge drops with the dot", 0, badges.counts.last())

        // Nothing open: a turn that ends is one to come back to.
        model.closeChat()
        turn("go")
        settle { session(pi.sessionID)?.unseen == true }
        assertTrue("a turn that ends with nothing open marks its session", session(pi.sessionID)?.unseen == true)
        settle { badges.counts.last() == 1 }
        assertEquals("and the badge counts it", 1, badges.counts.last())
        model.open(pi)
        settle { session(pi.sessionID)?.unseen == false }
        assertFalse("opening it in front of the person clears it", session(pi.sessionID)?.unseen ?: true)

        // On screen when the turn ends: the mark goes as soon as it comes.
        turn("again")
        settle { session(pi.sessionID)?.unseen == false }
        assertFalse("a session on screen when its turn ends keeps no dot", session(pi.sessionID)?.unseen ?: true)

        // Behind the lock the conversation is not in front of anyone.
        model.isLocked = true
        turn("locked")
        settle { session(pi.sessionID)?.unseen == true }
        drain()
        assertTrue("the app lock keeps the dot until it is lifted", session(pi.sessionID)?.unseen == true)
        model.isLocked = false
        settle { session(pi.sessionID)?.unseen == false }
        assertFalse("and unlocking onto the conversation clears it", session(pi.sessionID)?.unseen ?: true)

        // Away from the app, then back.
        model.setSceneActive(false)
        turn("away")
        settle { session(pi.sessionID)?.unseen == true }
        assertTrue("a turn that ends while the app is away marks it", session(pi.sessionID)?.unseen == true)
        settle { badges.counts.last() == 1 }
        model.settings.notificationsEnabled = false
        settle { badges.counts.last() == 0 }
        assertEquals("Notify me off takes the number off the icon", 0, badges.counts.last())
        model.settings.notificationsEnabled = true
        settle { badges.counts.last() == 1 }
        assertEquals("and on again puts it back", 1, badges.counts.last())
        model.setSceneActive(true)
        settle { session(pi.sessionID)?.unseen == false }
        assertFalse("coming back with the conversation open clears it", session(pi.sessionID)?.unseen ?: true)

        // With a dot still on the account, the number goes with the account.
        model.closeChat()
        turn("elsewhere")
        settle { session(pi.sessionID)?.unseen == true }
        settle { badges.counts.last() == 1 }
        model.signOut()
        assertEquals("signing out clears the badge", 0, badges.counts.last())
    }

    /** The badge's notification opens Sessions, whatever conversation was open, and settles the landing rule as a link does. */
    @Test
    fun theBadgesNotificationOpensSessions() = runTest {
        val model = harness.model(this, listOf("--demo"))
        settle { model.connection.hasSnapshot }
        model.tab = AppModel.Tab.settings
        model.open(checkNotNull(model.connection.sessions.firstOrNull { it.sessionID == DemoFixtures.approvalSessionID }))
        settle { model.chat != null }
        model.showSessions()
        assertEquals(AppModel.Tab.sessions, model.tab)
        assertTrue("on the list, not in the conversation", model.path.isEmpty())
        model.decideLandingTab()
        assertEquals("the landing rule does not move it afterwards", AppModel.Tab.sessions, model.tab)
    }
}
