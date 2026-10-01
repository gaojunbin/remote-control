package com.junbingao.remotecontrol.win.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Switch
import com.junbingao.remotecontrol.win.notifications.NotificationPermission
import com.junbingao.remotecontrol.win.notifications.SettingsFeature
import com.junbingao.remotecontrol.win.notifications.TurnNotifier
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * `WhileAwayGroup.tsx`: what happens while nobody is looking — the notification that says a
 * session needs you, and A35's resume after a usage limit resets. Both are switches, and both say
 * in their own row why they cannot be flipped (`docs/DESIGN.md` § "The Settings screen").
 */
@Composable
fun WhileAwayGroup() {
    SettingsGroup(S.settings.whileAway) {
        NotifyRow()
        ResumeRow()
    }
}

/**
 * Notify me, which on Windows lets the running app post the notifications the gateway pushes for
 * (`docs/DESIGN.md` § "The Windows app"). The switch is this PC's, not the account's, and the one
 * way this app is refused is Windows' own setting — so that, in Windows' words, is the only
 * sentence that replaces the row's.
 */
@Composable
fun NotifyRow() {
    val model = LocalAppModel.current
    val stage = LocalPreviewStage.current
    val notifier = remember(model) { SettingsFeature.state(of = model).notifier }
    // A render shows the refusal Windows would give.
    val blocked = stage == "settings.notify-blocked" || notifier.permission == NotificationPermission.denied
    val isOn = !blocked && notifier.isOn
    val inert = blocked || notifier.isAsking
    SettingsRow(
        title = S.settings.notify,
        sentence = if (blocked) S.win.pushBlocked else S.settings.notifyNote,
        target = true,
        reach = if (inert) null else ({ turn(model, notifier, on = !isOn) }),
    ) {
        Disabled(inert) {
            Switch(isOn = isOn, label = S.settings.notify) { turn(model, notifier, on = it) }
        }
    }
    LaunchedEffect(notifier) {
        notifier.refresh()
        // Windows Settings may have changed while the window was behind another.
        snapshotFlow { model.isWindowActive }.drop(1).filter { it }.collect { notifier.refresh() }
    }
}

private fun turn(model: WinAppModel, notifier: TurnNotifier, on: Boolean) {
    model.tasks.launch { notifier.turn(on = on) }
}

/** A35: the account's own switch, which the gateway holds. No preferences at all is a gateway that predates them, not a switch that is off. */
@Composable
fun ResumeRow() {
    val model = LocalAppModel.current
    val stage = LocalPreviewStage.current
    var error by remember { mutableStateOf<String?>(null) }
    val store = model.preferences
    val offered = store.isOffered && stage != "settings.old-gateway"
    val sentence = if (offered) error ?: S.settings.resumeAfterLimitNote else S.settings.resumeUnavailable
    fun set(next: Boolean) {
        error = null
        model.tasks.launch {
            store.setResumeAfterLimit(next)
            if (store.errorMessage != null) {
                error = S.errors.setFailed
                store.clearError()
            }
        }
    }
    SettingsRow(
        title = S.settings.resumeAfterLimit,
        sentence = sentence,
        target = true,
        reach = if (offered) ({ set(!store.resumeAfterLimit) }) else null,
    ) {
        Disabled(!offered) {
            Switch(isOn = offered && store.resumeAfterLimit, label = S.settings.resumeAfterLimit) { set(it) }
        }
    }
}
