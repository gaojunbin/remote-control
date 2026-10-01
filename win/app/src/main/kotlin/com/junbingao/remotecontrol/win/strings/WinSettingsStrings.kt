package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/**
 * The settings feature's own Windows words: the ones the web never needs because a browser the
 * gateway refuses is signed out before it can draw them.
 */
class WinSettingsStrings(
    /**
     * `docs/DESIGN.md` § "The Settings screen": the header dot's word while the gateway refuses
     * this app's connection — replaced by another app, or speaking a protocol this build does not.
     * Read aloud and shown on hover.
     */
    val refused: String,
) {
    companion object {
        val en = WinSettingsStrings(refused = "Refused")

        val zhHans = WinSettingsStrings(refused = "已拒绝")

        fun of(language: InterfaceLanguage): WinSettingsStrings = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
