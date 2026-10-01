package com.junbingao.remotecontrol.win.standin

/**
 * How much of a conversation the timeline shows: RCCore's `TimelineDetail`, which the Kotlin core
 * ports as `com.junbingao.remotecontrol.core.state.TimelineDetail`. It stands in for the core's
 * until stage 2 wires the core in and deletes this package.
 */
enum class TimelineDetail(val rawValue: String) {
    simple("simple"),
    detailed("detailed"),
}
