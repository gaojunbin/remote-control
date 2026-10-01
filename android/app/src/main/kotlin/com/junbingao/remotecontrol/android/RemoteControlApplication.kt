package com.junbingao.remotecontrol.android

import android.app.Application
import com.junbingao.remotecontrol.android.push.NotificationChannels

/** The process: the one place services that outlive a screen are set up. */
class RemoteControlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A notification needs its channel to exist before the first one is posted.
        NotificationChannels.ensure(this)
    }
}
