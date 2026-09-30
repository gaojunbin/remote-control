package com.junbingao.remotecontrol.core.protocol

/**
 * Amendment A40: one of the four session settings an attachment may or may not carry.
 * `AgentInfo.shared_settings_keys` names the ones `session.set` really changes on a `shared`
 * session; the rest stay what the terminal set and an app draws them as values (A17).
 *
 * The raw value is the word the wire uses, so the list is compared against what a device sends
 * without a translation table in between.
 */
enum class SharedSetting(val rawValue: String) {
    model("model"),
    permissionMode("permission_mode"),
    effort("effort"),
    speed("speed");

    companion object {
        val allCases: List<SharedSetting> get() = entries

        operator fun invoke(rawValue: String): SharedSetting? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
