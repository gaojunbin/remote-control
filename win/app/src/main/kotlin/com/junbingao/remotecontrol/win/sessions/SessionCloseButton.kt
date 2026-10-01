package com.junbingao.remotecontrol.win.sessions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.SessionClose
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.Help
import com.junbingao.remotecontrol.win.design.iconBtn
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.overlay.ConfirmDialog
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch

/**
 * `SessionCloseButton.tsx`: the Close action at the end of a session row (A39, `docs/DESIGN.md`
 * § "Close, then the Archive"). It ends the session on the machine — the device interrupts the turn
 * and lets go of the agent — and the row lands in the Archive when the device's reply comes back.
 *
 * The question is asked only while the session is working, by the dot's own tone, because that is
 * the only moment there is unfinished work to lose. An idle session closes on the click.
 */
@Composable
internal fun SessionCloseButton(
    session: Session,
    online: Boolean,
    /** A preview stage's: the question drawn open. */
    asksOnAppear: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val model = LocalAppModel.current
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Closes, and keeps the question open when the device refused: the row is only closed once the
    // store holds it archived.
    fun close() {
        busy = true
        model.tasks.launch {
            model.connection.close(session = session)
            if (model.connection.session(deviceID = session.deviceID, sessionID = session.sessionID)?.archived != false) asking = false
            busy = false
        }
    }

    fun tap() {
        if (SessionClose.asksFirst(session, online = online)) asking = true else close()
    }

    Help(S.sessions.close, modifier) {
        Disabled(busy) {
            Button(::tap, style = iconBtn, accessibilityLabel = S.sessions.close) { Icon(LucideIcon.circleX, size = 15.dp) }
        }
    }
    ConfirmDialog(
        isPresented = asking,
        onDismiss = { asking = false },
        title = S.sessions.closeTitle,
        body = S.sessions.closeBody,
        confirmLabel = S.sessions.close,
        danger = true,
        busy = busy,
        onConfirm = ::close,
    )
    LaunchedEffect(Unit) { if (asksOnAppear && SessionClose.asksFirst(session, online = online)) asking = true }
}
