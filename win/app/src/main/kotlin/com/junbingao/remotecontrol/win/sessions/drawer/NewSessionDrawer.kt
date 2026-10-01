package com.junbingao.remotecontrol.win.sessions.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.FormError
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Spinner
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.btn
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.overlay.Drawer
import com.junbingao.remotecontrol.win.devices.DeviceOrder
import com.junbingao.remotecontrol.win.devices.ListPresence
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * `NewSessionDrawer.tsx`: the right-hand drawer the Sessions page and the chat sidebar open, with
 * the fields in the order they are decided — device, agent, the agent's options, working directory,
 * git — and one primary button. Starting a session opens its conversation; Ctrl+Enter starts it
 * from anywhere in the drawer, where the Mac's is ⌘↵.
 */
@Composable
internal fun NewSessionDrawer(form: MutableState<NewSessionForm?>) {
    val model = LocalAppModel.current
    val open = form.value
    val device = open?.device(DeviceOrder.online(model.connection.devices))
    val presence = ListPresence.of(form)

    fun opened(session: Session) {
        form.value = null
        model.router.go(Route.Chat(deviceId = session.deviceID, sessionId = session.sessionID))
    }

    Drawer(
        isPresented = presence.isPresented,
        onDismiss = presence.onDismiss,
        title = S.newSession.title,
        subtitle = device?.let { S.newSession.continuingOn(it.name) } ?: S.newSession.pickDevice,
        footer = open?.let { current -> { NewSessionFooter(current, onStarted = ::opened) } },
    ) {
        if (open != null) NewSessionFields(open)
    }
}

/** The drawer's foot: why the last start failed, and Start session with its shortcut at the trailing edge. */
@Composable
internal fun NewSessionFooter(form: NewSessionForm, onStarted: (Session) -> Unit) {
    val model = LocalAppModel.current
    val device = form.device(DeviceOrder.online(model.connection.devices))
    val agent = form.agent(of = device)
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp) {
        val error = form.error
        if (error != null) FormError(error, Modifier.padding(bottom = Space.sp3))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Disabled(!form.canStart(device, agent)) {
                Button({ NewSessionFooter.start(form, model, onStarted) }, style = btn(ButtonVariant.primary, ButtonSize.block)) {
                    if (form.busy) Spinner()
                    Text(if (form.busy) S.newSession.starting else S.newSession.start, css(FontSize.fs15, weight = FontWeight.Medium), Modifier.fillMaxHeight(), softWrap = false)
                }
            }
            // `.kbd-hint`: the shortcut 16 px in from the button's trailing edge, in Windows' keys.
            Text(
                NewSessionFooter.SHORTCUT, css(FontSize.fs12, mono = true),
                Modifier.padding(end = Space.sp4).alpha(0.65f),
                color = Palette.inkInverse,
            )
        }
    }
    StartShortcut(form, model, onStarted)
}

internal object NewSessionFooter {
    /** Ctrl takes ⌘'s place (`docs/DESIGN.md` § "The Windows app"); a key's name is the same in both languages. */
    const val SHORTCUT = "Ctrl+Enter"

    fun start(form: NewSessionForm, model: WinAppModel, onStarted: (Session) -> Unit) {
        val device = form.device(DeviceOrder.online(model.connection.devices))
        model.tasks.launch {
            form.start(device = device, agent = form.agent(of = device), channel = model.connection.channel)?.let(onStarted)
        }
    }
}

/**
 * The web listens on the document for ⌘↵ or Ctrl+Enter while the drawer is open, so the shortcut
 * works whichever field has the focus — the picker over it too. Here it is the window's own key
 * events, before any field sees them, for as long as the drawer is drawn.
 */
@Composable
private fun StartShortcut(form: NewSessionForm, model: WinAppModel, onStarted: (Session) -> Unit) {
    val started by rememberUpdatedState(onStarted)
    DisposableEffect(form, model) {
        val manager = if (GraphicsEnvironment.isHeadless()) null else KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            val shortcut = event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ENTER && (event.isControlDown || event.isMetaDown)
            if (shortcut) NewSessionFooter.start(form, model, started)
            shortcut
        }
        manager?.addKeyEventDispatcher(dispatcher)
        onDispose { manager?.removeKeyEventDispatcher(dispatcher) }
    }
}
