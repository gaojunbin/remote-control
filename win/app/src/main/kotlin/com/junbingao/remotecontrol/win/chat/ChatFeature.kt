package com.junbingao.remotecontrol.win.chat

import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.chat.page.ChatMemory

/**
 * The chat feature's launch hook: a sign-out closes the conversation that is open while the
 * connection still names the account, and forgets the queued edits it kept
 * (`web/src/stores/signOut.ts`).
 */
object ChatFeature {
    fun install(on: WinAppModel) {
        on.onSignOut { ChatMemory.of(on).signOut(on) }
    }
}
