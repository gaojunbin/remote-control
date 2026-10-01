package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.win.shared.Attach

/**
 * What the composer may do for one session, read the way `Composer.tsx` reads it: from `control`,
 * never from `state` (A7), and from what the agent says an attachment carries (A10, A11, A40).
 */
data class ComposerGates(
    val terminalControlled: Boolean,
    /** A10: a live CLI owns the session and the device is attached to it. */
    val shared: Boolean,
    val running: Boolean,
    val canSteer: Boolean,
    val canTakeover: Boolean,
    /**
     * The composer is gated on `control`, never on `state`: a mirrored session reports `running`
     * while the terminal drives the turn.
     */
    val disabled: Boolean,
    /** A10: the channel cannot interrupt a running turn, so neither can we. */
    val canInterrupt: Boolean,
    /** A11: a control the attachment cannot drive is hidden, never disabled. */
    val showAttach: Boolean,
    private val settable: Set<SharedSetting>,
) {
    /** Whether this one setting is the device's to change from here. */
    fun canSet(key: SharedSetting): Boolean = key in settable

    companion object {
        private val turnStates = setOf(SessionState.running, SessionState.needsApproval, SessionState.needsInput, SessionState.starting)

        operator fun invoke(session: Session, agent: AgentInfo?, deviceOnline: Boolean): ComposerGates {
            val terminal = session.control == SessionControl.terminal
            val shared = session.control == SessionControl.shared
            return ComposerGates(
                terminalControlled = terminal,
                shared = shared,
                running = session.state in turnStates,
                canSteer = agent?.supports(AgentCapability.steer) ?: false,
                canTakeover = agent?.supports(AgentCapability.takeover) ?: false,
                disabled = terminal || !deviceOnline,
                canInterrupt = if (shared) Attach.canInterruptShared(agent) else true,
                showAttach = !shared || Attach.canAttachShared(agent),
                // A17/A40: a terminal session refuses `session.set` outright, and a shared one takes
                // only the settings its agent carries; what the device cannot change is shown as the
                // value the terminal chose.
                settable = SharedSetting.allCases.filter { key -> !terminal && (!shared || Attach.canSetShared(agent, key)) }.toSet(),
            )
        }
    }
}
