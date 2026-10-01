package com.junbingao.remotecontrol.android.launch

import android.content.Intent
import com.junbingao.remotecontrol.android.navigation.SessionLink

/**
 * The iPhone's launch arguments, as `adb shell am start -n com.junbingao.remotecontrol/.android.MainActivity
 * --esa args --demo,--reset-state` hands them to the app (or `--es args "--demo --reset-state"`),
 * with the same names and meanings (`docs/IOS.md` § "Demo mode"). The ones the iPhone reads only
 * in a debug build — the scripted voice and the field's scroll probe — are honoured here only in
 * a debug build too, so a release can never substitute fake speech for the microphone.
 */
class LaunchOptions(val arguments: List<String>, private val debug: Boolean) {
    val demo: Boolean get() = has("--demo")
    val demoAccount: Boolean get() = has("--demo-account")
    val demoQueue: Boolean get() = has("--demo-queue")
    val demoUpdateRequired: Boolean get() = has("--demo-update-required")
    val demoPreferenceChange: Boolean get() = has("--demo-preference-change")
    val registrationOpen: Boolean get() = has("--registration-open")
    val resetState: Boolean get() = has("--reset-state")
    val uiTesting: Boolean get() = has("--ui-testing")
    val voicePreview: Boolean get() = debug && has("--voice-preview")
    val fieldScrollProbe: Boolean get() = debug && has("--field-scroll-probe")

    /** `--voice-level=0.5`: the scripted platform's input level, 0 to 1. */
    val voiceLevel: Double? get() = if (debug) value("--voice-level=")?.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) else null

    /** `--voice-transcript=long`: the long dictation instead of the short sentence. */
    val voiceTranscript: String? get() = if (debug) value("--voice-transcript=") else null

    /** `--language=zh-Hans`: the interface language for the run, whatever an account stored. */
    val language: String? get() = value("--language=")

    /** `--gallery`: open the primitives' gallery, the shell's debug entry, at once. */
    val gallery: Boolean get() = debug && has("--gallery")

    private fun has(flag: String) = flag in arguments

    private fun value(prefix: String): String? = arguments.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    companion object {
        /** The extra the arguments ride in. */
        const val EXTRA = "args"

        fun from(intent: Intent?, debug: Boolean): LaunchOptions {
            val list = intent?.getStringArrayExtra(EXTRA)?.toList()
                ?: intent?.getStringExtra(EXTRA)?.split(' ', ',')?.filter { it.isNotBlank() }
                ?: emptyList()
            return LaunchOptions(list.map { it.trim() }, debug)
        }

        /** The conversation a launch or a new intent asks for, from its data URI. */
        fun link(intent: Intent?): SessionLink? = SessionLink.parse(intent?.data)
    }
}
