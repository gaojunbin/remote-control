package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState

/**
 * How a session's status dot looks, on a session row, in the chat header and anywhere else the
 * dot appears.
 *
 * The tone is a pure function of the three facts the reader cares about: what the agent is doing,
 * who still owns the session, and whether the machine is reachable at all. `state` alone is not
 * enough — a finished turn on a live session and a session whose CLI exited both report `idle`,
 * and they must not look the same. On a session row the colour is the whole of the state, and
 * [DotLegend] is what says so in words; the chat header still spells the state out beside its dot.
 *
 * The colour carries the meaning on its own: green means working — leave it; amber means there is
 * something for you, a finished turn to read or a question to answer. Only [waiting] moves, so the
 * one state that needs the person is the one that asks for a glance.
 *
 * `docs/DESIGN.md` states the same table for both apps.
 */
enum class DotTone(val rawValue: String) {
    /** Green, solid: a turn is under way and needs nobody. */
    working("working"),

    /** Amber, pulsing: the agent is blocked on the user. */
    waiting("waiting"),

    /**
     * Amber, solid: alive and quiet — a turn finished and its result is there to be looked at, a
     * terminal is still open, or the device holds the session.
     */
    live("live"),

    /** Grey: nothing owns it any more, it was stopped, or the machine is gone. */
    off("off"),

    /** Red, solid: the agent reported an error. */
    failed("failed");

    companion object {
        val allCases: List<DotTone> get() = entries

        operator fun invoke(rawValue: String): DotTone? = entries.firstOrNull { it.rawValue == rawValue }

        /**
         * The whole rule, in the order the cases are decided.
         *
         * A machine that cannot be reached says so before anything else: whatever the device last
         * reported about a session is history, not status.
         */
        fun of(state: SessionState, control: SessionControl, online: Boolean): DotTone {
            if (!online) return off
            return when (state) {
                SessionState.error -> failed
                SessionState.starting, SessionState.running -> working
                SessionState.needsApproval, SessionState.needsInput -> waiting
                SessionState.idle, SessionState.readonly -> if (control == SessionControl.none) off else live
                else -> off
            }
        }
    }
}

/** The dot this session shows, given whether its device is reachable. */
fun Session.dotTone(online: Boolean): DotTone = DotTone.of(state = state, control = control, online = online)
