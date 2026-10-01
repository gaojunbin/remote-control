package com.junbingao.remotecontrol.win.notifications

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionResume
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.ModelHarness
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.platform.InertToasts
import com.junbingao.remotecontrol.win.platform.ToastTarget
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `gateway/rc_gateway/push.py`'s moments and words, as this PC posts them, and `web/tests/service-worker.test.ts`'s line. */
class NotificationRuleTests {
    private fun session(state: SessionState, resume: SessionResume? = null, id: String = "s1") =
        Session(sessionID = id, deviceID = "d1", agent = "claude", title = "t", cwd = "/", state = state, resume = resume)

    @Test
    fun aStateChangeIsTheGatewaysTransitionTable() {
        assertEquals(listOf(PushKind.turnCompleted), TurnNoticeRule.kinds(previous = session(SessionState.running), current = session(SessionState.idle)))
        assertEquals(listOf(PushKind.turnCompleted), TurnNoticeRule.kinds(previous = session(SessionState.needsInput), current = session(SessionState.idle)))
        assertEquals(listOf(PushKind.needsApproval), TurnNoticeRule.kinds(previous = session(SessionState.running), current = session(SessionState.needsApproval)))
        assertEquals(listOf(PushKind.needsInput), TurnNoticeRule.kinds(previous = session(SessionState.running), current = session(SessionState.needsInput)))
        assertEquals(listOf(PushKind.error), TurnNoticeRule.kinds(previous = session(SessionState.running), current = session(SessionState.error)))
    }

    @Test
    fun aSessionThatWasAlreadyQuietIsNoNews() {
        assertTrue(TurnNoticeRule.kinds(previous = session(SessionState.stopped), current = session(SessionState.idle)).isEmpty())
        assertTrue(TurnNoticeRule.kinds(previous = session(SessionState.idle), current = session(SessionState.idle)).isEmpty())
        assertTrue(TurnNoticeRule.kinds(previous = session(SessionState.idle), current = session(SessionState.running)).isEmpty())
        // Two different sessions are no transition at all.
        assertTrue(TurnNoticeRule.kinds(previous = session(SessionState.running, id = "a"), current = session(SessionState.idle, id = "b")).isEmpty())
    }

    @Test
    fun aResumeTheDeviceJustScheduledIsThePause() {
        val pending = SessionResume(at = 1_000)
        assertEquals(listOf(PushKind.limitReached), TurnNoticeRule.kinds(previous = session(SessionState.idle), current = session(SessionState.idle, resume = pending)))
        // A turn the limit ended and the pause it brings, in the order they happened.
        assertEquals(
            listOf(PushKind.turnCompleted, PushKind.limitReached),
            TurnNoticeRule.kinds(previous = session(SessionState.running), current = session(SessionState.idle, resume = pending)),
        )
        // A time moved is not news, and neither is the resume going.
        val moved = SessionResume(at = 2_000)
        assertTrue(TurnNoticeRule.kinds(previous = session(SessionState.idle, resume = pending), current = session(SessionState.idle, resume = moved)).isEmpty())
        assertTrue(TurnNoticeRule.kinds(previous = session(SessionState.idle, resume = pending), current = session(SessionState.idle)).isEmpty())
    }

    @Test
    fun onlyTheResumeThatRanOrWasGivenUpIsReadFromTheEvent() {
        assertEquals(PushKind.resumed, TurnNoticeRule.kind(resume = ResumeStatus.fired))
        assertEquals(PushKind.resumeDropped, TurnNoticeRule.kind(resume = ResumeStatus.dropped))
        assertEquals(null, TurnNoticeRule.kind(resume = ResumeStatus.scheduled))
        assertEquals(null, TurnNoticeRule.kind(resume = ResumeStatus.rescheduled))
        assertEquals(null, TurnNoticeRule.kind(resume = ResumeStatus.cancelled))
    }

    @Test
    fun itPostsOnlyWithTheSwitchOnAndThePermissionGiven() {
        val target = NoticeTarget(deviceID = "d1", sessionID = "s1")
        assertTrue(TurnNoticeRule.posts(target, enabled = true, permission = NotificationPermission.authorized, route = Route.Sessions, windowActive = true))
        assertFalse(TurnNoticeRule.posts(target, enabled = false, permission = NotificationPermission.authorized, route = Route.Sessions, windowActive = false))
        assertFalse(TurnNoticeRule.posts(target, enabled = true, permission = NotificationPermission.denied, route = Route.Sessions, windowActive = false))
        assertFalse(TurnNoticeRule.posts(target, enabled = true, permission = NotificationPermission.notDetermined, route = Route.Sessions, windowActive = false))
    }

    @Test
    fun theConversationOpenInTheFocusedWindowPostsNothing() {
        val target = NoticeTarget(deviceID = "d1", sessionID = "s1")
        val open = Route.Chat(deviceId = "d1", sessionId = "s1")
        assertFalse(TurnNoticeRule.posts(target, enabled = true, permission = NotificationPermission.authorized, route = open, windowActive = true))
        // Behind another window, or with the window closed, it is news again.
        assertTrue(TurnNoticeRule.posts(target, enabled = true, permission = NotificationPermission.authorized, route = open, windowActive = false))
        // Another conversation open is not this one.
        assertTrue(
            TurnNoticeRule.posts(
                target, enabled = true, permission = NotificationPermission.authorized,
                route = Route.Chat(deviceId = "d1", sessionId = "s2"), windowActive = true,
            ),
        )
    }
}

class NotificationWordsTests {
    private val target = NoticeTarget(deviceID = "dev-mac", sessionID = "ses-1")

    @Test
    fun theLineIsTheOneTheGatewayWrites() {
        val words = listOf(
            PushKind.needsApproval to "approval needed", PushKind.needsInput to "waiting for your answer",
            PushKind.turnCompleted to "turn finished", PushKind.error to "session error",
            PushKind.limitReached to "paused by the usage limit", PushKind.resumed to "resumed after the limit reset",
            PushKind.resumeDropped to "not resumed",
        )
        for ((kind, phrase) in words) {
            val notice = TurnNotice(kind = kind, target = target, deviceName = "mac-studio-office")
            assertEquals("mac-studio-office: $phrase", notice.body)
            assertEquals("Remote Control", notice.title)
        }
    }

    @Test
    fun aKindThisBuildHasNeverSeenStillSaysSomething() {
        val notice = TurnNotice(kind = PushKind("surprise"), target = target, deviceName = "box")
        assertEquals("box: update", notice.body)
    }
}

/** The notifier on a real model, with the inert platform standing in for Windows' notifications. */
class NotifierTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private fun signedIn(block: suspend WinAppModel.(InertNotificationPlatform) -> Unit) =
        ModelHarness(LaunchOptions(demo = true, ephemeral = true)).use { harness ->
            harness.run {
                restoreOrPrompt()
                harness.waitFor { connection.hasSnapshot }
                block(assertIs<InertNotificationPlatform>(notifier.platform))
            }
        }

    private val WinAppModel.notifier: TurnNotifier get() = SettingsFeature.state(of = this).notifier

    private fun session(state: SessionState, resume: SessionResume? = null) =
        Session(sessionID = "s1", deviceID = DemoFixtures.macDeviceID, agent = "claude", title = "t", cwd = "/", state = state, resume = resume)

    @Test
    fun turningItOnAsksTheSystemOnlyWhileItHasNeverBeenAsked() = signedIn { system ->
        system.granted = NotificationPermission.notDetermined
        notifier.refresh()
        notifier.turn(on = true)
        assertEquals(1, system.requests)
        assertTrue(notifier.isOn && settings.notificationsEnabled)
        notifier.turn(on = false)
        notifier.turn(on = true)
        assertEquals(1, system.requests)
        assertTrue(notifier.isOn)
        signOut()
    }

    @Test
    fun aRefusalLeavesItOffAndSaysBlocked() = signedIn { system ->
        system.granted = NotificationPermission.notDetermined
        system.answer = NotificationPermission.denied
        notifier.turn(on = true)
        assertTrue(!notifier.isOn && !settings.notificationsEnabled)
        assertEquals(NotificationPermission.denied, notifier.permission)
        signOut()
    }

    @Test
    fun itPostsTheGatewaysMomentsUnderTheDevicesName() = signedIn { system ->
        notifier.turn(on = true)
        notifier.sessionChanged(from = session(SessionState.running), to = session(SessionState.idle))
        assertEquals(listOf("mac-studio-office: turn finished"), system.posted.map { it.body })
        notifier.sessionChanged(from = session(SessionState.idle), to = session(SessionState.running))
        assertEquals(1, system.posted.size)
        signOut()
    }

    @Test
    fun nothingIsPostedWithTheSwitchOff() = signedIn { system ->
        notifier.sessionChanged(from = session(SessionState.running), to = session(SessionState.needsApproval))
        assertTrue(system.posted.isEmpty())
        signOut()
    }

    @Test
    fun theConversationOnScreenIsNotAnnounced() = signedIn { system ->
        notifier.turn(on = true)
        router.go(Route.Chat(deviceId = DemoFixtures.macDeviceID, sessionId = "s1"))
        isWindowActive = true
        notifier.sessionChanged(from = session(SessionState.running), to = session(SessionState.needsInput))
        assertTrue(system.posted.isEmpty())
        isWindowActive = false
        notifier.sessionChanged(from = session(SessionState.running), to = session(SessionState.needsInput))
        assertEquals(listOf(PushKind.needsInput), system.posted.map { it.kind })
        signOut()
    }

    @Test
    fun aResumeThatRanOrWasDroppedIsReadFromItsEvent() = signedIn { system ->
        notifier.turn(on = true)
        val known = assertNotNull(connection.sessions.firstOrNull())
        for ((seq, status) in listOf(1 to ResumeStatus.fired, 2 to ResumeStatus.scheduled, 3 to ResumeStatus.dropped, 4 to ResumeStatus.cancelled)) {
            val event = SessionEvent(seq = seq, ts = 0, kind = SessionEvent.resumeKind, body = SessionEventBody.Resume(ResumePayload(status = status)))
            notifier.receive(AppFrame.SessionEvent(sessionID = known.sessionID, deviceID = known.deviceID, event = event))
        }
        assertEquals(listOf(PushKind.resumed, PushKind.resumeDropped), system.posted.map { it.kind })
        assertTrue(system.posted.all { it.target.sessionID == known.sessionID })
        signOut()
    }

    @Test
    fun aClickOpensItsConversationAndASignOutTakesWhatWasPosted() = signedIn { system ->
        router.replace(Route.Sessions)
        // A click is the window's: the model's toasts open what the notification names.
        val live = ToastTarget(deviceId = DemoFixtures.macDeviceID, sessionId = DemoFixtures.liveSessionID)
        toasts.onOpen?.invoke(live)
        assertEquals(Route.Chat(deviceId = live.deviceId, sessionId = live.sessionId), router.route)
        assertTrue(router.canGoBack)
        notifier.turn(on = true)
        notifier.sessionChanged(from = session(SessionState.running), to = session(SessionState.error))
        assertTrue(system.posted.isNotEmpty())
        signOut()
        assertTrue(system.posted.isEmpty())
    }

    /** Windows' own platform posts through the window's toasts, and Windows asks nobody: its answer is never "not asked". */
    @Test
    fun windowsNotificationsGoThroughTheWindowsToasts() = signedIn {
        val windows = SystemNotificationPlatform(this)
        assertNotEquals(NotificationPermission.notDetermined, windows.permission())
        assertEquals(windows.permission(), windows.requestPermission())
        val target = NoticeTarget(deviceID = DemoFixtures.macDeviceID, sessionID = "s1")
        windows.post(TurnNotice(kind = PushKind.needsApproval, target = target, deviceName = "mac-studio-office"))
        val shown = assertIs<InertToasts>(toasts).posted
        assertEquals(listOf("mac-studio-office: approval needed"), shown.map { it.body })
        assertEquals(listOf("Remote Control"), shown.map { it.title })
        assertEquals(ToastTarget(deviceId = DemoFixtures.macDeviceID, sessionId = "s1"), shown.single().target)
        windows.removeDelivered()
        assertTrue(assertIs<InertToasts>(toasts).posted.isEmpty())
        signOut()
    }
}
