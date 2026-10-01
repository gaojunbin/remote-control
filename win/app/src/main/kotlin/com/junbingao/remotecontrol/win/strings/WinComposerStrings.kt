package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/**
 * The composer's Windows-only words. Where the web names the browser, the Windows app names the
 * computer it runs on (`docs/DESIGN.md` § "The Windows app" → **Windows' own words**).
 */
class WinComposerStrings(
    /** `voice.unsupported` on Windows: no input device to capture from. */
    val voiceUnsupported: String,
) {
    companion object {
        val en = WinComposerStrings(
            voiceUnsupported = "This PC cannot capture audio.",
        )

        val zhHans = WinComposerStrings(
            voiceUnsupported = "这台电脑无法采集音频。",
        )

        fun of(language: InterfaceLanguage): WinComposerStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
