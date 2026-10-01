package com.junbingao.remotecontrol.android.screens.users

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
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.screens.settings.SheetContentTop
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.android.system.Segment
import com.junbingao.remotecontrol.android.system.SegmentedControl
import com.junbingao.remotecontrol.core.protocol.AccountRules
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.state.AccountError
import com.junbingao.remotecontrol.core.state.UsersStore
import com.junbingao.remotecontrol.core.state.trimmed
import kotlinx.coroutines.CancellationException

/**
 * Username, password, role. Member is the default: an admin is a deliberate choice and never the
 * one made by leaving a control alone.
 */
@Composable
fun AddUserSheet(store: UsersStore, dismiss: () -> Unit) {
    val model = LocalAppModel.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.member) }
    var isWorking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun add() {
        if (isWorking) return
        isWorking = true
        val name = username.trimmed.lowercase()
        val secret = password
        val chosen = role
        model.perform {
            try {
                store.create(username = name, password = secret, role = chosen)
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = L10n.platform(AccountError.create(failure))
            } finally {
                isWorking = false
            }
        }
    }

    NavigationScreen(
        L10n.string("Add user"),
        displayMode = TitleDisplayMode.inline,
        showsBack = false,
        leading = { BarTextButton(L10n.string("Cancel"), dismiss) },
        trailing = {
            BarTextButton(
                L10n.string("Add"),
                ::add,
                enabled = !isWorking && username.trimmed.isNotEmpty() && AccountRules.isPasswordLongEnough(password),
                prominent = true,
                tag = "addUser.add",
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insets.padding())
                .padding(top = SheetContentTop)
                .padding(horizontal = Theme.Space.page)
                .padding(bottom = Theme.Space.large),
            verticalArrangement = Arrangement.spacedBy(Theme.Space.large),
        ) {
            Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(Theme.Space.medium)) {
                Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
                    FieldLabel("Username")
                    TextField(L10n.string("their username"), username, { username = it }, tag = "addUser.username")
                }
                Divider(color = Theme.border)
                Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
                    FieldLabel("Password")
                    TextField(
                        L10n.string("At least 8 characters"), password, { password = it },
                        secure = true,
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                        tag = "addUser.password",
                    )
                }
                Divider(color = Theme.border)
                Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
                    FieldLabel("Role")
                    val roles = listOf(UserRole.member, UserRole.admin)
                    SegmentedControl(
                        segments = roles.map { Segment(it.title, tag = "addUser.role.${it.rawValue}") },
                        selected = roles.indexOf(role),
                        onSelect = { role = roles[it] },
                        fill = true,
                        tag = "addUser.role",
                    )
                }
            }
            error?.let { Text(it, Modifier.testTag("addUser.error"), style = SystemFont.footnote, color = Theme.danger) }
            Text(
                L10n.string("An admin sees every account on this gateway and can change them. A member sees only its own devices and sessions."),
                style = Theme.Text.caption,
                color = Theme.inkSecondary,
            )
        }
    }
}
