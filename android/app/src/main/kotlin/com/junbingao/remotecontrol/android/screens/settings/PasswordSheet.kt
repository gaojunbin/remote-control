package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.junbingao.remotecontrol.android.design.Divider
import com.junbingao.remotecontrol.android.design.FieldLabel
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.TextField
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.card
import com.junbingao.remotecontrol.android.design.scrollIndicator
import com.junbingao.remotecontrol.android.navigation.NavigationMetrics
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.core.protocol.AccountRules
import com.junbingao.remotecontrol.core.state.AccountError
import kotlinx.coroutines.CancellationException

/**
 * Changing your own password (`POST /api/password`, A24).
 *
 * Two fields and nothing else: the one you have and the one you want. Other sign-ins of the
 * account stay valid, which the sheet says, because a person changing a password usually wants to
 * know whether it signs them out.
 */
@Composable
fun PasswordSheet(dismiss: () -> Unit) {
    val model = LocalAppModel.current
    var current by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (isWorking) return
        isWorking = true
        val old = current
        val new = replacement
        model.perform {
            try {
                connection.changePassword(current = old, new = new)
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = L10n.platform(AccountError.passwordChange(failure))
            } finally {
                isWorking = false
            }
        }
    }

    NavigationScreen(
        L10n.string("Change password"),
        displayMode = TitleDisplayMode.inline,
        showsBack = false,
        leading = { BarTextButton(L10n.string("Cancel"), dismiss) },
        trailing = {
            BarTextButton(
                L10n.string("Save"),
                ::save,
                enabled = !isWorking && current.isNotEmpty() && AccountRules.isPasswordLongEnough(replacement),
                prominent = true,
                tag = "password.save",
            )
        },
    ) { insets ->
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .scrollIndicator(scroll, top = insets.top, bottom = insets.bottom)
                .verticalScroll(scroll)
                .padding(insets.padding())
                .padding(top = NavigationMetrics.barFoot)
                .padding(horizontal = Theme.Space.page)
                .padding(bottom = Theme.Space.large),
            verticalArrangement = Arrangement.spacedBy(Theme.Space.large),
        ) {
            Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(Theme.Space.medium)) {
                Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
                    FieldLabel("Current password")
                    TextField(
                        L10n.string("Your password"), current, { current = it },
                        secure = true,
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                        tag = "password.current",
                    )
                }
                Divider(color = Theme.border)
                Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
                    FieldLabel("New password")
                    TextField(
                        L10n.string("At least 8 characters"), replacement, { replacement = it },
                        secure = true,
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                        tag = "password.new",
                    )
                }
            }
            error?.let { Text(it, Modifier.testTag("password.error"), style = SystemFont.footnote, color = Theme.danger) }
            Text(
                L10n.string("You stay signed in here and anywhere else you are signed in."),
                style = Theme.Text.caption,
                color = Theme.inkSecondary,
            )
        }
    }
}
