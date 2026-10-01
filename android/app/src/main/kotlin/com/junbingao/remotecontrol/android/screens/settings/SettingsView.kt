package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.android.design.FieldLabel
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.navigation.LocalNavigator
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.screens.users.UsersRoute
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.InsetGroupedList

/**
 * Placeholder for `android-settings`, which ports the iPhone's `SettingsView` and its groups
 * here: the account's two ways out until then — the accounts screen for an admin, and Sign out.
 */
@Composable
fun SettingsView() {
    val model = LocalAppModel.current
    val navigator = LocalNavigator.current
    NavigationScreen(L10n.string("Settings")) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), contentPadding = insets.padding()) {
            section(key = "account", header = { FieldLabel("Account") }) {
                if (model.connection.isAdmin) {
                    row(key = "users", onClick = { navigator?.push(UsersRoute) }, tag = "settings.users") {
                        Text(L10n.string("Users"), style = Theme.Text.label, color = Theme.ink)
                    }
                }
                row(key = "signOut", onClick = { model.perform { signOut() } }, tag = "settings.signOut") {
                    Text(L10n.string("Sign out"), style = Theme.Text.label, color = Theme.danger)
                }
            }
        }
    }
}
