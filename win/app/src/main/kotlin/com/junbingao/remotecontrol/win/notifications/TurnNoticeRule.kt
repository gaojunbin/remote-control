package com.junbingao.remotecontrol.win.notifications

import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.TurnAlerts
import com.junbingao.remotecontrol.win.app.Route

/**
 * When this PC posts, and when it keeps quiet: the moments the gateway pushes for
 * (`transition_kind` and `resume_kind` in `gateway/rc_gateway/push.py`, cued by the hub's
 * transition and resume hooks), and the one rule of the app's own — nothing about the
 * conversation open in the focused window.
 */
object TurnNoticeRule {
    /**
     * What a session's newer version is news of, in the order it is posted. A state change is
     * `TurnAlerts`' table, which is the gateway's. A resume the device has just scheduled is the
     * pause the gateway pushes for; it is read from the session, which every app socket of the
     * account is sent, rather than from the `resume` event, which only the socket watching that
     * session receives.
     */
    fun kinds(previous: Session, current: Session): List<PushKind> {
        val kinds = mutableListOf<PushKind>()
        TurnAlerts.kind(previous = previous, current = current)?.let { kinds += it }
        if (previous.id == current.id && previous.resume == null && current.resume != null) {
            kinds += PushKind.limitReached
        }
        return kinds
    }

    /**
     * A `resume` event's news, for the two statuses a session's own fields cannot tell from a
     * person's cancel: the resume ran, or it was given up. A scheduled one is the session's pause
     * above, and a rescheduled or a cancelled one is not news.
     */
    fun kind(resume: ResumeStatus): PushKind? = when (resume) {
        ResumeStatus.fired -> PushKind.resumed
        ResumeStatus.dropped -> PushKind.resumeDropped
        else -> null
    }

    /**
     * Whether a notice is posted: the switch is on, Windows allows it, and the conversation it
     * names is not open in the focused window.
     */
    fun posts(target: NoticeTarget, enabled: Boolean, permission: NotificationPermission, route: Route, windowActive: Boolean): Boolean {
        if (!enabled || permission != NotificationPermission.authorized) return false
        return !(windowActive && route == Route.Chat(deviceId = target.deviceID, sessionId = target.sessionID))
    }
}
