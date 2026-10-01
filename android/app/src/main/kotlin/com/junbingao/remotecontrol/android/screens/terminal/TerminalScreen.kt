package com.junbingao.remotecontrol.android.screens.terminal

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.fragment.app.FragmentActivity
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ChipButtonStyle
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.navigation.HidesTabBar
import com.junbingao.remotecontrol.android.navigation.LocalNavigator
import com.junbingao.remotecontrol.android.navigation.NavigationMetrics
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.security.BiometricLock
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.android.system.TopBar
import com.junbingao.remotecontrol.android.system.safeArea
import com.junbingao.remotecontrol.android.terminal.TerminalFeed
import com.junbingao.remotecontrol.android.terminal.TerminalHost
import com.junbingao.remotecontrol.android.terminal.TerminalPasteboard
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.state.ConnectionPhase
import com.junbingao.remotecontrol.core.state.ControlLatch
import com.junbingao.remotecontrol.core.state.TerminalKey
import com.junbingao.remotecontrol.core.state.TerminalSession
import com.junbingao.remotecontrol.core.state.TerminalSize
import com.junbingao.remotecontrol.core.state.TerminalStatusText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A shell on one machine (amendment A38, `docs/DESIGN.md` § "The terminal").
 *
 * Full screen, the machine's name as the title, **Close** at the trailing edge, a thin status line
 * under it and the emulator taking everything else. The protocol lives in `TerminalSession` and the
 * rendering in `TerminalHost`; what is here is the screen between them — when to open, what the
 * line says, and the key bar a phone needs.
 */
@Composable
fun TerminalScreen(deviceID: String) {
    val model = LocalAppModel.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    // The session's own work — its input, its resizes, the last close — runs on past the screen
    // that started it, as the iPhone's unstructured tasks do: leaving is what sends the close.
    val tasks = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    val feed = remember { TerminalFeed() }
    val latch = remember { ControlLatch() }
    // Whether the App Lock has been answered. Nothing is opened, and no emulator is built, until it
    // has (`docs/DESIGN.md` § "The terminal" → **Safety**).
    var isUnlocked by remember { mutableStateOf(false) }
    var hasOpened by remember { mutableStateOf(false) }
    val device = model.connection.device(deviceID)
    val isLocked = model.settings.appLockEnabled && !isUnlocked

    // The emulator has a size. The first one opens the shell; every one after it follows the view
    // (`terminal.resize`).
    fun laidOut(size: TerminalSize) {
        val current = session ?: return
        if (!hasOpened) {
            hasOpened = true
            tasks.launch { current.open(cols = size.cols, rows = size.rows) }
            return
        }
        current.resize(cols = size.cols, rows = size.rows)
    }

    fun begin() {
        if (session != null) return
        val channel = model.connection.channel ?: return
        val created = TerminalSession(deviceID = deviceID, channel = channel, tasks = tasks)
        created.onOutput = { bytes -> feed.write(bytes) }
        session = created
        model.connection.addFrameHandler("terminal") { frame -> created.receive(frame) }
        // The emulator may already have been laid out while the lock was up.
        feed.size?.let(::laidOut)
    }

    fun close() {
        model.connection.removeFrameHandler("terminal")
        session?.close()
    }

    fun leave() {
        close()
        navigator?.pop()
    }

    // Ask for another shell after one exited, at whatever size the emulator is.
    fun start() {
        val current = session ?: return
        val size = feed.size ?: return
        tasks.launch { current.open(cols = size.cols, rows = size.rows) }
    }

    // Bytes the emulator produced from a keystroke, through the sticky Ctrl.
    fun typed(bytes: ByteArray) {
        session?.type(latch.apply(bytes))
    }

    fun press(key: TerminalKey) {
        when (key) {
            TerminalKey.control -> latch.toggle()
            TerminalKey.paste -> {
                latch.disarm()
                TerminalPasteboard.bytes(context)?.let { session?.type(it) }
            }
            else -> key.bytes?.let { session?.type(latch.apply(it)) }
        }
    }

    // The App Lock first, then the session. Opening a terminal is the most powerful thing this app
    // can do to a machine, so an enabled lock is asked again here even inside an unlocked app; a
    // refusal goes back.
    LaunchedEffect(Unit) {
        if (model.settings.appLockEnabled) {
            val granted = activity?.let { BiometricLock.authenticate(it, L10n.string("Open a terminal on this device")) } ?: false
            if (!granted) {
                navigator?.pop()
                return@LaunchedEffect
            }
        }
        isUnlocked = true
        begin()
    }
    DisposableEffect(Unit) { onDispose { close() } }
    // The socket, not the app: backgrounding sends nothing, and it is the loss of the link that
    // makes the screen attach again when it returns.
    val phase = model.connection.phase
    LaunchedEffect(phase) { session?.link(isUp = phase == ConnectionPhase.Connected) }

    HidesTabBar()
    NavigationScreen(
        device?.name ?: "",
        displayMode = TitleDisplayMode.inline,
        onBack = ::leave,
        trailing = { BarTextButton(L10n.string("Close"), ::leave, tag = "terminal.close") },
        top = {
            TopBar {
                Column {
                    Spacer(Modifier.height(NavigationMetrics.barFoot))
                    StatusLine(device, session, reconnect = { tasks.launch { session?.attach() } }, newShell = ::start)
                }
            }
        },
    ) { insets ->
        // The key bar stands over the keyboard while it is up, and over the screen's foot otherwise.
        val foot = maxOf(insets.bottom, safeArea().keyboard)
        Column(Modifier.fillMaxSize().padding(top = insets.top)) {
            if (isLocked) {
                Box(Modifier.weight(1f).fillMaxWidth())
            } else {
                TerminalHost(
                    feed,
                    fontSize = model.settings.terminalFontSize,
                    onSize = ::laidOut,
                    onInput = ::typed,
                    onFontSize = { model.settings.terminalFontSize = it },
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Theme.surface),
                )
                TerminalKeyBar(latch.isArmed, ::press, reach = foot)
            }
            Spacer(Modifier.height(foot))
        }
    }
}

/**
 * `docs/DESIGN.md`: Connecting, Connected, or Disconnected with a Reconnect; the exit code with a
 * New shell; and a machine that has gone says so in place of any of them.
 */
@Composable
private fun StatusLine(device: Device?, session: TerminalSession?, reconnect: () -> Unit, newShell: () -> Unit) {
    val line = when {
        device == null -> L10n.string("That device is no longer on this gateway.")
        !device.online -> L10n.string("This device is offline.")
        session == null -> L10n.string("Connecting")
        else -> TerminalStatusText.line(session.status)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Theme.Space.page, vertical = Theme.Space.tight),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line, Modifier.testTag("terminal.status"), style = Theme.Text.caption, color = Theme.inkSecondary)
        if (session?.missedOutput == true) {
            Text(L10n.string("Some output was lost."), Modifier.testTag("terminal.gap"), style = Theme.Text.caption, color = Theme.attention)
        }
        Spacer(Modifier.weight(1f))
        val status = session?.status
        if (status != null && device?.online != false) {
            if (TerminalStatusText.offersReconnect(status)) {
                Button(onClick = reconnect, Modifier.testTag("terminal.reconnect"), style = ChipButtonStyle) { Text(L10n.string("Reconnect")) }
            } else if (TerminalStatusText.offersNewShell(status)) {
                Button(onClick = newShell, Modifier.testTag("terminal.newShell"), style = ChipButtonStyle) { Text(L10n.string("New shell")) }
            }
        }
    }
}
