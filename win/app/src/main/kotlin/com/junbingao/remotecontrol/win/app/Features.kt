package com.junbingao.remotecontrol.win.app

import com.junbingao.remotecontrol.win.chat.ChatFeature
import com.junbingao.remotecontrol.win.chat.composer.ComposerFeature
import com.junbingao.remotecontrol.win.devices.ListsFeature
import com.junbingao.remotecontrol.win.notifications.SettingsFeature
import com.junbingao.remotecontrol.win.unseen.UnseenFeature

/**
 * The features' launch hooks, called once when the model is built. Each feature registers here
 * whatever it keeps for the life of the app — a sign-out handler, a transition handler, a frame
 * handler — without touching the model's own file.
 */
object Features {
    fun install(on: WinAppModel) {
        ChatFeature.install(on = on)
        ComposerFeature.install(on = on)
        ListsFeature.install(on = on)
        SettingsFeature.install(on = on)
        UnseenFeature.install(on = on)
    }
}
