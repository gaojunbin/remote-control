package com.junbingao.remotecontrol.win.devices

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.LocalRowIsHovered
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.RowHeight
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.delay

/** How long the row keeps saying why it opened nothing. */
private const val NOTE_MILLIS = 4_000L

/**
 * `web/src/features/devices/DeviceRow.tsx`: one registered device, as `docs/DESIGN.md` § "The
 * device row" rules it — a computer glyph at the leading edge, the name once, a status line, the
 * agents as logos alone, and a third line only while an update runs or has failed (A36).
 *
 * A38, rule 20: the row's own click opens a terminal on the machine. A device that is offline or
 * offers no shell says which of the two it is under its name and goes nowhere. Everything else is
 * the menu's, which sits above the row's target so none of its items navigates.
 */
@Composable
internal fun DeviceRow(
    device: Device,
    sessionCount: Int,
    /** A22: the build a retry would ask for, when the gateway serves one. */
    servedBuild: String?,
    /** A22: why this device's last `device.update` was refused outright. */
    updateError: String?,
    /** A preview stage's: the menu drawn open, or the refusal a click gets. */
    menuOpen: Boolean,
    refusesOnAppear: Boolean,
    onRename: () -> Unit,
    onRetryUpdate: () -> Unit,
    onRevoke: () -> Unit,
) {
    val model = LocalAppModel.current
    val layout = LocalLayoutClass.current
    var note by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableIntStateOf(0) }
    val compact = layout.maxWidth760
    val minHeight = if (!compact) RowHeight.rowH else if (layout.maxWidth480) RowHeight.rowHStackedTall else RowHeight.rowHStacked
    val notice = DeviceUpdateWords.notice(device, localError = updateError)

    // A38: the click opens a shell, or says why it cannot. `terminal` is absent on a client older
    // than the amendment, which is the same answer as false.
    fun open() {
        if (device.online && device.offersTerminal) {
            model.router.go(Route.Terminal(deviceId = device.deviceID))
            return
        }
        note = if (device.online) S.devices.noTerminal else S.devices.deviceOffline
        notes += 1
    }

    val menu: @Composable (Modifier) -> Unit = { modifier ->
        DeviceRowMenu(
            retryBlocked = DeviceUpdateWords.retryBlocked(device, servedBuild),
            failed = notice?.failed == true,
            initiallyOpen = menuOpen,
            onRename = onRename,
            onRetryUpdate = onRetryUpdate,
            onShowQuota = { model.router.go(Route.Device(id = device.deviceID)) },
            onRevoke = onRevoke,
            modifier = modifier,
        )
    }
    // The target under everything but the menu, as the web stretches the name's link over the row
    // and lifts the menu above it.
    Button(::open, Modifier.fillMaxWidth(), style = DeviceRowStyle(minHeight), accessibilityLabel = device.name) {
        if (compact) {
            // 760 and narrower: the agents take a line of their own, indented past the glyph so
            // they line up with the name.
            VStack(Modifier.padding(vertical = Space.sp3, horizontal = Space.sp4), spacing = Space.sp2, alignment = Alignment.Start) {
                HStack(spacing = Space.sp3) {
                    DeviceGlyph()
                    DeviceRowMain(device, sessionCount, notice, note, wide = false, modifier = Modifier.weight(1f))
                    // `margin-top: -2px` on a centred item lifts it by one point.
                    menu(Modifier.offset(y = (-1).dp))
                }
                DeviceAgentStrip(device.availableAgents, Modifier.padding(start = DeviceGlyph.side + Space.sp3))
            }
        } else {
            // Glyph, the machine, its agents and the menu on one line.
            HStack(Modifier.padding(horizontal = Space.sp5), spacing = Space.sp4) {
                DeviceGlyph()
                DeviceRowMain(device, sessionCount, notice, note, wide = true, modifier = Modifier.weight(1f))
                DeviceAgentStrip(device.availableAgents)
                menu(Modifier)
            }
        }
    }
    LaunchedEffect(notes) {
        if (notes == 0) return@LaunchedEffect
        delay(NOTE_MILLIS)
        note = null
    }
    LaunchedEffect(Unit) { if (refusesOnAppear) open() }
}

/** The row: as tall as `minHeight` at least, its content centred, and tinted under the pointer. */
private class DeviceRowStyle(val minHeight: Dp) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        // The tint eases in; the menu's dots light at once, as the web's do.
        val tint by animateColorAsState(
            if (configuration.isHovered) Palette.hover else Color.Transparent,
            Motion.ease(Motion.durFast, LocalReduceMotion.current),
        )
        ExactFrame(modifier.background(tint), minHeight = minHeight) {
            CompositionLocalProvider(LocalRowIsHovered provides configuration.isHovered) { configuration.label() }
        }
    }
}
