package com.junbingao.remotecontrol.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Amendment A44: the gateway detects the dictation language, so a language is offered only where
 * the phone's own recogniser listens, and there it is the recogniser's list, Chinese first, with
 * no Automatic.
 */
class DictationLanguageTests {
    /** The phone listens wherever the gateway does not transcribe. */
    @Test
    fun backendInEffect() {
        val table = listOf(
            Triple(VoiceBackend.onDevice, true, VoiceBackend.onDevice),
            Triple(VoiceBackend.onDevice, false, VoiceBackend.onDevice),
            Triple(VoiceBackend.gateway, true, VoiceBackend.gateway),
            // A gateway with no transcription service falls back to the phone, and the phone then
            // needs a language as much as if it was chosen.
            Triple(VoiceBackend.gateway, false, VoiceBackend.onDevice),
        )
        for ((chosen, transcribes, expected) in table) {
            assertEquals(expected, VoiceBackend.inEffect(chosen = chosen, gatewayTranscribes = transcribes),
                         "$chosen on a gateway that transcribes: $transcribes")
        }
    }

    /** The recogniser's languages, Chinese first, and no Automatic. */
    @Test
    fun theList() {
        assertEquals(listOf("zh", "en", "ja", "de", "fr", "es"), DictationLanguage.codes)
        assertFalse("auto" in DictationLanguage.codes)
        assertEquals("zh", DictationLanguage.standard)
    }

    /** Unset, auto and anything unknown are heard as Chinese. */
    @Test
    fun effectiveLanguage() {
        assertEquals("zh", DictationLanguage.effective(null))
        assertEquals("zh", DictationLanguage.effective("auto"))
        assertEquals("zh", DictationLanguage.effective("ko"))
        assertEquals("en", DictationLanguage.effective("en"))
        assertEquals("es", DictationLanguage.effective("es"))
    }

    /** Each language is handed to the recogniser as a locale it supports. */
    @Test
    fun locales() {
        assertEquals(listOf("zh-CN", "en-US", "ja-JP", "de-DE", "fr-FR", "es-ES"),
                     DictationLanguage.codes.map(DictationLanguage::localeIdentifier))
        assertEquals("zh-CN", DictationLanguage.localeIdentifier("auto"))
    }

    /** A language is named in the app's own language. */
    @Test
    fun names() {
        assertEquals("Chinese", DictationLanguage.name(of = "zh", language = InterfaceLanguage.en))
        assertEquals("English", DictationLanguage.name(of = "en", language = InterfaceLanguage.en))
        assertEquals("中文", DictationLanguage.name(of = "zh", language = InterfaceLanguage.zhHans))
    }

    /** A new install listens for Chinese, and a stored auto is read as it without a write. */
    @Test
    fun settingsDefault() {
        val defaults = MemoryUserDefaults()
        val settings = SettingsStore(defaults = defaults)
        assertEquals("zh", settings.voiceLanguage)
        assertEquals("zh-CN", settings.speechLocaleIdentifier)

        defaults.set("auto", forKey = "preference.voiceLanguage")
        val legacy = SettingsStore(defaults = defaults)
        assertEquals("auto", legacy.voiceLanguage, "the stored value is left as it was")
        assertEquals("zh", legacy.dictationLanguage)
        assertEquals("zh-CN", legacy.speechLocaleIdentifier)
        assertEquals("auto", defaults.string(forKey = "preference.voiceLanguage"), "and nothing rewrote it")
    }
}
