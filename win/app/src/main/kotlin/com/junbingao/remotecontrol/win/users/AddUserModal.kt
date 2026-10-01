package com.junbingao.remotecontrol.win.users

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AccountRules
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.FormError
import com.junbingao.remotecontrol.win.design.SegmentOption
import com.junbingao.remotecontrol.win.design.Segmented
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WebField
import com.junbingao.remotecontrol.win.design.overlay.Modal
import com.junbingao.remotecontrol.win.shared.AccountErrors
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * `AddUserModal.tsx` — A24: the admin makes an account without waiting for anyone to register.
 * One of these is made for each opening, so every opening starts empty.
 */
class AddUserForm {
    var username by mutableStateOf("")
    var password by mutableStateOf("")

    /** Member first, and the default: an admin is the exception on this screen. */
    var role by mutableStateOf(UserRole.member)
    var busy by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    val ready: Boolean
        get() = username.trimmed.isNotEmpty() && AccountRules.isPasswordLongEnough(password)

    /** True once the account exists and the list has been read again. */
    suspend fun submit(to: UsersModel, on: ConnectionStore): Boolean {
        val store = to.store(on = on) ?: return false
        busy = true
        error = null
        return try {
            store.create(username = username.trimmed, password = password, role = role)
            to.load(on = on)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refusal: Exception) {
            error = AccountErrors.userErrorText(refusal, conflict = S.account.taken)
            busy = false
            false
        }
    }
}

/** The modal's `.form-stack`: username, password, the role, and the refusal. */
@Composable
fun AddUserFields(form: AddUserForm) {
    VStack(spacing = 0.dp, alignment = Alignment.Start) {
        FieldLabel(S.account.username)
        WebField(form.username, { form.username = it })
        FieldLabel(S.account.password, Modifier.padding(top = Space.sp4))
        WebField(form.password, { form.password = it }, secure = true)
        FieldLabel(S.account.role, Modifier.padding(top = Space.sp4))
        Segmented(
            value = form.role,
            options = listOf(UserRole.member, UserRole.admin).map { SegmentOption(it, S.roleLabel(it.rawValue)) },
            ariaLabel = S.account.role,
        ) { form.role = it }
        form.error?.let { FormError(it, Modifier.padding(top = Space.sp3)) }
    }
}

/** The Add user modal, open while there is a form for it. */
@Composable
fun AddUserModal(form: AddUserForm?, isPresented: Boolean, onDismiss: () -> Unit, users: UsersModel, connection: ConnectionStore) {
    Modal(
        isPresented = isPresented,
        onDismiss = onDismiss,
        title = S.users.add,
        width = 420.dp,
        footer = {
            if (form != null) {
                val model = LocalAppModel.current
                Btn(S.common.cancel, action = onDismiss)
                Disabled(!form.ready) {
                    Btn(S.users.add, variant = ButtonVariant.primary, busy = form.busy) {
                        model.tasks.launch { if (form.submit(to = users, on = connection)) onDismiss() }
                    }
                }
            }
        },
    ) {
        if (form != null) AddUserFields(form)
    }
}
