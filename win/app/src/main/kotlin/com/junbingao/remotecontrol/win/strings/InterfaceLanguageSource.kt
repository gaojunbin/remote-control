package com.junbingao.remotecontrol.win.strings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.win.standin.InterfaceLanguage

/**
 * The interface language `S` reads, kept in Compose snapshot state so that every composable which
 * read a string redraws when it changes. The model moves it — to the signed-in account's choice
 * (A41), and to English on the login page — and nothing else writes it. Readable from any thread:
 * a notification or an error is worded wherever it is built.
 */
object InterfaceLanguageSource {
    var current: InterfaceLanguage by mutableStateOf(InterfaceLanguage.en)
}
