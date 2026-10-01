package com.junbingao.remotecontrol.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.junbingao.remotecontrol.BuildConfig
import com.junbingao.remotecontrol.android.design.FieldScrollProbe
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.security.PrivacyShield
import com.junbingao.remotecontrol.android.shell.AppRoot
import com.junbingao.remotecontrol.android.shell.MainShell
import com.junbingao.remotecontrol.android.shell.ShellState
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * The one activity, as the iPhone app has one scene: edge to edge with the page colour behind
 * the system bars, the launch arguments read once, and a link into a conversation — at launch or
 * while running, from a notification or `remotecontrol://` — opened in place. It handles its own
 * configuration changes, so the shell and its stacks live as long as it does.
 */
class MainActivity : FragmentActivity() {
    private val shell = ShellState()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PrivacyShield.apply(this)
        val options = LaunchOptions.from(intent, BuildConfig.DEBUG)
        options.language?.let(L10n::use)
        FieldScrollProbe.enable(options.fieldScrollProbe)
        if (options.gallery) shell.openGallery()
        LaunchOptions.link(intent)?.let(shell::open)
        setContent {
            AppRoot(shell.presenter) { MainShell(shell) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LaunchOptions.link(intent)?.let(shell::open)
    }
}
