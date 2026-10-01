package com.junbingao.remotecontrol.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.android.design.Appearance
import com.junbingao.remotecontrol.android.design.LocalTextInputRegions
import com.junbingao.remotecontrol.android.design.ProvideAppearance
import com.junbingao.remotecontrol.android.design.TextInputRegions
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.systemAppearance
import com.junbingao.remotecontrol.android.screens.lock.PrivacyShield
import com.junbingao.remotecontrol.android.security.SceneRule
import com.junbingao.remotecontrol.android.security.currentSceneState
import com.junbingao.remotecontrol.android.system.PresentationHost
import com.junbingao.remotecontrol.android.system.Presenter

/**
 * Everything every screen stands in: the system's appearance, the page colour behind the system
 * bars (the app draws edge to edge), the presentation stack drawn above the screens, and above
 * that [over] — the app lock — and the privacy shield whenever the app is not the one in front, as
 * the iPhone's `RootView` lays its windows. [appearance] is fixed by a picture and read from the
 * system otherwise.
 */
@Composable
fun AppRoot(
    presenter: Presenter,
    appearance: Appearance = systemAppearance(),
    shielded: Boolean = SceneRule.shields(currentSceneState()),
    over: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val regions = remember { TextInputRegions() }
    ProvideAppearance(appearance) {
        CompositionLocalProvider(LocalTextInputRegions provides regions) {
            Box(Modifier.fillMaxSize().background(Theme.canvas)) {
                PresentationHost(presenter, content)
                over()
                // What the recents screen keeps a picture of is the shield, never a transcript.
                PrivacyShield(visible = shielded)
            }
        }
    }
}
