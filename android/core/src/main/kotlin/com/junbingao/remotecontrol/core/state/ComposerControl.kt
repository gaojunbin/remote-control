package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.SharedSetting

/**
 * Amendments A43 and A44: the controls of the composer's row, in the one order `docs/DESIGN.md`
 * gives them — what waits, how you speak, what runs, what it may do.
 *
 * Up next stands only while something is queued, the dictation language only where the phone
 * recognises speech itself, and the model card and the permission mode wherever the session has
 * them to show, as a control or as the value a terminal set (A17). The order lives here, away from
 * the view, so it is one rule that can be checked without a screen.
 */
enum class ComposerControl(val rawValue: String) {
    upNext("upNext"),
    dictationLanguage("dictationLanguage"),
    modelCard("modelCard"),
    permissions("permissions");

    val id: String get() = rawValue

    companion object {
        val allCases: List<ComposerControl> get() = entries

        operator fun invoke(rawValue: String): ComposerControl? = entries.firstOrNull { it.rawValue == rawValue }

        /** The Up next icon's accessible value: the count, in words, because the badge that carries it on screen says nothing to a screen reader. */
        fun upNextValue(queued: Int): String = L10n.string(if (queued == 1) "%lld message" else "%lld messages", queued)
    }
}

/** The row for this session, leading edge first, given the backend that is transcribing (`VoiceBackend.inEffect`). */
fun ChatStore.controlRow(backend: VoiceBackend): List<ComposerControl> {
    val row = mutableListOf<ComposerControl>()
    if (session.queued > 0) row.add(ComposerControl.upNext)
    if (backend == VoiceBackend.onDevice) row.add(ComposerControl.dictationLanguage)
    if (allowsModelCardChanges || terminalSetting(TerminalSetting.Field.modelCard) != null) row.add(ComposerControl.modelCard)
    if (offersPermissionPicker || terminalSetting(TerminalSetting.Field.permissionMode) != null) row.add(ComposerControl.permissions)
    return row
}

/**
 * Amendment A25: the permission picker is offered where this app may change the mode and the agent
 * has modes to choose from. An agent with no permission system (pi) lists none, and nothing stands
 * in its place.
 */
val ChatStore.offersPermissionPicker: Boolean
    get() = allowsSettingsChanges(SharedSetting.permissionMode) && agent?.permissionModes?.isEmpty() == false
