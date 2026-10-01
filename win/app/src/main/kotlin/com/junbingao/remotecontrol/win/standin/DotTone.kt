package com.junbingao.remotecontrol.win.standin

/**
 * How a session's status dot looks: RCCore's `DotTone`, which the Kotlin core ports with the rule
 * that picks it from a session's state, its control owner and its device. The foundation draws
 * the five tones before the core exists, so this stands in for the core's until stage 2 wires the
 * core in and deletes this package.
 *
 * Green means working — leave it; amber means there is something for you. Only `waiting` moves.
 */
enum class DotTone(val rawValue: String) {
    /** Green, solid: a turn is under way and needs nobody. */
    working("working"),

    /** Amber, pulsing: the agent is blocked on the user. */
    waiting("waiting"),

    /** Amber, solid: alive and quiet — a finished turn to read, or a terminal still open. */
    live("live"),

    /** Grey: nothing owns it any more, it was stopped, or the machine is gone. */
    off("off"),

    /** Red, solid: the agent reported an error. */
    failed("failed"),
}
