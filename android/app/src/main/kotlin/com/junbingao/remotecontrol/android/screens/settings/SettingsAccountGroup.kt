package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.PlainButtonStyle
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.GroupedListScope
import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/**
 * The Account group: what this account has, and how to leave it.
 *
 * `docs/DESIGN.md` § "Account, in Settings": the admin is offered the accounts screen and no
 * password row — the operator's password is the gateway's own `RC_PASSWORD` — and every other
 * account the other way round. Who is signed in is the header above, not a row here.
 */
fun GroupedListScope.SettingsAccountGroup(
    /** Held so a change of interface language rebuilds the sentences where they stand (`SettingsLabel`). */
    language: InterfaceLanguage,
    users: () -> Unit,
    changePassword: () -> Unit,
    signOut: () -> Unit,
) {
    SettingsGroup("Account") {
        val model = LocalAppModel.current
        if (model.connection.isAdmin) {
            Button(onClick = users, Modifier.testTag("settings.users"), style = PlainButtonStyle) {
                SettingsActionLabel(
                    "Users",
                    sentence = L10n.string("Accounts on this gateway, and whether anyone can create one."),
                    leadsOn = true,
                )
            }
        } else {
            Button(onClick = changePassword, Modifier.testTag("settings.changePassword"), style = PlainButtonStyle) {
                SettingsActionLabel("Change password", sentence = L10n.string("The current password and the new one."), leadsOn = true)
            }
        }
        Button(onClick = signOut, Modifier.testTag("settings.signOut"), style = PlainButtonStyle) {
            SettingsActionLabel("Sign out", sentence = signOutSentence, tint = Theme.danger)
        }
    }
}

/**
 * The sign-out row's sentence, which is also the confirmation's message: what the dialog explains
 * is what the row promised.
 */
val signOutSentence: String
    get() = L10n.string("Cached sessions and drafts leave this device. Nothing changes on your machines.")
