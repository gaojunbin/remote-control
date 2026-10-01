package com.junbingao.remotecontrol.android.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AppMark
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.Divider
import com.junbingao.remotecontrol.android.design.FieldLabel
import com.junbingao.remotecontrol.android.design.PlainButtonStyle
import com.junbingao.remotecontrol.android.design.PlainTextEntry
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.TextField
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.card
import com.junbingao.remotecontrol.android.design.dismissesKeyboardOnBackgroundTap
import com.junbingao.remotecontrol.android.design.pageBackground
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import com.junbingao.remotecontrol.android.system.safeArea
import com.junbingao.remotecontrol.core.protocol.AccountRules
import com.junbingao.remotecontrol.core.state.trimmed
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gateway address, username and password — and, where the gateway allows it, the same three
 * fields as a way to create the account instead: the iPhone's `LoginView`.
 *
 * `docs/DESIGN.md` § "Accounts": the username is remembered per gateway so the next sign-in is the
 * password alone, a disabled account is told so, and a wrong password is never told which half
 * was wrong. "Create an account" is offered only when `GET /api/health` says registration is open,
 * and this screen is the only place that asks.
 */
@Composable
fun LoginView(model: AppModel) {
    var origin by rememberSaveable { mutableStateOf(model.settings.lastOrigin) }
    // The username belongs to the gateway, not to the phone: the form offers whoever last signed
    // in on the address it is prefilled with.
    var username by rememberSaveable { mutableStateOf(model.settings.username(model.settings.lastOrigin)) }
    var password by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var isRegistering by rememberSaveable { mutableStateOf(false) }
    var registrationOpen by remember { mutableStateOf(false) }
    val focus = remember { LoginFocus() }
    val scope = rememberCoroutineScope()

    // The gateway is typed, so what it allows can only be known once there is an address to ask.
    // Asking again on every keystroke would be one request per character, so the field is left to
    // settle first.
    LaunchedEffect(origin) {
        if (origin.trimmed.isEmpty()) {
            registrationOpen = false
            return@LaunchedEffect
        }
        delay(400.milliseconds)
        registrationOpen = model.connection.registrationOpen(origin.trimmed)
    }

    val isComplete = origin.trimmed.isNotEmpty() && username.trimmed.isNotEmpty() &&
        if (isRegistering) AccountRules.isPasswordLongEnough(password) else password.isNotEmpty()
    val submit: () -> Unit = submit@{
        if (isWorking || !isComplete) return@submit
        isWorking = true
        val address = origin.trimmed
        val name = username.trimmed.lowercase()
        // The person asked for this, so it runs to its end whatever the screen does meanwhile.
        model.perform {
            if (isRegistering) register(address, name, password) else signIn(address, name, password)
            if (isSignedIn) password = ""
            isWorking = false
        }
    }

    Box(Modifier.fillMaxSize().pageBackground().dismissesKeyboardOnBackgroundTap(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = safeArea().top)
                .padding(horizontal = Theme.Space.page)
                .padding(bottom = Theme.Space.large + safeArea().bottomWithKeyboard),
            verticalArrangement = Arrangement.spacedBy(Theme.Space.large),
        ) {
            Heading(isRegistering)
            Fields(origin, { origin = it }, username, { username = it }, password, { password = it }, isRegistering, focus, submit)
            model.connection.errorMessage?.let { error ->
                Text(L10n.platform(error), Modifier.testTag("login.error"), style = SystemFont.footnote, color = Theme.danger)
            }
            Button(onClick = submit, Modifier.testTag("login.connect"), enabled = !isWorking && isComplete, style = PrimaryButtonStyle()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small), verticalAlignment = Alignment.CenterVertically) {
                    if (isWorking) ActivityIndicator(tint = Theme.onAccent)
                    Text(L10n.string(if (isRegistering) "Create account" else "Connect"))
                }
            }
            if (isRegistering) {
                QuietLink(L10n.string("Sign in instead"), "login.signInInstead") {
                    model.connection.clearError()
                    password = ""
                    isRegistering = false
                    scope.launch { focus.password.requestFocus() }
                }
            } else {
                if (registrationOpen) {
                    QuietLink(L10n.string("Create an account"), "login.register") {
                        model.connection.clearError()
                        password = ""
                        isRegistering = true
                        scope.launch { focus.username.requestFocus() }
                    }
                }
                Demo(model)
            }
        }
    }
}

/** The three fields' focus, so the return key can move from one to the next. */
private class LoginFocus {
    val origin = FocusRequester()
    val username = FocusRequester()
    val password = FocusRequester()
}

@Composable
private fun Heading(isRegistering: Boolean) {
    Column(Modifier.padding(top = Theme.Space.large), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        AppMark(52.dp)
        // The product's own name, never translated.
        Text("Remote Control", style = SystemFont.largeTitle.weight(FontWeight.SemiBold), color = Theme.ink)
        Text(
            L10n.string(
                if (isRegistering) "Pick a username and a password for this gateway."
                else "Drive your coding agents on your own machines, from your phone.",
            ),
            style = SystemFont.subheadline,
            color = Theme.inkSecondary,
        )
    }
}

@Composable
private fun Fields(
    origin: String, onOrigin: (String) -> Unit,
    username: String, onUsername: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    isRegistering: Boolean, focus: LoginFocus, submit: () -> Unit,
) {
    Column(Modifier.card(), verticalArrangement = Arrangement.spacedBy(Theme.Space.medium)) {
        Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
            FieldLabel("Gateway")
            TextField(
                L10n.string("https://rc.example.com"), origin, onOrigin,
                Modifier.focusRequester(focus.origin),
                style = Theme.monoBody,
                keyboard = PlainTextEntry.copy(keyboardType = KeyboardType.Uri),
                submit = ImeAction.Next,
                onSubmit = { focus.username.requestFocus() },
                tag = "login.gateway",
            )
        }
        Divider(color = Theme.border)
        Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
            FieldLabel("Username")
            TextField(
                L10n.string("you"), username, onUsername,
                Modifier.focusRequester(focus.username),
                submit = ImeAction.Next,
                onSubmit = { focus.password.requestFocus() },
                tag = "login.username",
            )
        }
        Divider(color = Theme.border)
        Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
            FieldLabel("Password")
            TextField(
                L10n.string(if (isRegistering) "At least 8 characters" else "Your password"), password, onPassword,
                Modifier.focusRequester(focus.password),
                secure = true,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                submit = ImeAction.Go,
                onSubmit = submit,
                tag = "login.password",
            )
        }
    }
}

@Composable
private fun Demo(model: AppModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        QuietLink(L10n.string("Try the demo"), "login.demo") { model.perform { enterDemo() } }
        Text(
            L10n.string("The demo runs entirely on this device with sample data. Nothing is sent anywhere."),
            style = SystemFont.footnote,
            color = Theme.inkSecondary,
        )
    }
}

/** A plain button in the subheadline size and the ink, a full touch tall: the form's secondary choices. */
@Composable
private fun QuietLink(title: String, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, Modifier.heightIn(min = Theme.Touch.minimum).testTag(tag), style = PlainButtonStyle) {
        Box(Modifier.heightIn(min = Theme.Touch.minimum), contentAlignment = Alignment.CenterStart) {
            Text(title, style = SystemFont.subheadline, color = Theme.ink)
        }
    }
}
