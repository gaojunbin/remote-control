package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.formatted
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * The language the app writes its own words in.
 *
 * It is a reading preference like the timeline detail: it changes menus, buttons, captions,
 * status lines, placeholders and accessible names, and nothing else. What the agent wrote, what
 * the device reported — a name, a path, a branch, a model or permission id — and anything the
 * reader typed are never translated. English is the default whatever the system language is,
 * because the product is written in English and a phone set to another language is not a request
 * to change it.
 */
@Serializable
enum class InterfaceLanguage(val rawValue: String) {
    @SerialName("en") en("en"),
    @SerialName("zh-Hans") zhHans("zh-Hans");

    /**
     * Each name in its own script: a reader who cannot read the language the app is in has to be
     * able to recognise the one they want.
     */
    val title: String
        get() = when (this) {
            en -> "English"
            zhHans -> "中文"
        }

    /** The locale a screen resolves its own words and its dates through. */
    val locale: Locale get() = Locale.forLanguageTag(rawValue)

    /**
     * The table a string built outside a screen is looked up in. English has none, because every
     * key is its own English text.
     */
    internal val table: Map<String, String>
        get() = when (this) {
            en -> emptyMap()
            zhHans -> Localizable.zhHans
        }

    companion object {
        val allCases: List<InterfaceLanguage> get() = entries

        /** The language a stored word names, or null for a word this build does not know. */
        operator fun invoke(rawValue: String): InterfaceLanguage? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * The app's own words, in the language the reader chose rather than the one the system is set
 * to.
 *
 * A screen resolves its own strings, but a string built in a store, an error or a notification
 * has no screen to read, so it comes through here instead. One preference, one table, both ways.
 */
object L10n {
    /** The chosen language's table, readable from any thread: an error description is asked for wherever the error was caught. */
    @Volatile
    private var current: Map<String, String> = emptyMap()

    /** Point every later lookup at one language. `SettingsStore` calls this on launch and whenever the preference changes. */
    fun use(language: InterfaceLanguage) {
        current = language.table
    }

    /** The translation, or the key itself where there is none — which is the English text, because every key is its own English text. */
    fun string(key: String): String = current[key] ?: key

    /** A translation with values in it; the key carries the format specifiers. */
    fun string(key: String, vararg arguments: Any?): String = formatted(string(key), *arguments)
}
