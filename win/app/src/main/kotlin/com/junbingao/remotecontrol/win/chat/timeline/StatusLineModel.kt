package com.junbingao.remotecontrol.win.chat.timeline

import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.win.strings.S

/**
 * `web/src/features/chat/StatusLine.tsx`: the single line under the timeline that explains what
 * happens next, or nothing at all when there is nothing to explain (`docs/DESIGN.md` § "Status
 * vocabulary").
 *
 * `offersTakeover`: whether the line ends in a Take over link — only where the agent can be taken
 * over, which is also where the words invite it.
 */
data class StatusLineModel(val tone: Tone, val text: String, val offersTakeover: Boolean = false) {
    enum class Tone { running, attention, error, muted }

    companion object {
        /**
         * The line for a session, or null where the web draws none.
         *
         * `editingQueued` is A43: the composer holds a queued message being edited, which goes back
         * into the line even behind a steering agent — so the line says queued, as the button beside
         * the field does.
         */
        fun of(session: Session, agent: AgentInfo?, deviceOnline: Boolean, editingQueued: Boolean = false): StatusLineModel? {
            val name = S.agentLabel(session.agent)
            val steers = (agent?.supports(AgentCapability.steer) ?: false) && !editingQueued
            val canTakeover = agent?.supports(AgentCapability.takeover) ?: false

            if (!deviceOnline) return StatusLineModel(Tone.muted, S.status.offline)

            if (session.control == SessionControl.terminal) {
                val busy = session.state == SessionState.running || session.state == SessionState.starting
                // This line says who holds the session, what is happening there, and how to write
                // to it — that last clause only where the agent can be taken over, which is also
                // where the button is.
                val clauses = listOfNotNull(
                    S.status.terminalControlled,
                    if (busy) S.status.terminalBusy else null,
                    if (canTakeover) S.status.takeOverToSend else null,
                )
                return StatusLineModel(if (busy) Tone.running else Tone.muted, clauses.joinToString(" · "), offersTakeover = canTakeover)
            }

            return when (session.state) {
                SessionState.running -> StatusLineModel(Tone.running, if (steers) S.status.workingSteer(name) else S.status.workingQueued(name))
                SessionState.starting -> StatusLineModel(Tone.running, S.status.starting)
                SessionState.needsApproval -> StatusLineModel(Tone.attention, S.status.needsApproval)
                SessionState.needsInput -> StatusLineModel(Tone.attention, S.status.needsInput)
                SessionState.error -> StatusLineModel(Tone.error, session.stateDetail ?: S.status.errored)
                SessionState.stopped -> StatusLineModel(Tone.muted, S.status.stopped)
                else -> null
            }
        }
    }
}
