package com.junbingao.remotecontrol.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.pageBackground
import com.junbingao.remotecontrol.android.screens.devices.DevicesDestination
import com.junbingao.remotecontrol.android.screens.sessions.SessionsDestination
import com.junbingao.remotecontrol.android.screens.settings.SettingsDestination
import com.junbingao.remotecontrol.android.security.SceneRule
import com.junbingao.remotecontrol.android.security.currentSceneState
import com.junbingao.remotecontrol.android.system.safeArea
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * The app shell: sign in, or the three tabs plus the conversation stack — the iPhone's `RootView`.
 * What covers it all (the lock, the privacy shield) is drawn above the presentations by
 * [AppRoot], so an open sheet cannot show a transcript over them.
 */
@Composable
fun RootView(model: AppModel) {
    CompositionLocalProvider(LocalAppModel provides model) {
        val requirement = model.connection.updateRequired
        Box(Modifier.fillMaxSize().pageBackground()) {
            // Amendment A31: below the gateway's minimum nothing here is reachable — not by a
            // touch, which the screen over it takes, and not by a screen reader.
            Box(Modifier.fillMaxSize().hiddenWhen(requirement != null)) {
                when {
                    // Locked, the shell is still drawn under the lock but no longer read out.
                    model.isSignedIn -> Box(Modifier.fillMaxSize().hiddenWhen(model.isLocked)) { MainShell(model) }
                    // The launch background and nothing else while the Keystore is being read.
                    // The form is an answer, not a waiting room.
                    model.isResuming -> Unit
                    else -> LoginView(model)
                }
            }
            // Amendment A31: over everything, including the sign-in form, because
            // `GET /api/health` answers before anyone has a credential. Nothing underneath is
            // reachable until the app is updated or signed out.
            requirement?.let { UpdateRequiredView(it) { model.perform { signOut() } } }
            model.toast?.let { message -> Toast(message) { model.toast = null } }
        }
    }
    LaunchedEffect(model) { model.restoreOrPrompt() }
    // Only the background is leaving the app. The biometric prompt, a system dialog and the other
    // half of a split screen leave it visible and nothing more, and the app lock's own footer
    // promises it engages when the app returns from the background.
    val scene = currentSceneState()
    LaunchedEffect(scene) {
        model.setSceneActive(SceneRule.isForeground(scene))
        if (!SceneRule.isBackground(scene)) return@LaunchedEffect
        model.lockIfNeeded()
        model.persistForBackground()
    }
}

/** Three destinations. Opening a conversation hides the tab bar and leaves the screen to the transcript and the composer. */
@Composable
private fun MainShell(model: AppModel) {
    // The landing rule reads the first device list, which arrives with the hello; the same
    // snapshot is the whole list of what this account has, so it is also where drafts for
    // sessions that no longer exist are forgotten.
    LaunchedEffect(model.connection.hasSnapshot) {
        model.decideLandingTab()
        model.adoptSnapshot()
    }
    TabShell(model.navigation) { tab, route ->
        when (tab) {
            AppModel.Tab.devices -> DevicesDestination(route)
            AppModel.Tab.sessions -> SessionsDestination(route)
            AppModel.Tab.settings -> SettingsDestination(route)
        }
    }
}

/** The model's one-line message, at the foot of the screen for three seconds. */
@Composable
private fun Toast(message: String, onGone: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom = 80.dp + safeArea().bottom), contentAlignment = Alignment.BottomCenter) {
        Text(
            message,
            Modifier
                .background(Theme.accent, CapsuleShape)
                .padding(horizontal = Theme.Space.medium, vertical = Theme.Space.small)
                .testTag("app.toast"),
            style = SystemFont.footnote,
            color = Theme.onAccent,
        )
    }
    LaunchedEffect(message) {
        delay(3.seconds)
        onGone()
    }
}

/** SwiftUI's `.accessibilityHidden(_:)`: while [hidden], nothing in here is in the semantics tree. */
private fun Modifier.hiddenWhen(hidden: Boolean): Modifier = if (hidden) clearAndSetSemantics {} else this
