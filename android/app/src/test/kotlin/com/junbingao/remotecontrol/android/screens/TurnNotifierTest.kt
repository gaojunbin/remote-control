package com.junbingao.remotecontrol.android.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.push.PushAuthorization
import com.junbingao.remotecontrol.android.screens.alerts.TurnNotifier
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** `docs/DESIGN.md` § "The Android app": a turn's end is announced, unless its conversation is on screen. */
@RunWith(AndroidJUnit4::class)
class TurnNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val running = Session(sessionID = "s", deviceID = "d", state = SessionState.running, updatedAt = 1)
    private val idle = running.copy(state = SessionState.idle, updatedAt = 2)

    @Test
    fun aFinishedTurnIsAnnouncedWithTheStatusWord() {
        val alert = TurnNotifier(context).announce(running, idle, deviceName = "mac-studio-office", onScreen = false,
                                                   enabled = true, authorization = PushAuthorization.authorized)
        assertEquals("mac-studio-office", alert?.title)
        assertEquals("Turn finished", alert?.body)
        assertEquals(PushKind.turnCompleted, alert?.route?.kind)
        assertEquals("d/s", alert?.threadIdentifier)
    }

    @Test
    fun eachGateHoldsItBack() {
        val notifier = TurnNotifier(context)
        assertNull("its conversation is on screen", notifier.announce(running, idle, "m", onScreen = true, enabled = true,
                                                                       authorization = PushAuthorization.authorized))
        assertNull("the switch is off", notifier.announce(running, idle, "m", onScreen = false, enabled = false,
                                                           authorization = PushAuthorization.authorized))
        assertNull("the system refused", notifier.announce(running, idle, "m", onScreen = false, enabled = true,
                                                            authorization = PushAuthorization.denied))
        assertNull("nothing changed", notifier.announce(idle, idle, "m", onScreen = false, enabled = true,
                                                         authorization = PushAuthorization.authorized))
    }
}
