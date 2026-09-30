package com.junbingao.remotecontrol.core.state

import java.util.Locale

/**
 * Amendment A44: the language the phone's own recogniser listens for.
 *
 * A platform recogniser cannot detect a language; it listens for the one it is given. So where
 * the phone transcribes, the person picks one, and nowhere else: the gateway's provider detects
 * the language itself and is told nothing. The list is the recogniser's rather than the gateway's
 * `stt.languages` (always `["auto"]` since A44), and has no Automatic.
 *
 * The account's `stt_language` holds the code. Unset, `auto` (written before A44) or a code the
 * list does not hold reads as Chinese, and is left where it stands rather than written back.
 */
object DictationLanguage {
    /** Each language with the locale the recogniser is handed for it, Chinese first. */
    private val locales: List<Pair<String, String>> = listOf(
        "zh" to "zh-CN",
        "en" to "en-US",
        "ja" to "ja-JP",
        "de" to "de-DE",
        "fr" to "fr-FR",
        "es" to "es-ES",
    )

    /** What the recogniser is offered, in the order the menus list them. */
    val codes: List<String> = locales.map { it.first }

    /** What a new install listens for, and what anything unknown reads as. */
    const val standard = "zh"

    /** The code the phone listens for, given what the account or this phone stored. */
    fun effective(stored: String?): String = if (stored != null && stored in codes) stored else standard

    /** The locale handed to the recogniser for a code. */
    fun localeIdentifier(code: String): String {
        val listened = effective(code)
        return locales.firstOrNull { it.first == listened }?.second ?: "zh-CN"
    }

    /** A language's name in the app's own language, not the phone's: the menu is one of the app's words. */
    fun name(of: String, language: InterfaceLanguage): String =
        Locale.forLanguageTag(of).getDisplayLanguage(language.locale).ifEmpty { of }

    /**
     * Amendment A44: the polish request's language hint — `auto` for words the gateway
     * transcribed, the code the phone listened for otherwise.
     */
    fun polishHint(backend: VoiceBackend, listening: String): String =
        if (backend == VoiceBackend.gateway) "auto" else effective(listening)
}
