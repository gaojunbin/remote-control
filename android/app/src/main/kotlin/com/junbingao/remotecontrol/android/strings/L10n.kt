package com.junbingao.remotecontrol.android.strings

import androidx.compose.runtime.mutableStateOf

/**
 * The app's own words, in the language the reader chose rather than the one the phone is set to.
 *
 * The iPhone's rule (`docs/IOS.md` § "Language"): English whatever the phone is set to until
 * Chinese is picked, one catalogue — `ios/App/Localizable.xcstrings`, read here through the
 * generated [Catalog] — whose keys are the English text, and a change that reaches every open
 * screen at once because it only moves where words are read from. The language is held as
 * snapshot state, so a composable that looks a word up recomposes when it changes and nothing
 * is restarted; code outside a composable reads the language that is current when it runs, which
 * is why a word is computed where it is shown and never kept.
 *
 * The table is the whole iPhone catalogue, a superset of the core's own, so screen code asks this
 * object for every word it draws, whichever layer the word came from on the iPhone.
 */
object L10n {
    /** `InterfaceLanguage.en`'s raw value. */
    const val english = "en"

    /** `InterfaceLanguage.zhHans`'s raw value. */
    const val chinese = "zh-Hans"

    private val chosen = mutableStateOf(english)
    private var source: () -> String = { chosen.value }

    /** The tag every lookup reads now: [english] or [chinese]. */
    val language: String get() = source()

    /** Point every later lookup at one language, as `L10n.use` does on the iPhone. */
    fun use(language: String) {
        chosen.value = language
        source = { chosen.value }
    }

    /**
     * Read the language from wherever the app keeps it — the account's settings, which are
     * snapshot state themselves — instead of holding a copy that could disagree with them.
     */
    fun follow(language: () -> String) {
        source = language
    }

    /** The translation, or the key itself where there is none, which is its own English text. */
    fun string(key: String): String = table(language)[key] ?: key

    /** A translation with values in it; the key carries the iPhone's format specifiers. */
    fun string(key: String, vararg arguments: Any?): String =
        IosFormat.format(string(key), arguments.toList())

    /** Whether the catalogue has this key at all, for the checks that hold screens to it. */
    fun knows(key: String): Boolean = Catalog.en.containsKey(key)

    internal fun table(language: String): Map<String, String> =
        if (language == chinese) Catalog.zhHans else Catalog.en
}
