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
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.FormError
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WebField
import com.junbingao.remotecontrol.win.design.overlay.Modal
import com.junbingao.remotecontrol.win.shared.AccountErrors
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * `ResetPasswordModal.tsx` — A24: the admin sets a member's password outright. It never asks for
 * the old one — the admin does not have it — and the account's open sign-ins stay valid.
 */
class ResetPasswordForm(val username: String) {
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    val ready: Boolean get() = AccountRules.isPasswordLongEnough(password)

    suspend fun submit(to: UsersModel, on: ConnectionStore): Boolean {
        val store = to.store(on = on) ?: return false
        busy = true
        error = null
        return try {
            store.resetPassword(of = username, to = password)
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
fun ResetPasswordFields(form: ResetPasswordForm) {
    VStack(spacing = 0.dp, alignment = Alignment.Start) {
        FieldLabel(S.account.newPassword)
        WebField(form.password, { form.password = it }, secure = true)
        form.error?.let { FormError(it, Modifier.padding(top = Space.sp3)) }
    }
}

/** The Reset password modal, open while there is a form for it. */
@Composable
fun ResetPasswordModal(form: ResetPasswordForm?, isPresented: Boolean, onDismiss: () -> Unit, users: UsersModel, connection: ConnectionStore) {
    Modal(
        isPresented = isPresented,
        onDismiss = onDismiss,
        title = S.users.resetPasswordTitle(form?.username ?: ""),
        width = 420.dp,
        footer = {
            if (form != null) {
                val model = LocalAppModel.current
                Btn(S.common.cancel, action = onDismiss)
                Disabled(!form.ready) {
                    Btn(S.common.save, variant = ButtonVariant.primary, busy = form.busy) {
                        model.tasks.launch { if (form.submit(to = users, on = connection)) onDismiss() }
                    }
                }
            }
        },
    ) {
        if (form != null) ResetPasswordFields(form)
    }
}
