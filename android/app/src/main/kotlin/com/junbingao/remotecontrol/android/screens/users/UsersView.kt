package com.junbingao.remotecontrol.android.screens.users

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.FieldLabel
import com.junbingao.remotecontrol.android.design.FontScope
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.SettingsFooter
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.settings
import com.junbingao.remotecontrol.android.design.settingsRowLayout
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.Alert
import com.junbingao.remotecontrol.android.system.AlertAction
import com.junbingao.remotecontrol.android.system.AlertTextField
import com.junbingao.remotecontrol.android.system.BottomBar
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.android.system.RowStyle
import com.junbingao.remotecontrol.android.system.Sheet
import com.junbingao.remotecontrol.android.system.Toggle
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.state.UsersStore

/** The Settings stack's route to the accounts screen, which the iPhone pushes with `navigationDestination(isPresented:)`. */
data object UsersRoute

/**
 * The admin's accounts screen (protocol 3.9, A24), and nobody else's.
 *
 * `docs/DESIGN.md` § "Accounts": one switch at the top, one row per account, and three actions per
 * row — Reset password, Disable or Enable, Delete — reachable from a trailing swipe and from the
 * context menu, exactly as the web reaches them from its row menu. The `admin` row has none of
 * them.
 */
@Composable
fun UsersView() {
    val model = LocalAppModel.current
    var store by remember { mutableStateOf<UsersStore?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    val actions = remember { UserActions() }
    val list = rememberLazyListState()

    NavigationScreen(
        L10n.string("Users"),
        listState = list,
        bottomBar = {
            BottomBar {
                Button(
                    onClick = { isAdding = true },
                    Modifier.testTag("users.add"),
                    enabled = store != null,
                    style = PrimaryButtonStyle(),
                ) {
                    Label(L10n.string("Add user"), Sf.plus)
                }
            }
        },
    ) { insets ->
        val danger = Theme.danger
        val attention = Theme.attention
        val quiet = Theme.inkSecondary
        val current = store
        val message = actions.actionError ?: current?.errorMessage?.let(L10n::platform)
        InsetGroupedList(Modifier.fillMaxSize(), list, insets.padding()) {
            section(
                key = "registration",
                footer = { SettingsFooter(L10n.string("Anyone with the gateway address can create an account")) },
            ) {
                row(key = "registration.switch", style = RowStyle.settings) {
                    FontScope(Theme.Text.label) {
                        Toggle(
                            L10n.string("Registration"),
                            isOn = current?.registrationOpen ?: false,
                            onChange = { open -> current?.let { actions.setRegistration(model, it, open) } },
                            modifier = Modifier.settingsRowLayout(),
                            enabled = current != null,
                            tag = "users.registration",
                        )
                    }
                }
            }
            section(key = "accounts", header = { FieldLabel("Accounts") }) {
                for (record in current?.users.orEmpty()) {
                    row(
                        key = record.username,
                        style = sessionRowLayout,
                        // SwiftUI lays a trailing swipe out from the edge inwards, so the first
                        // listed sits nearest the edge and the row reads Reset · Disable · Delete.
                        swipeActions = if (record.isOperator || current == null) emptyList() else {
                            actions.swipe(model, current, record, danger = danger, attention = attention, quiet = quiet)
                        },
                        contextMenu = if (record.isOperator || current == null) emptyList() else actions.menu(model, current, record),
                        tag = "user.${record.username}",
                    ) { UserRow(record) }
                }
            }
            if (message != null) {
                section(key = "error") {
                    row(key = "error.text", style = RowStyle(background = Color.Transparent, separator = false)) {
                        Text(message, Modifier.testTag("users.error"), style = SystemFont.footnote, color = Theme.danger)
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (store == null) store = model.connection.usersStore()
        store?.load()
    }

    store?.let { current ->
        Sheet(isAdding, onDismiss = { isAdding = false }) { AddUserSheet(current) { isAdding = false } }
    }
    ResetPasswordAlert(actions, store)
    DeleteAccountAlert(actions, store)
}

/** The password alert, which re-opens with the gateway's sentence when the reset is refused. */
@Composable
private fun ResetPasswordAlert(actions: UserActions, store: UsersStore?) {
    val model = LocalAppModel.current
    val record = actions.resetting
    Alert(
        record != null,
        L10n.string("Reset password"),
        onDismiss = { actions.resetting = null },
        message = actions.resetError ?: L10n.string(
            "%@ signs in with this password from now on. Other sign-ins stay valid.",
            record?.username.orEmpty(),
        ),
        actions = listOf(
            AlertAction(L10n.string("Cancel"), ActionRole.cancel) { actions.resetting = null },
            AlertAction(L10n.string("Set password"), tag = "users.reset.confirm") { store?.let { actions.resetPassword(model, it) } },
        ),
        textField = AlertTextField(
            L10n.string("New password"),
            actions.newPassword,
            { actions.newPassword = it },
            secure = true,
            tag = "users.reset.password",
        ),
    )
}

/** Deleting asks first, and names what goes with the account. */
@Composable
private fun DeleteAccountAlert(actions: UserActions, store: UsersStore?) {
    val model = LocalAppModel.current
    val record = actions.deleting
    Alert(
        record != null,
        L10n.string("Delete account"),
        onDismiss = { actions.deleting = null },
        message = record?.let(::deleteMessage).orEmpty(),
        actions = listOf(
            AlertAction(L10n.string("Cancel"), ActionRole.cancel) { actions.deleting = null },
            AlertAction(L10n.string("Delete account"), ActionRole.destructive, tag = "users.delete.confirm") {
                store?.let { actions.delete(model, it) }
            },
        ),
    )
}

private fun deleteMessage(record: UserRecord): String = when (record.devices) {
    0 -> L10n.string("Delete %@? The account, its sign-ins and its push registrations go with it.", record.username)
    1 -> L10n.string(
        "Delete %@ and its %lld device? The token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts.",
        record.username, record.devices,
    )
    else -> L10n.string(
        "Delete %@ and its %lld devices? Their tokens stop working and their sessions leave this gateway. The machines keep their agents and transcripts.",
        record.username, record.devices,
    )
}

/** `sessionRowLayout()`, the session list's row, which the accounts screen shares: 14 above and below, 16 at the sides, no separator. */
private val sessionRowLayout = RowStyle(insets = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp), separator = false)
