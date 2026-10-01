package com.junbingao.remotecontrol.win.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.AccountRules
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.overlay.ConfirmDialog
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch

/**
 * `AccountGroup.tsx` — A24: the rows this account has. Users belongs to the admin role; a
 * password belongs to whoever has one, and the gateway refuses the change for the built-in `admin`
 * alone, whose password is its own `RC_PASSWORD`, so a second admin account gets both rows. Sign
 * out asks first (`docs/DESIGN.md` § "The Settings screen").
 */
@Composable
fun AccountGroup() {
    val model = LocalAppModel.current
    val stage = LocalPreviewStage.current
    val changingPassword = remember { mutableStateOf<ChangePasswordForm?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    val connection = model.connection
    SettingsGroup(S.settings.account) {
        if (connection.isAdmin) {
            SettingsActionRow(S.users.title, S.settings.usersNote) { model.router.go(Route.Users) }
        }
        if (connection.username.isNotEmpty() && connection.username != AccountRules.operatorUsername) {
            SettingsActionRow(S.settings.changePassword, S.settings.changePasswordNote) { changingPassword.value = ChangePasswordForm() }
        }
        SettingsActionRow(S.settings.signOut, S.settings.signOutNote, danger = true) { signingOut = true }
    }
    ChangePasswordModal(changingPassword.value, changingPassword.settingsModalOpen, { changingPassword.settingsModalOpen = false }, connection)
    ConfirmDialog(
        isPresented = signingOut,
        onDismiss = { signingOut = false },
        title = S.settings.signOutConfirm,
        body = S.settings.signOutNote,
        confirmLabel = S.settings.signOut,
        danger = true,
    ) {
        model.tasks.launch { model.signOut() }
    }
    LaunchedEffect(Unit) {
        when (stage) {
            "settings.sign-out" -> signingOut = true
            "settings.change-password" -> changingPassword.value = ChangePasswordForm()
            "settings.change-password-error" -> changingPassword.value = refuseStagedPassword(model)
        }
    }
}

/** A render of the refusal: a current password the gateway does not take. */
private fun refuseStagedPassword(model: WinAppModel): ChangePasswordForm {
    val form = ChangePasswordForm()
    form.current = "wrong"
    form.next = "another-password"
    model.tasks.launch { form.submit(on = model.connection) }
    return form
}
