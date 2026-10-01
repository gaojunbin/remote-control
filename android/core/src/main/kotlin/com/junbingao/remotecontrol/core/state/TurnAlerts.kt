package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.PushKind
import com.junbingao.remotecontrol.core.protocol.ResumeStatus
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionState

/**
 * The one moment a person who walked away from the phone asked to be told about: a session that was
 * working and now is not.
 *
 * The table is the gateway's own — `gateway/rc_gateway/push.py` `transition_kind` — so a banner this
 * app raises for itself and a push the gateway sends mean exactly the same thing and carry the same
 * word. `docs/DESIGN.md` § "Being told when a turn ends".
 */
object TurnAlerts {
    /**
     * The states a turn can be interrupted from. Returning to `idle` from one of them is a finished
     * turn; returning to `idle` from anywhere else is a session that was already quiet.
     */
    private val active: Set<SessionState> = setOf(SessionState.running, SessionState.needsApproval, SessionState.needsInput)

    fun kind(previous: SessionState, current: SessionState): PushKind? {
        if (current == previous) return null
        return when (current) {
            SessionState.needsApproval -> PushKind.needsApproval
            SessionState.needsInput -> PushKind.needsInput
            SessionState.error -> PushKind.error
            SessionState.idle -> if (previous in active) PushKind.turnCompleted else null
            else -> null
        }
    }

    /**
     * Nothing is announced for a session seen for the first time: the `hello` list is what the app
     * knows, not something that just happened. A pair that names two different sessions is not a
     * transition either.
     */
    fun kind(previous: Session?, current: Session): PushKind? {
        if (previous == null || previous.id != current.id) return null
        return kind(previous = previous.state, current = current.state)
    }

    /**
     * Amendment A35: the three `resume` statuses the gateway pushes for, and which this app
     * therefore announces for itself while it is open. A rescheduled resume is a detail of a pause
     * already told, and a cancelled one is the person's own doing; neither is news.
     */
    fun kind(resume: ResumeStatus): PushKind? = when (resume) {
        ResumeStatus.scheduled -> PushKind.limitReached
        ResumeStatus.fired -> PushKind.resumed
        ResumeStatus.dropped -> PushKind.resumeDropped
        else -> null
    }
}
