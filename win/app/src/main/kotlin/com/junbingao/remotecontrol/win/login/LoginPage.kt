package com.junbingao.remotecontrol.win.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.app.lastSignInError
import com.junbingao.remotecontrol.win.app.register
import com.junbingao.remotecontrol.win.app.signIn
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.FieldText
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.FormError
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Mark
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Shadow
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.card
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.fieldChrome
import com.junbingao.remotecontrol.win.design.overlay.WholePointCenter
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * `web/src/features/login/LoginPage.tsx`: the card that asks for an account, or makes one (A24) —
 * with one field the web's has no need for, the gateway's address, above the username
 * (`docs/DESIGN.md` § "The Windows app" → **Sign-in, overlays, dictation and Update required are
 * the Mac's**). The address and the username are remembered for the next launch; the password
 * never is.
 */
@Composable
fun LoginPage() {
    val model = LocalAppModel.current
    val stage = LocalPreviewStage.current
    val layout = LocalLayoutClass.current
    val form = remember { LoginForm() }
    val requesters = remember { LoginField.entries.associateWith { FocusRequester() } }

    fun switchTo(next: LoginMode) {
        form.mode = next
        form.error = null
        form.password = ""
        if (next == LoginMode.register) form.focusing = LoginField.username
    }

    fun submit() {
        if (form.busy || !form.ready) return
        val address = form.origin.trimmed
        // An address the app would refuse is refused here, in the form's words, before anything is sent.
        if (runCatching { GatewayEndpoint(address) }.isFailure) {
            form.error = S.win.gatewayInvalid
            return
        }
        form.busy = true
        form.error = null
        val name = form.username.trimmed
        val registering = form.registering
        // Started on the model's scope: the sign-in outlives this page, which goes the moment it succeeds.
        model.tasks.launch {
            if (registering) {
                model.register(origin = address, username = name, password = form.password)
            } else {
                model.signIn(origin = address, username = name, password = form.password)
            }
            form.busy = false
            if (model.isSignedIn) return@launch
            val failure = model.lastSignInError
            if (registering && LoginErrorText.closesRegistration(failure)) form.registrationOpen = false
            form.error = if (registering) LoginErrorText.register(failure) else LoginErrorText.signIn(failure)
            form.password = ""
        }
    }

    ThinScrollView(modifier = Modifier.fillMaxSize().background(Palette.canvas)) {
        // `.login` centres the card; the browser puts it on a whole point.
        WholePointCenter(minimumHeight = layout.height.dp) {
            LoginCard(form, requesters, onSubmit = ::submit, onSwitch = ::switchTo)
        }
    }

    // The address and the account the app last signed in with, and the focus where typing starts:
    // the first field still empty.
    LaunchedEffect(Unit) {
        form.origin = model.settings.lastOrigin
        form.username = model.settings.username(form.origin)
        form.focusing = when {
            form.origin.isEmpty() -> LoginField.origin
            form.username.isEmpty() -> LoginField.username
            else -> LoginField.password
        }
        // A render of a state that takes typing: a refused password, the registration form.
        when (stage) {
            "login.error" -> {
                form.password = "wrongpass"
                submit()
            }
            "login.register" -> switchTo(LoginMode.register)
        }
    }
    LaunchedEffect(form.focusing) {
        form.focusing?.let { requesters.getValue(it).requestFocus() }
    }
    // `GET /api/health` for the address typed, once typing pauses. An unreachable gateway takes no
    // registrations either.
    LaunchedEffect(form.origin.trimmed) {
        val address = form.origin.trimmed
        if (runCatching { GatewayEndpoint(address) }.isFailure) {
            form.registrationOpen = false
            return@LaunchedEffect
        }
        delay(350)
        val open = model.connection.registrationOpen(origin = address)
        form.registrationOpen = open
        if (!open && form.registering) switchTo(LoginMode.signIn)
    }
}

internal enum class LoginMode { signIn, register }

internal enum class LoginField { origin, username, password }

/** What the form holds while it is on screen. */
internal class LoginForm {
    var mode by mutableStateOf(LoginMode.signIn)
    var origin by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** A24: whether this gateway takes registrations. The login page is the only place that asks, because it is the only place that offers it. */
    var registrationOpen by mutableStateOf(false)

    /** The field the focus is sent to. */
    var focusing by mutableStateOf<LoginField?>(null)

    /** The field the focus is in. */
    var focused by mutableStateOf<LoginField?>(null)

    val registering: Boolean get() = mode == LoginMode.register
    val ready: Boolean get() = origin.trimmed.isNotEmpty() && username.trimmed.isNotEmpty() && password.isNotEmpty()

    val buttonTitle: String
        get() = when {
            busy && registering -> S.login.creating
            busy -> S.login.signingIn
            registering -> S.login.createAccount
            else -> S.login.submit
        }
}

@Composable
private fun LoginCard(form: LoginForm, requesters: Map<LoginField, FocusRequester>, onSubmit: () -> Unit, onSwitch: (LoginMode) -> Unit) {
    VStack(
        Modifier.padding(Space.sp6).widthIn(max = 380.dp).card(shadow = Shadow.one).padding(Space.sp8),
        spacing = 0.dp,
        alignment = Alignment.Start,
    ) {
        HStack(Modifier.padding(bottom = Space.sp2), spacing = Space.sp3) {
            Mark(size = 26.dp)
            Text(S.login.title, css(FontSize.fs22, weight = FontWeight.SemiBold, tracking = -0.01f))
        }
        Hint(if (form.registering) S.login.registerSubtitle else S.login.subtitle, Modifier.padding(bottom = Space.sp6))

        FieldLabel(S.win.gateway)
        Entry(form, LoginField.origin, form.origin, { form.origin = it }, S.win.gatewayPlaceholder, requesters, onSubmit)
        FieldLabel(S.account.username)
        Entry(form, LoginField.username, form.username, { form.username = it }, S.login.usernamePlaceholder, requesters, onSubmit)
        FieldLabel(S.account.password)
        Entry(form, LoginField.password, form.password, { form.password = it }, S.login.passwordPlaceholder, requesters, onSubmit)

        form.error?.let { FormError(it, Modifier.pulledUp(6.dp).padding(bottom = Space.sp4)) }

        Disabled(form.busy || !form.ready) {
            Btn(form.buttonTitle, variant = ButtonVariant.primary, size = ButtonSize.block, action = onSubmit)
        }

        if (form.registering) {
            LoginSwitch(S.login.signInInstead) { onSwitch(LoginMode.signIn) }
        } else if (form.registrationOpen) {
            LoginSwitch(S.login.createAccountLink) { onSwitch(LoginMode.register) }
        }
    }
}

@Composable
private fun Entry(
    form: LoginForm,
    field: LoginField,
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    requesters: Map<LoginField, FocusRequester>,
    onSubmit: () -> Unit,
) {
    Box(Modifier.padding(bottom = Space.sp4).fieldChrome(focused = form.focused == field), contentAlignment = Alignment.CenterStart) {
        FieldText(
            text, onTextChange, placeholder,
            secure = field == LoginField.password,
            modifier = Modifier.fillMaxWidth(),
            focusRequester = requesters.getValue(field),
            onFocusChange = { focused ->
                if (focused) form.focused = field else if (form.focused == field) form.focused = null
            },
            onSubmit = onSubmit,
        )
    }
}

/**
 * `.login-switch`: the one line under the button that swaps the card for the other form — 13 px in
 * the accent ink, underlined under the pointer.
 */
@Composable
private fun LoginSwitch(title: String, action: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = Space.sp4), contentAlignment = Alignment.TopCenter) {
        Button(action, Modifier.pointerHoverIcon(PointerIcon.Hand), style = LoginSwitchStyle(title)) {}
    }
}

private class LoginSwitchStyle(private val title: String) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        val text = if (configuration.isHovered) {
            AnnotatedString(title, listOf(AnnotatedString.Range(SpanStyle(textDecoration = TextDecoration.Underline), 0, title.length)))
        } else {
            AnnotatedString(title)
        }
        Text(text, css(FontSize.fs13), modifier, color = Palette.accent)
    }
}

/** SwiftUI's negative top padding: the view draws `by` higher and takes that much less room. */
private fun Modifier.pulledUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val pull = by.toPx().roundToInt()
    layout(placeable.width, (placeable.height - pull).coerceAtLeast(0)) { placeable.place(0, -pull) }
}
