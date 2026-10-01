package com.junbingao.remotecontrol.android.screens.devices

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ChipButtonStyle
import com.junbingao.remotecontrol.android.design.Divider
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.card
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.screens.sessions.BarFoot
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.android.system.FullScreenCover
import com.junbingao.remotecontrol.android.system.Sheet
import com.junbingao.remotecontrol.core.protocol.PairingStep
import com.junbingao.remotecontrol.core.state.PairingFlow
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The pairing sheet: copy one command, watch the machine arrive. */
@Composable
fun AddDeviceSheet(dismiss: () -> Unit) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var flow by remember { mutableStateOf<PairingFlow?>(null) }
    var copied by remember { mutableStateOf(false) }
    var showsManual by remember { mutableStateOf(false) }
    var showsScanner by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1.seconds)
            now = Instant.now()
        }
    }
    LaunchedEffect(Unit) {
        if (flow != null) return@LaunchedEffect
        val created = model.pairingFlow() ?: return@LaunchedEffect
        flow = created
        created.begin()
    }
    // Listening lasts as long as the sheet does. The scanner is drawn over the sheet rather than
    // in its place, so the sheet goes on listening through it.
    val listening = flow
    DisposableEffect(listening) {
        if (listening != null) model.connection.addFrameHandler("pairing") { frame -> listening.receive(frame) }
        onDispose { model.connection.removeFrameHandler("pairing") }
    }

    NavigationScreen(
        L10n.string("Add device"),
        displayMode = TitleDisplayMode.inline,
        showsBack = false,
        leading = {
            // Cancel leaves at once; the code is given back behind the closed sheet, so nothing is
            // drawn while the gateway answers.
            BarTextButton(L10n.string("Cancel"), {
                dismiss()
                val leaving = flow
                model.perform { leaving?.cancel() }
            }, tag = "pairing.cancel")
        },
        trailing = {
            BarTextButton(L10n.string("Done"), dismiss, enabled = flow?.isComplete == true, prominent = true, tag = "pairing.done")
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insets.padding())
                .padding(top = BarFoot.height, start = Theme.Space.page, end = Theme.Space.page, bottom = Theme.Space.large),
            verticalArrangement = Arrangement.spacedBy(Theme.Space.large),
        ) {
            val current = flow
            val pairing = current?.pairing
            if (current != null && pairing != null) {
                Code(
                    current, pairing, now, copied,
                    copy = { text ->
                        copyText(context, text)
                        copied = true
                        scope.launch {
                            delay(2.seconds)
                            copied = false
                        }
                    },
                    scan = { showsScanner = true },
                    manual = { showsManual = true },
                    scope = scope,
                )
            } else {
                Waiting()
            }
            current?.errorMessage?.let { Text(L10n.platform(it), style = SystemFont.footnote, color = Theme.danger) }
        }
    }

    Sheet(showsManual, onDismiss = { showsManual = false }) {
        ManualInstallView(command = flow?.command ?: "", code = flow?.code ?: "", dismiss = { showsManual = false })
    }
    FullScreenCover(showsScanner, onDismiss = { showsScanner = false }) {
        flow?.let { ScanPairingView(it, origins(model), scanCommand(model), model.codeScanner, dismiss = { showsScanner = false }) }
    }
}

/**
 * One code, reached one of two ways. A code minted here comes with the one-liner that uses it; a
 * code claimed from a scan does not, because the host that printed the QR code has already run one
 * (A23).
 */
@Composable
private fun Code(
    flow: PairingFlow,
    pairing: PairingFlow.Pairing,
    now: Instant,
    copied: Boolean,
    copy: (String) -> Unit,
    scan: () -> Unit,
    manual: () -> Unit,
    scope: CoroutineScope,
) {
    if (pairing.install != null) {
        Text(
            L10n.string("Run one command on the machine where your agents live. It dials out to the gateway, so nothing is exposed on the host."),
            style = SystemFont.subheadline,
            color = Theme.inkSecondary,
        )
        CommandCard(flow, now, copied, copy, scope)
        ScanButton(scan)
        ProgressCard(flow)
        Button(onClick = manual, Modifier.heightIn(min = Theme.Touch.minimum)) {
            Text(L10n.string("Manual install"), style = SystemFont.footnote, color = Theme.ink)
        }
    } else {
        ClaimedCard(flow, now)
        ProgressCard(flow)
    }
}

@Composable
private fun Waiting() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActivityIndicator()
        Text(L10n.string("Requesting a code"), color = Theme.inkSecondary)
    }
}

/** Amendment A23: the other way in. The host runs one command, prints a QR code, and this claims it — no code is typed anywhere. */
@Composable
private fun ScanButton(scan: () -> Unit) {
    Button(onClick = scan, Modifier.testTag("pairing.scan"), style = ChipButtonStyle) {
        Label(L10n.string("Scan a code"), Sf.qrcodeViewfinder)
    }
}

/** The code the gateway minted for a scanned host. There is no one-liner beside it: the host ran one to get here. */
@Composable
private fun ClaimedCard(flow: PairingFlow, now: Instant) {
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        Text(L10n.string("This host asked to join your gateway."), style = SystemFont.subheadline, color = Theme.inkSecondary)
        CodeLine(flow, now, "pairing.claimedCode")
    }
}

@Composable
private fun CommandCard(flow: PairingFlow, now: Instant, copied: Boolean, copy: (String) -> Unit, scope: CoroutineScope) {
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(breakingAfterSlashes(flow.command), Modifier.testTag("pairing.command"), style = Theme.mono, color = Theme.ink)
            }
            CopyButton(copied, "pairing.copy") { copy(flow.command) }
        }
        Divider(color = Theme.border)
        CodeLine(flow, now, "pairing.expiry")
        if (flow.hasExpired(now)) {
            Button(onClick = { scope.launch { flow.begin() } }, style = ChipButtonStyle) { Text(L10n.string("Get a new code")) }
        }
    }
}

/** The code, "single use", and how long it has left — or that it has run out. */
@Composable
private fun CodeLine(flow: PairingFlow, now: Instant, tag: String) {
    val expired = flow.hasExpired(now)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(flow.code, style = Theme.mono, color = Theme.ink)
        Text(L10n.string("single use"), Modifier.weight(1f), style = SystemFont.caption, color = Theme.inkSecondary)
        Text(
            if (expired) L10n.string("expired") else L10n.string("expires in %@", flow.expiry(now)),
            Modifier.testTag(tag),
            style = SystemFont.caption,
            color = if (expired) Theme.danger else Theme.inkSecondary,
        )
    }
}

@Composable
private fun ProgressCard(flow: PairingFlow) {
    Column(Modifier.fillMaxWidth().card().testTag("pairing.steps"), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        for (step in flow.steps) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(Theme.Space.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (step.done) Sf.checkmarkCircleFill else Sf.circle, font = SystemFont.body, tint = if (step.done) Theme.ink else Theme.resting)
                Text(step.title, style = SystemFont.subheadline, color = if (step.done) Theme.ink else Theme.inkSecondary)
                if (step.id == PairingStep.agents && flow.detectedAgents.isNotEmpty()) {
                    Text(flow.detectedAgents, style = Theme.mono, color = Theme.inkSecondary)
                }
            }
        }
    }
}

/** The quiet Copy pill beside a command, which says Copied for a moment once it has. */
@Composable
internal fun CopyButton(copied: Boolean, tag: String, copy: () -> Unit) {
    Button(onClick = copy, Modifier.testTag(tag), style = ChipButtonStyle) {
        Box(Modifier.widthIn(min = 54.dp), contentAlignment = Alignment.Center) {
            Text(L10n.string(if (copied) "Copied" else "Copy"))
        }
    }
}

/** The origins a scanned link may name: the one this app dials, and the one the gateway publishes to the world. They differ on a LAN sign-in. */
private fun origins(model: AppModel): List<GatewayEndpoint> {
    val found = listOfNotNull(model.connection.endpoint).toMutableList()
    val published = model.connection.config.publicOrigin
    if (published.isNotEmpty()) {
        runCatching { GatewayEndpoint(published) }.getOrNull()?.takeIf { it !in found }?.let(found::add)
    }
    return found
}

private fun scanCommand(model: AppModel): String {
    val published = model.connection.config.publicOrigin
    val origin = published.ifEmpty { model.connection.endpoint?.origin ?: "" }
    return "curl -fsSL $origin/install.sh | sh"
}

/**
 * A command as the iPhone wraps it: a long URL breaks after a path's slash (`https://` ·
 * `demo.remote-control.invalid/` · `install.sh`), where Android's line breaker would wait for a
 * dot. Only what is drawn changes; Copy hands over the command itself.
 */
internal fun breakingAfterSlashes(command: String): String = command.replace(Regex("/(?!/)"), "/\u200B")

/** The clipboard, which nothing else on these screens writes. */
internal fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(text, text))
}

