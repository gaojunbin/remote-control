package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.PlainButtonStyle
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.push.PushAuthorization
import com.junbingao.remotecontrol.android.screens.alerts.PushController
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.GroupedListScope
import com.junbingao.remotecontrol.android.system.Switch
import com.junbingao.remotecontrol.android.system.Toggle
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.ResumeText

/**
 * "While you're away": the two switches that decide what happens when nobody is looking at the
 * phone — being told, and the device carrying on (A35).
 *
 * `docs/DESIGN.md` § "The Settings screen": each switch says in its own row what it does, and a row
 * that cannot do anything says why there instead.
 */
fun GroupedListScope.SettingsAwayGroup(
    /** Held so a change of interface language rebuilds the sentences where they stand (`SettingsLabel`). */
    language: InterfaceLanguage,
) {
    SettingsGroup("While you're away") {
        val model = LocalAppModel.current
        val settings = model.settings
        val push = model.push
        if (push.authorization == PushAuthorization.denied) {
            // The one settings row on the phone whose tap is not the control's: Android Settings is
            // the only place this can be turned back on, so the row goes there and the switch stays
            // inert.
            Button(onClick = { push.openSystemSettings() }, Modifier.testTag("settings.notifications.blocked"), style = PlainButtonStyle) {
                Row(
                    Modifier.settingsRowPadding(),
                    horizontalArrangement = Arrangement.spacedBy(Theme.Space.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsLabel("Notify me", L10n.string("Blocked in iOS Settings. Tap to open them."), Modifier.weight(1f))
                    InertSwitch()
                }
            }
        } else {
            Toggle(
                L10n.string("Notify me"),
                isOn = settings.notificationsEnabled,
                onChange = { settings.notificationsEnabled = it },
                modifier = Modifier.settingsRowPadding(),
                // The app's own notifications need no gateway, so the switch works in the demo too
                // wherever the system can raise one.
                enabled = push.isSupported,
                tag = "settings.notifications",
            ) {
                SettingsLabel("Notify me", notifySentence(push))
            }
        }
        ResumeAfterLimitRow(language)
    }
}

/**
 * The switch drawn off and never touched — the iPhone's `allowsHitTesting(false)`: a tap where it
 * stands is the row's, which goes to Android Settings.
 */
@Composable
private fun InertSwitch() {
    Box {
        Switch(isOn = false, onChange = {}, enabled = false)
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}

/**
 * What the switch does, or what the system did to it. The iPhone also says here when the gateway
 * refused the device's registration; Android registers nothing yet.
 */
private fun notifySentence(push: PushController): String =
    if (!push.isSupported) push.statusText else L10n.string("Which device and session needs you, and nothing else.")

/**
 * Amendment A35 — the account's switch, not this phone's.
 *
 * The gateway keeps it, so the browser, the phone and every device read the same value and turning
 * it off anywhere cancels every pending resume everywhere. A gateway that predates the switch
 * offers nothing and the row says so; a refused write puts the switch back and says why.
 */
@Composable
private fun ResumeAfterLimitRow(language: InterfaceLanguage) {
    val model = LocalAppModel.current
    val store = model.preferences
    val offered = store.isOffered
    val sentence = store.errorMessage?.let(L10n::platform) ?: ResumeText.settingsSentence(offered = offered)
    // The write is a request, so the switch starts one rather than assigning; the store applies it
    // before the round trip, and puts it back with the gateway's reason if it is refused.
    Toggle(
        L10n.string("Resume after the limit resets"),
        isOn = store.resumeAfterLimit,
        onChange = { next -> model.perform { preferences.setResumeAfterLimit(next) } },
        modifier = Modifier.settingsRowPadding(),
        enabled = offered,
        tag = "settings.resumeAfterLimit",
    ) {
        SettingsLabel("Resume after the limit resets", sentence)
    }
}
