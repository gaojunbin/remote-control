package com.junbingao.remotecontrol.win.standin

/**
 * The language the app writes its own words in: RCCore's `InterfaceLanguage`, which the Kotlin
 * core ports as `com.junbingao.remotecontrol.core.state.InterfaceLanguage`.
 *
 * The foundation is built before the core is wired in, so this stands in for it with the same
 * cases, raw values and titles. Stage 2 deletes this package and imports the core's types.
 *
 * English is the default whatever the system language is, because the product is written in
 * English and a machine set to another language is not a request to change it.
 */
enum class InterfaceLanguage(val rawValue: String) {
    en("en"),
    zhHans("zh-Hans");

    /** Each name in its own script, so a reader who cannot read the current one finds theirs. */
    val title: String
        get() = when (this) {
            en -> "English"
            zhHans -> "中文"
        }

    companion object {
        fun of(rawValue: String): InterfaceLanguage? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
