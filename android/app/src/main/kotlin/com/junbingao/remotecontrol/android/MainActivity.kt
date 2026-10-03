package com.junbingao.remotecontrol.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import com.junbingao.remotecontrol.BuildConfig
import com.junbingao.remotecontrol.android.design.FieldScrollProbe
import com.junbingao.remotecontrol.android.gallery.GalleryRoot
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.screens.lock.AppLockWindow
import com.junbingao.remotecontrol.android.security.RecentsShield
import com.junbingao.remotecontrol.android.shell.AppRoot
import com.junbingao.remotecontrol.android.shell.RootView
import com.junbingao.remotecontrol.android.system.Presenter

/**
 * The one activity, as the iPhone app has one scene: edge to edge with the page colour behind the
 * system bars, and a link into a conversation — at launch or while running, from a notification or
 * `remotecontrol://` — handed to the model, which opens it in place once there is an account to
 * open it in; the badge's notification opens Sessions (A47). It handles its own configuration
 * changes; the model lives in the process.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        RecentsShield.apply(this)
        val options = LaunchOptions.from(intent, BuildConfig.DEBUG)
        FieldScrollProbe.enable(options.fieldScrollProbe)
        val model = (application as RemoteControlApplication).model(options)
        LaunchOptions.link(intent)?.let(model::handle)
        if (LaunchOptions.opensSessions(intent)) model.showSessions()
        setContent {
            val presenter = remember { Presenter() }
            if (options.gallery) {
                AppRoot(presenter) { GalleryRoot() }
            } else {
                AppRoot(presenter, over = { AppLockWindow(model.isLocked) { model.isLocked = false } }) { RootView(model) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val model = (application as RemoteControlApplication).model(LaunchOptions.from(intent, BuildConfig.DEBUG))
        LaunchOptions.link(intent)?.let(model::handle)
        if (LaunchOptions.opensSessions(intent)) model.showSessions()
    }
}
