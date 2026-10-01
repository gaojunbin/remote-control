package com.junbingao.remotecontrol.android.screens.alerts

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.push.PushAuthorization
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.state.TurnAlerts
import com.junbingao.remotecontrol.core.transport.SessionLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

/**
 * `ios/VerificationUI`'s "The app's own banner when a turn ends" and the A35 banners, on a platform
 * that posts nothing: three gates, and a notification whose tap is the session's own link, which
 * is the path a link from anywhere else takes. Where the iPhone gates on the app being in front,
 * Android gates on the conversation being on screen (`docs/DESIGN.md` § "The Android app").
 */
@RunWith(AndroidJUnit4::class)
class TurnBannerTest {
    private val alerts = FakeTurnAlertPlatform()
    private val notifier = TurnNotifier(alerts)

    private fun demoSession(state: SessionState) = Session(
        sessionID = "s-1", deviceID = "d-1", agent = "claude", title = "Fix the flake", cwd = "/w", state = state,
        updatedAt = 1_700_000_000_000,
    )

    private val ran = demoSession(SessionState.running)
    private val quiet = demoSession(SessionState.idle)

    @Test
    fun eachGateHoldsTheNotificationBack() {
        assertNull("the conversation on screen raises nothing: the person is reading it",
                   notifier.announce(ran, quiet, "mac", onScreen = true, enabled = true, authorization = PushAuthorization.authorized))
        assertNull("the Notify me switch is the one switch, and off means off",
                   notifier.announce(ran, quiet, "mac", onScreen = false, enabled = false, authorization = PushAuthorization.authorized))
        assertNull("nothing is raised before the system has granted permission",
                   notifier.announce(ran, quiet, "mac", onScreen = false, enabled = true, authorization = PushAuthorization.notDetermined))
        assertNull("and nothing after it has been refused",
                   notifier.announce(ran, quiet, "mac", onScreen = false, enabled = true, authorization = PushAuthorization.denied))
        assertNull("a session that was already quiet is not a finished turn",
                   notifier.announce(quiet, quiet, "mac", onScreen = false, enabled = true, authorization = PushAuthorization.authorized))
        assertEquals("none of those reached the system", 0, alerts.posted.size)
    }

    @Test
    fun aFinishedTurnIsTheStatusWordUnderTheDevicesName() {
        val finished = notifier.announce(ran, quiet, "mac", onScreen = false, enabled = true, authorization = PushAuthorization.authorized)
        assertNotNull("an allowed app announces a finished turn", finished)
        finished!!
        assertEquals("one request, once", 1, alerts.posted.size)
        assertEquals("the notification is headed by the device's name", "mac", finished.title)
        assertEquals("and says the status word, and nothing about the turn", "Turn finished", finished.body)
        assertEquals("notifications group under the session they belong to", quiet.id, finished.threadIdentifier)
        assertEquals("the payload carries the push's own sentence", "mac: Turn finished", finished.route.title)
        assertEquals("the last one is kept for a check to read", finished, notifier.lastAlert)

        // The tap is the session's own link, which opens the session through the path every link takes.
        assertEquals("remotecontrol://session?device=d-1&id=s-1", finished.route.deepLink.toString())
        val opened = LaunchOptions.link(Intent(Intent.ACTION_VIEW, finished.route.deepLink.toString().toUri()))
        assertEquals("which the activity reads back as the session", SessionLink(deviceID = "d-1", sessionID = "s-1"), opened)

        val asking = demoSession(SessionState.needsInput)
        assertEquals("a provisional grant still delivers, with the same word", "Waiting for your answer",
                     notifier.announce(ran, asking, "mac", onScreen = false, enabled = true, authorization = PushAuthorization.provisional)?.body)
    }

    /** Amendment A35: the three statuses the gateway pushes for, and no others, under the same gates. */
    @Test
    fun aPauseAResumeAndADropAreNews() {
        val paused = DemoFixtures.sessions.first { it.sessionID == DemoFixtures.pausedSessionID }
        for ((status, kind) in listOf(
            ResumeStatus.scheduled to PushKind.limitReached,
            ResumeStatus.fired to PushKind.resumed,
            ResumeStatus.dropped to PushKind.resumeDropped,
        )) {
            val announced = TurnAlerts.kind(resume = status)
            assertEquals("${status.rawValue} raises the kind the gateway pushes", kind, announced)
            val alert = notifier.announce(kind, paused, "mac-studio-office", identifier = "resume/${paused.id}/${status.rawValue}",
                                          onScreen = false, enabled = true, authorization = PushAuthorization.authorized)
            assertNotNull("and the app raises its own notification for it", alert)
            assertFalse("with one of the three sentences", alert!!.body.isEmpty())
            assertTrue("titled with the machine and no time", alert.route.title.contains("mac-studio-office"))
            assertEquals("deduplicated on the event, not the moment", "resume/${paused.id}/${status.rawValue}", alert.identifier)
        }
        assertNull("a moved resume is not news", TurnAlerts.kind(resume = ResumeStatus.rescheduled))
        assertNull("and neither is one you cancelled", TurnAlerts.kind(resume = ResumeStatus.cancelled))
        assertNull("nothing is raised over the conversation it is about",
                   notifier.announce(PushKind.limitReached, paused, "mac", onScreen = true, enabled = true,
                                     authorization = PushAuthorization.authorized))
        assertNull("nor with the Notify me switch off",
                   notifier.announce(PushKind.limitReached, paused, "mac", onScreen = false, enabled = false,
                                     authorization = PushAuthorization.authorized))
    }

    /** The phone's own poster takes nothing to the system while the system does not allow it, as on a phone never asked. */
    @Test
    fun theSystemPosterPostsNothingUnallowed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val system = TurnNotifier(context)
        val alert = system.announce(ran, quiet, "mac", onScreen = false, enabled = true, authorization = PushAuthorization.authorized)
        assertNotNull("the notifier decided to announce", alert)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertEquals("and Android, which has not granted it, shows nothing", 0, manager.activeNotifications.size)
        assertEquals(URI("remotecontrol://session?device=d-1&id=s-1"), alert!!.route.deepLink)
    }
}

/** The posting side of a notification, with no notification manager behind it. */
internal class FakeTurnAlertPlatform : TurnAlertPlatform {
    val posted = mutableListOf<TurnAlert>()

    override fun post(alert: TurnAlert) {
        posted += alert
    }
}
