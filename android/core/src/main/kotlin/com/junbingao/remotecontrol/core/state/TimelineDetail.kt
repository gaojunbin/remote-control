package com.junbingao.remotecontrol.core.state

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How much of a transcript is drawn. Amendment A41 makes the level the account's rather than this
 * phone's — it is a reading preference and the person reads on more than one screen — and the
 * store holds every block whichever level is chosen. From RCCore's `SettingsStore.swift`.
 */
@Serializable
enum class TimelineDetail(val rawValue: String) {
    /** Only what is written to the reader. */
    @SerialName("simple") simple("simple"),

    /** Everything the agent did, including thinking and every tool call. */
    @SerialName("detailed") detailed("detailed");

    val title: String
        get() = when (this) {
            simple -> L10n.string("Simple")
            detailed -> L10n.string("Detailed")
        }

    val explanation: String
        get() = when (this) {
            simple -> L10n.string("Simple shows only what is written to you.")
            detailed -> L10n.string("Detailed adds thinking, tool calls and the task list.")
        }

    companion object {
        val allCases: List<TimelineDetail> get() = entries

        operator fun invoke(rawValue: String): TimelineDetail? = entries.firstOrNull { it.rawValue == rawValue }

        /**
         * The sentence under the control. A choice between two options has to describe both, so
         * it is the explanations in the order they are offered.
         */
        val footnote: String get() = allCases.joinToString(" ") { it.explanation }
    }
}
