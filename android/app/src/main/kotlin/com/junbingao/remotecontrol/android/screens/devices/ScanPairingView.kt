package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.junbingao.remotecontrol.android.attachments.Camera
import com.junbingao.remotecontrol.android.attachments.CameraAccess
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.ChipButtonStyle
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.card
import com.junbingao.remotecontrol.android.scanner.CodeScanning
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import com.junbingao.remotecontrol.android.system.safeArea
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.state.PairingClaimLink
import com.junbingao.remotecontrol.core.state.PairingFlow
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Amendment A23: pairing a host by scanning the QR code it prints.
 *
 * The camera fills the screen and the two steps sit over it, because both of them happen while the
 * camera is open: the one-liner is read off this screen and run on the host, and the code it prints
 * is what the camera is pointed at. A payload for another gateway is answered in the strip at the
 * bottom and the camera keeps looking.
 */
@Composable
fun ScanPairingView(
    flow: PairingFlow,
    origins: List<GatewayEndpoint>,
    installCommand: String,
    scanner: CodeScanning,
    dismiss: () -> Unit,
) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Nothing until the camera is known to be looking: "Hold steady" is said only while one actually
    // is (`docs/DESIGN.md` § "The three screens" → **A camera the app may not use says so**).
    var status by remember { mutableStateOf("") }
    var access by remember { mutableStateOf<CameraAccess?>(null) }
    var isClaiming by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(scanner) {
        val granted = scanner.requestAccess()
        access = granted
        if (granted != CameraAccess.allowed) return@LaunchedEffect
        status = L10n.string("Hold steady — the QR code is detected automatically.")
    }

    // One payload at a time: a camera reports the same code on every frame it holds, and a second
    // claim of a spent token would report it as used.
    fun offer(payload: String) {
        if (isClaiming) return
        val link = PairingClaimLink(payload = payload, gateways = origins)
        if (link == null) {
            status = L10n.string("That code belongs to a different gateway")
            return
        }
        isClaiming = true
        status = L10n.string("Claiming that code…")
        model.perform {
            try {
                // The sheet behind this one holds the same flow and follows the claimed code's
                // progress, so there is nothing to hand back.
                flow.claim(token = link.token)
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status = message(error)
                isClaiming = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (access == CameraAccess.allowed) scanner.Viewfinder(onCode = ::offer, modifier = Modifier.fillMaxSize())
        Overlay(
            access = access,
            installCommand = installCommand,
            copied = copied,
            status = status,
            isClaiming = isClaiming,
            copy = {
                copyText(context, installCommand)
                copied = true
                scope.launch {
                    delay(2.seconds)
                    copied = false
                }
            },
            openSettings = { Camera.openSystemSettings(context) },
            dismiss = dismiss,
        )
    }
}

@Composable
private fun Overlay(
    access: CameraAccess?,
    installCommand: String,
    copied: Boolean,
    status: String,
    isClaiming: Boolean,
    copy: () -> Unit,
    openSettings: () -> Unit,
    dismiss: () -> Unit,
) {
    val safe = safeArea()
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = safe.top, bottom = safe.bottom)
            .padding(horizontal = Theme.Space.page, vertical = Theme.Space.medium),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.medium),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            // The chip's tinted fill vanishes over a camera, so this one carries the surface itself.
            Button(
                onClick = dismiss,
                Modifier
                    .heightIn(min = Theme.Touch.minimum)
                    .background(Theme.surface, CapsuleShape)
                    .padding(horizontal = Theme.Space.medium)
                    .testTag("scan.cancel"),
            ) {
                Text(L10n.string("Cancel"), style = Theme.Text.meta, color = Theme.ink)
            }
        }
        Steps(installCommand, copied, copy)
        if (access == CameraAccess.denied) Refused(openSettings)
        Spacer(Modifier.weight(1f))
        if (status.isNotEmpty()) Strip(status, isClaiming)
    }
}

/**
 * In place of the viewfinder: the same one line any denied permission gets, and the one thing
 * left to do about it. The scanner never pretends to scan over a black frame.
 */
@Composable
private fun Refused(openSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        Text(
            L10n.string("Allow camera access in Settings, or type the code"),
            Modifier.testTag("scan.cameraRefused"),
            style = SystemFont.subheadline,
            color = Theme.ink,
        )
        Button(onClick = openSettings, Modifier.testTag("scan.openSettings"), style = ChipButtonStyle) {
            Text(L10n.string("Open iOS Settings"))
        }
    }
}

@Composable
private fun Steps(installCommand: String, copied: Boolean, copy: () -> Unit) {
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        Text(L10n.string("Pair with Remote Control"), style = Theme.Text.title, color = Theme.ink)
        Text(L10n.string("On the host you want to connect to, run:"), style = SystemFont.subheadline, color = Theme.inkSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(breakingAfterSlashes(installCommand), Modifier.testTag("scan.command"), style = Theme.mono, color = Theme.ink)
            }
            CopyButton(copied, "scan.copy", copy)
        }
        Text(L10n.string("Point this camera at the QR code it prints."), style = SystemFont.subheadline, color = Theme.inkSecondary)
    }
}

@Composable
private fun Strip(status: String, isClaiming: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, ContinuousShape(Theme.Radius.control))
            .padding(Theme.Space.small),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isClaiming) ActivityIndicator()
        Text(status, Modifier.testTag("scan.status"), style = SystemFont.footnote, color = Theme.ink)
    }
}

/** A token the gateway has forgotten and a token already claimed are the only two the person can do anything about, and each says what to do. */
private fun message(error: Throwable): String {
    val expired = L10n.string("This code has expired. Run the command again on the host.")
    val used = L10n.string("This code was already used.")
    if (error is TransportError.Http) {
        when (error.status) {
            404, 410 -> return expired
            409 -> return used
        }
    }
    if (error is GatewayErrorBody) {
        if (error.code == GatewayErrorCode.notFound) return expired
        if (error.code == GatewayErrorCode.conflict) return used
    }
    return L10n.string("Could not claim that code.")
}
