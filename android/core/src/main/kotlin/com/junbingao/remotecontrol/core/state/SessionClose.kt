package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Session

/**
 * Closing a session from a list row (amendment A39; `docs/DESIGN.md` § "Close, then the Archive").
 *
 * `session.archive {archived: true}` ends the session on the machine — the turn is interrupted,
 * what the device holds for the agent is released — and the row lands in the Archive afterwards.
 * Which rows offer it is `SessionListLayout.offersClose`; this is the one question left: whether
 * the app asks before it does that.
 */
object SessionClose {
    /**
     * The dialog is for the one case where something is lost: the agent is working, so the
     * unfinished part of its turn goes with the session. Every other row closes on the tap, because
     * there is nothing to lose.
     *
     * "Working" is the dot's own word, not a second rule: the green tone of [DotTone], which already
     * knows that a machine nobody can reach reports nothing worth stopping.
     */
    fun asksFirst(session: Session, online: Boolean): Boolean = session.dotTone(online = online) == DotTone.working
}
