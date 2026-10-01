package com.junbingao.remotecontrol.win.users

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.FormError
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.overlay.Modal
import com.junbingao.remotecontrol.win.shared.AccountErrors
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * `DeleteUserModal.tsx` — A24: deleting an account revokes its devices and their sessions leave
 * the gateway, so the confirmation names how many go with it.
 */
class DeleteUserForm(val user: UserRecord) {
    var busy by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** "Delete alice?", or "Delete alice and its 2 devices?" when some go too. */
    val body: String
        get() = if (user.devices == 0) S.users.deleteBody(user.username) else S.users.deleteBodyDevices(user.username, user.devices)

    suspend fun submit(to: UsersModel, on: ConnectionStore): Boolean {
        val store = to.store(on = on) ?: return false
        busy = true
        error = null
        return try {
            store.delete(user.username)
            to.load(on = on)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refusal: Exception) {
            error = AccountErrors.userErrorText(refusal, conflict = S.account.notAllowed)
            busy = false
            false
        }
    }
}

@Composable
fun DeleteUserFields(form: DeleteUserForm) {
    VStack(spacing = 0.dp, alignment = Alignment.Start) {
        Hint(form.body)
        form.error?.let { FormError(it, Modifier.padding(top = Space.sp3)) }
    }
}

/** The Delete account modal, open while there is a form for it. */
@Composable
fun DeleteUserModal(form: DeleteUserForm?, isPresented: Boolean, onDismiss: () -> Unit, users: UsersModel, connection: ConnectionStore) {
    Modal(
        isPresented = isPresented,
        onDismiss = onDismiss,
        title = S.users.deleteTitle,
        width = 440.dp,
        footer = {
            if (form != null) {
                val model = LocalAppModel.current
                Btn(S.common.cancel, action = onDismiss)
                Btn(S.users.deleteConfirm, variant = ButtonVariant.danger, busy = form.busy) {
                    model.tasks.launch { if (form.submit(to = users, on = connection)) onDismiss() }
                }
            }
        },
    ) {
        if (form != null) DeleteUserFields(form)
    }
}
