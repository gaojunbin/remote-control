package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.win.app.WinAppModel

/**
 * The composer feature's launch hook. The files of every session's draft live for the app's life,
 * and nothing of an account's stays behind it: signing out empties them, as
 * `web/src/stores/signOut.ts` resets the drafts store.
 */
object ComposerFeature {
    fun install(on: WinAppModel) {
        val drafts = ComposerDrafts.of(on)
        on.onSignOut { drafts.reset() }
    }
}
