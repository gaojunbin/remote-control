package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.junbingao.remotecontrol.android.security.BiometricLock
import com.junbingao.remotecontrol.android.security.SceneRule
import com.junbingao.remotecontrol.android.security.currentSceneState
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.GroupedListScope
import com.junbingao.remotecontrol.android.system.Toggle
import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/**
 * The Security group: the app lock, which is the phone's own and nobody else's.
 *
 * It is the last group because it is the one setting that guards everything above it, and a
 * terminal opened while it is on asks again (A38).
 */
fun GroupedListScope.SettingsSecurityGroup(
    /** Held so a change of interface language rebuilds the sentence where it stands (`SettingsLabel`). */
    language: InterfaceLanguage,
) {
    SettingsGroup("Security") { AppLockRow() }
}

/**
 * The lock unlocks with a biometric or, failing that, the screen lock (`docs/DESIGN.md` § "The
 * Android app"). A phone with neither has nothing to unlock with, so the switch cannot be turned on
 * there and the row says so in place of its sentence; one already on can still be turned off.
 * Asked again whenever the app comes back to the front, which is when a screen lock set in Android
 * Settings is first seen.
 */
@Composable
private fun AppLockRow() {
    val settings = LocalAppModel.current.settings
    val context = LocalContext.current
    val foreground = SceneRule.isForeground(currentSceneState())
    val unlockable = remember(foreground) { BiometricLock.canAuthenticate(context) }
    Toggle(
        L10n.string("Require Face ID"),
        isOn = settings.appLockEnabled,
        onChange = { settings.appLockEnabled = it },
        modifier = Modifier.settingsRowPadding(),
        enabled = unlockable || settings.appLockEnabled,
        tag = "settings.appLock",
    ) {
        SettingsLabel(
            "Require Face ID",
            if (unlockable) {
                L10n.string("Unlock with Face ID, Touch ID or your passcode when the app returns from the background.")
            } else {
                L10n.string("Not available on this device")
            },
        )
    }
}
