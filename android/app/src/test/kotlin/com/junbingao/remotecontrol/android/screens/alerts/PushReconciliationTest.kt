package com.junbingao.remotecontrol.android.screens.alerts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.push.PushAuthorization
import com.junbingao.remotecontrol.android.strings.L10n
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ios/VerificationUI`'s "Push reconciliation": one preference and one system authorization, on a
 * platform with no notification manager behind it. Android registers nothing with a gateway yet,
 * so where the iPhone's switch reads "On" once a token is registered, this one reads "On, in this
 * app only" — the demo's answer on the iPhone — wherever the system allows it.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PushReconciliationTest {
    @After
    fun englishAgain() = L10n.use(L10n.english)

    @Test
    fun theSwitchAndTheSystemAgree() = runTest {
        val platform = FakeNotificationPlatform()
        val push = PushController(platform, backgroundScope)
        push.attach(api = null, enabled = false)
        runCurrent()
        assertEquals("notifications start off", "Off", push.statusText)

        platform.authorizationValue = PushAuthorization.authorized
        push.setEnabled(true)
        runCurrent()
        assertEquals("an authorized device posts the app's own notifications", "On, in this app only", push.statusText)
        assertEquals("and reads the system's answer", PushAuthorization.authorized, push.authorization)

        push.setEnabled(false)
        runCurrent()
        assertEquals("turning notifications off", "Off", push.statusText)
        assertTrue("takes down what the app posted", platform.unregisterCalls > 0)

        platform.authorizationValue = PushAuthorization.denied
        push.setEnabled(true)
        runCurrent()
        assertTrue("a denied device says where to fix it", push.statusText.contains("Blocked"))
        assertEquals("in Android's own words", "Blocked in Android Settings", push.statusText)
    }

    /** The demo, and the moment before a sign-in: there is nobody to hand anything to, and the switch still reads on. */
    @Test
    fun withNoGatewayTheSwitchStillReadsOn() = runTest {
        val platform = FakeNotificationPlatform().apply { authorizationValue = PushAuthorization.authorized }
        val push = PushController(platform, backgroundScope)
        push.attach(api = null, enabled = true)
        runCurrent()
        assertEquals("with no gateway the switch still reads on", "On, in this app only", push.statusText)
    }

    /** Every step waits for the one before it, so a system answer that is slow to come can never land after a later change. */
    @Test
    fun aSlowAnswerNeverOvertakesALaterChange() = runTest {
        val platform = FakeNotificationPlatform().apply { authorizationValue = PushAuthorization.authorized }
        val held = CompletableDeferred<Unit>()
        platform.holdNextAnswer = held
        val push = PushController(platform, backgroundScope)
        push.attach(api = null, enabled = true)
        push.setEnabled(false)
        runCurrent()
        assertEquals("the second step has not started while the first waits for the system", 1, platform.questions)
        held.complete(Unit)
        runCurrent()
        assertEquals("it runs once the first is done", 2, platform.questions)
        assertEquals("and the later change is the one that stands", "Off", push.statusText)
    }

    /** The switch asks the system only when it has never been asked, and reads the answer it gave. */
    @Test
    fun theSwitchAsksOnceAndReadsTheAnswer() = runTest {
        val platform = FakeNotificationPlatform()
        val push = PushController(platform, backgroundScope)
        push.attach(api = null, enabled = true)
        runCurrent()
        assertEquals("a phone never asked is waiting for permission", "Waiting for permission", push.statusText)

        var asked = 0
        push.requestAuthorizationIfNeeded {
            asked += 1
            platform.authorizationValue = PushAuthorization.authorized
        }
        runCurrent()
        assertEquals("the system is asked", 1, asked)
        assertEquals("and the answer is what the switch reads", "On, in this app only", push.statusText)

        push.requestAuthorizationIfNeeded { asked += 1 }
        runCurrent()
        assertEquals("a phone that has answered is not asked again", 1, asked)
    }

    /** No notifications on this device at all: the switch says so in the row and cannot be turned on. */
    @Test
    fun aPhoneWithoutNotificationsSaysSo() = runTest {
        val platform = FakeNotificationPlatform().apply { supported = false }
        val push = PushController(platform, backgroundScope)
        push.attach(api = null, enabled = true)
        runCurrent()
        assertFalse(push.isSupported)
        assertEquals("Not available on this device", push.statusText)
        assertEquals(PushAuthorization.unsupported, push.authorization)
    }

    /** The words are built at read time, so a change of language reaches a status already reached. */
    @Test
    fun theStatusFollowsAChangeOfLanguage() = runTest {
        val push = PushController(FakeNotificationPlatform(), backgroundScope)
        push.attach(api = null, enabled = false)
        runCurrent()
        assertEquals("Off", push.statusText)
        L10n.use(L10n.chinese)
        assertEquals(L10n.string("Off"), push.statusText)
        assertTrue("in the language chosen since", push.statusText != "Off")
    }

    /** Signing out takes down what the account left behind. */
    @Test
    fun detachingTakesTheNotificationsDown() = runTest {
        val platform = FakeNotificationPlatform().apply { authorizationValue = PushAuthorization.authorized }
        val push = PushController(platform, backgroundScope)
        push.attach(api = null, enabled = true)
        runCurrent()
        val before = platform.unregisterCalls
        push.detach()
        runCurrent()
        assertEquals("Off", push.statusText)
        assertTrue(platform.unregisterCalls > before)
    }
}

/** A notification platform with no notification manager behind it. */
internal class FakeNotificationPlatform : NotificationPlatform {
    override var supported = true
    var authorizationValue = PushAuthorization.notDetermined
    var unregisterCalls = 0
    var settingsOpened = 0

    /** Holds the next answer back until it is completed, for the ordering check. */
    var holdNextAnswer: CompletableDeferred<Unit>? = null

    /** How many times the system has been asked what it allows. */
    var questions = 0

    override suspend fun authorization(): PushAuthorization {
        questions += 1
        holdNextAnswer?.let {
            holdNextAnswer = null
            it.await()
        }
        return authorizationValue
    }

    override fun unregister() {
        unregisterCalls += 1
    }

    override fun openSettings() {
        settingsOpened += 1
    }
}
