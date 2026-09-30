package com.junbingao.remotecontrol.core.state

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where speech becomes text. From RCCore's `SettingsStore.swift`; the overload that reads the
 * stores is the stores' own, so it lives with them.
 */
@Serializable
enum class VoiceBackend(val rawValue: String) {
    /** The phone's own recogniser. Audio never leaves the phone. */
    @SerialName("onDevice") onDevice("onDevice"),

    /** The gateway's streaming endpoint. Audio is uploaded to your own gateway. */
    @SerialName("gateway") gateway("gateway");

    val title: String
        get() = when (this) {
            onDevice -> L10n.string("On this iPhone")
            gateway -> L10n.string("Gateway")
        }

    val explanation: String
        get() = when (this) {
            onDevice -> L10n.string("Audio stays on this device. Needs an on-device model for the language you pick.")
            gateway -> L10n.string("Audio is streamed to your gateway, which recognises the language itself.")
        }

    companion object {
        val allCases: List<VoiceBackend> get() = entries

        operator fun invoke(rawValue: String): VoiceBackend? = entries.firstOrNull { it.rawValue == rawValue }

        /**
         * Amendment A44: the backend that really turns speech into text. The gateway transcribes
         * only where it has a transcription service; anywhere else dictation falls back to this
         * phone, whichever was chosen. The composer, Settings and the speech backend read this one
         * answer, so a language is offered exactly where the phone is the one listening.
         */
        fun inEffect(chosen: VoiceBackend, gatewayTranscribes: Boolean): VoiceBackend =
            if (chosen == gateway && gatewayTranscribes) gateway else onDevice
    }
}
