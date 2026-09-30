package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.core.transport.PolishStrength
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Amendments A35 and A41: the settings that must read the same on the phone, in the browser and
 * on every device of the account, so the gateway keeps them and no app keeps a copy of its own.
 *
 * `resume_after_limit` came first (A35) and is always there. A41 added the Settings screen's own —
 * the interface language, the dictation language, polish with its model and strength, and the
 * timeline detail — and every one of them is optional: absent means nobody has set it yet, and an
 * app then writes its own value up once (`PreferenceSync`). A gateway older than A35 sends no
 * object at all, which is `null` in the app and a resume switch shown disabled with a note.
 *
 * An unknown word in one of the enum fields reads as absent rather than failing the frame: the
 * gateway validates every write, so a value this build does not know is one a later build added,
 * and the rest of the object is still the account's.
 */
@Serializable
data class Preferences(
    /**
     * Whether a session the vendor's usage limit stopped is resumed by its device once the limit
     * resets (protocol 7.2). Off until the person turns it on.
     */
    @SerialName("resume_after_limit") val resumeAfterLimit: Boolean = false,
    /** The app's interface language (A41). */
    val language: InterfaceLanguage? = null,
    /**
     * The language a phone that recognises speech itself listens for — `zh`, `en`, … — Chinese
     * when unset; an `auto` written before A44 reads as unset (A41, A44).
     */
    @SerialName("stt_language") val sttLanguage: String? = null,
    /** Whether a finished dictation goes through the gateway's polish model (A29, A41). */
    @SerialName("polish_enabled") val polishEnabled: Boolean? = null,
    /** The polish model chosen from `GET /api/polish/models`; empty when none. */
    @SerialName("polish_model") val polishModel: String? = null,
    /** How far the polish may go (A29, A41). */
    @SerialName("polish_strength") val polishStrength: PolishStrength? = null,
    /** How much of a transcript is drawn (A41). */
    @SerialName("timeline_detail") val timelineDetail: TimelineDetail? = null,
) {
    /**
     * The object a `PATCH` leaves behind: the fields the write names, and the rest as they were
     * (protocol 3.2). The gateway is the one writer; this is how the app draws the write before
     * the round trip and how the demo gateway keeps the account's copy.
     */
    fun applying(changes: PreferencePatch): Preferences = Preferences(
        resumeAfterLimit = changes.resumeAfterLimit ?: resumeAfterLimit,
        language = changes.language ?: language,
        sttLanguage = changes.sttLanguage ?: sttLanguage,
        polishEnabled = changes.polishEnabled ?: polishEnabled,
        polishModel = changes.polishModel ?: polishModel,
        polishStrength = changes.polishStrength ?: polishStrength,
        timelineDetail = changes.timelineDetail ?: timelineDetail,
    )
}

/**
 * The body of `PATCH /api/preferences` (protocol 3.2): the fields to set and no others. A field
 * left out is left alone, and the gateway answers with the whole object.
 */
@Serializable
data class PreferencePatch(
    @SerialName("resume_after_limit") val resumeAfterLimit: Boolean? = null,
    val language: InterfaceLanguage? = null,
    @SerialName("stt_language") val sttLanguage: String? = null,
    @SerialName("polish_enabled") val polishEnabled: Boolean? = null,
    @SerialName("polish_model") val polishModel: String? = null,
    @SerialName("polish_strength") val polishStrength: PolishStrength? = null,
    @SerialName("timeline_detail") val timelineDetail: TimelineDetail? = null,
) {
    /** Nothing to write, which is a request nobody should send. */
    val isEmpty: Boolean get() = this == PreferencePatch()
}

/** The body of `GET` and `PATCH /api/preferences` (protocol 3.2). */
@Serializable
data class PreferencesResponse(val preferences: Preferences = Preferences())
