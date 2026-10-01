package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.design.LocalPreviewStage

/**
 * The composer of one conversation: the web's `<Composer>`, placed where the chat page places it,
 * reading everything else it needs from the app model.
 *
 * `docs/DESIGN.md` § "The composer" — **A draft belongs to its session**. A composer is made for one
 * conversation's store and ends with it: opening another conversation draws a fresh one, which is
 * what ends a dictation with the session it was spoken for, its words staying in that session's
 * draft.
 */
@Composable
fun ComposerView(chat: ChatStore) {
    val model = LocalAppModel.current
    val stage = ComposerStage(LocalPreviewStage.current)
    key(chat) { ComposerSession(chat, model, stage) }
}

/** The composer of one store, holding the model that lives exactly as long. */
@Composable
private fun ComposerSession(chat: ChatStore, model: WinAppModel, stage: ComposerStage?) {
    val composer = remember { ComposerModel(chat, AppComposerHost(model, stage)) }
    ComposerBody(composer, stage)
    // A dictation ends when the composer stops taking one: the device went offline, or a terminal
    // took the session back.
    val enabled = composer.voiceEnabled
    LaunchedEffect(enabled) { composer.voice.enabled = enabled }
    DisposableEffect(composer) { onDispose { composer.shutDown() } }
    LaunchedEffect(composer) { stage?.run(on = composer) }
}
