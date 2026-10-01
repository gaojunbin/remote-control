package com.junbingao.remotecontrol.win.settings

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
import com.junbingao.remotecontrol.core.transport.TransportError
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
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * `ChangePasswordModal.tsx` — A24: an account changes its own password here. `admin`'s is
 * `RC_PASSWORD` and the gateway refuses it, which is why the row that opens this is every other
 * account's. One of these is made for each opening, so every opening starts empty.
 */
class ChangePasswordForm {
    var current by mutableStateOf("")
    var next by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    val ready: Boolean get() = current.isNotEmpty() && AccountRules.isPasswordLongEnough(next)

    /** True once the gateway has taken the new password. */
    suspend fun submit(on: ConnectionStore): Boolean {
        busy = true
        error = null
        return try {
            on.changePassword(current = current, new = next)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refusal: Exception) {
            error = errorText(refusal)
            busy = false
            false
        }
    }

    companion object {
        /**
         * `passwordErrorText`: the refusal read from its status. A `401` reaches this side as
         * `TransportError.Unauthorized`, and here it is the current password that was wrong, not
         * the session.
         */
        fun errorText(error: Throwable): String = when {
            error is TransportError.Unauthorized -> S.account.wrongCurrentPassword
            error is TransportError.Http && error.status == 403 -> S.account.notAllowed
            error is TransportError.Http && error.status == 400 -> S.account.rules
            else -> S.errors.generic
        }
    }
}

@Composable
fun ChangePasswordFields(form: ChangePasswordForm) {
    VStack(spacing = 0.dp, alignment = Alignment.Start) {
        FieldLabel(S.account.currentPassword)
        WebField(form.current, { form.current = it }, secure = true)
        FieldLabel(S.account.newPassword, Modifier.padding(top = Space.sp4))
        WebField(form.next, { form.next = it }, secure = true)
        form.error?.let { FormError(it, Modifier.padding(top = Space.sp3)) }
    }
}

/** The Change password modal, open while there is a form for it. */
@Composable
fun ChangePasswordModal(form: ChangePasswordForm?, isPresented: Boolean, onDismiss: () -> Unit, connection: ConnectionStore) {
    Modal(
        isPresented = isPresented,
        onDismiss = onDismiss,
        title = S.settings.changePassword,
        width = 420.dp,
        footer = {
            if (form != null) {
                val model = LocalAppModel.current
                Btn(S.common.cancel, action = onDismiss)
                Disabled(!form.ready) {
                    Btn(S.common.save, variant = ButtonVariant.primary, busy = form.busy) {
                        model.tasks.launch { if (form.submit(on = connection)) onDismiss() }
                    }
                }
            }
        },
    ) {
        if (form != null) ChangePasswordFields(form)
    }
}
