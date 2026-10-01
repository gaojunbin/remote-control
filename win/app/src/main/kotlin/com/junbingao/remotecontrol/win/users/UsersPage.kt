package com.junbingao.remotecontrol.win.users

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.FormError
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.surface
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.notifications.SettingsFeature
import com.junbingao.remotecontrol.win.settings.settingsModalOpen
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch

/**
 * `/users` (A24), `web/src/features/users/UsersPage.tsx`: the admin's accounts screen — the
 * registration switch, one row per account, and Add user as the page's primary button. The
 * gateway answers `403` to everyone else, so a member who reaches the address is sent to Sessions
 * before anything is asked for, rather than shown an empty page that failed.
 */
@Composable
fun UsersPage() {
    val model = LocalAppModel.current
    val stage = LocalPreviewStage.current
    val users = remember(model) { SettingsFeature.state(of = model).users }
    val connection = model.connection
    val adding = remember { mutableStateOf<AddUserForm?>(null) }
    val resetting = remember { mutableStateOf<ResetPasswordForm?>(null) }
    val deleting = remember { mutableStateOf<DeleteUserForm?>(null) }
    VStack(spacing = 0.dp, alignment = Alignment.Start) {
        PageHead(S.users.title) {
            Btn(S.users.add, icon = LucideIcon.plus, variant = ButtonVariant.primary) { adding.value = AddUserForm() }
        }
        VStack(Modifier.widthIn(max = 620.dp).fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
            RegistrationCard(isOpen = users.registrationOpen) { open ->
                model.tasks.launch { users.openRegistration(open, on = connection) }
            }
            users.error?.let { FormError(it, Modifier.padding(top = Space.sp4)) }
            if (users.loaded) {
                Accounts(
                    users, stage, Modifier.padding(top = Space.sp4),
                    onResetPassword = { resetting.value = ResetPasswordForm(username = it.username) },
                    onToggleState = { user -> model.tasks.launch { users.toggleState(of = user, on = model.connection) } },
                    onDelete = { deleting.value = DeleteUserForm(user = it) },
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!connection.isAdmin) {
            model.router.replace(Route.Sessions)
            return@LaunchedEffect
        }
        users.load(on = connection)
        openStagedDialog(users, stage, adding, resetting, deleting)
    }
    AddUserModal(adding.value, adding.settingsModalOpen, { adding.settingsModalOpen = false }, users, connection)
    ResetPasswordModal(resetting.value, resetting.settingsModalOpen, { resetting.settingsModalOpen = false }, users, connection)
    DeleteUserModal(deleting.value, deleting.settingsModalOpen, { deleting.settingsModalOpen = false }, users, connection)
}

@Composable
private fun Accounts(
    users: UsersModel,
    stage: String?,
    modifier: Modifier,
    onResetPassword: (UserRecord) -> Unit,
    onToggleState: (UserRecord) -> Unit,
    onDelete: (UserRecord) -> Unit,
) {
    val firstActionable = users.users.firstOrNull { !it.isOperator }?.id
    VStack(modifier.fillMaxWidth().surface(), spacing = 0.dp) {
        for (user in users.users) {
            key(user.id) {
                val actionable = !user.isOperator
                UserRow(
                    user = user,
                    actionable = actionable,
                    menuOpen = stage == "users.menu" && actionable && user.id == firstActionable,
                    onResetPassword = { onResetPassword(user) },
                    onToggleState = { onToggleState(user) },
                    onDelete = { onDelete(user) },
                )
            }
        }
    }
}

/** A render's stage opens a dialog the page would open on a click. */
private fun openStagedDialog(
    users: UsersModel,
    stage: String?,
    adding: MutableState<AddUserForm?>,
    resetting: MutableState<ResetPasswordForm?>,
    deleting: MutableState<DeleteUserForm?>,
) {
    val user = users.users.firstOrNull { !it.isOperator }
    if (user == null) {
        if (stage == "users.add") adding.value = AddUserForm()
        return
    }
    when (stage) {
        "users.add" -> adding.value = AddUserForm()
        "users.reset" -> resetting.value = ResetPasswordForm(username = user.username)
        "users.delete" -> deleting.value = DeleteUserForm(user = user)
    }
}
