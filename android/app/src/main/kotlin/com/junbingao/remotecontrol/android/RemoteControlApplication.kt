package com.junbingao.remotecontrol.android

import android.app.Application
import com.junbingao.remotecontrol.BuildConfig
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.push.NotificationChannels
import com.junbingao.remotecontrol.android.shell.AppEnvironment
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.core.state.AppBuild
import kotlinx.coroutines.MainScope

/**
 * The process: the one place services that outlive a screen are set up, and the one app model,
 * built once with the arguments the first launch carried — as the iPhone builds its in the `App`
 * and never in a view, so a recreated activity finds the account, the stacks and the open
 * conversation where it left them.
 */
class RemoteControlApplication : Application() {
    /** Where the model's work runs: the main thread, for the life of the process. */
    val tasks = MainScope()

    private var built: AppModel? = null

    override fun onCreate() {
        super.onCreate()
        // The core reads this build's version as RCCore reads the bundle's.
        AppBuild.version = BuildConfig.VERSION_NAME
        // A notification needs its channel to exist before the first one is posted.
        NotificationChannels.ensure(this)
    }

    /** The model, built on first use from the launch that asked for it. */
    fun model(options: LaunchOptions): AppModel = built ?: AppEnvironment.model(this, options, tasks).also { built = it }
}
