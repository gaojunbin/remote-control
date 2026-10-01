package com.junbingao.remotecontrol.win.devices.adddevice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.FirstTextBaseline
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.devices.TextMeasure
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.pair-scan` (A23): the second way in, under the code flow rather than beside it — the bare
 * installer to run on the host, and what to do with the QR code it prints. The app has no
 * pairing-link screen, so the sentence sends the link to the phone app or a browser
 * (`docs/DESIGN.md` § "The Windows app", after the Mac's).
 */
@Composable
internal fun PairScanSection(pairing: AddDevicePairing, command: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    VStack(modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
        Text(S.pairing.scanTitle, css(FontSize.fs13, weight = FontWeight.SemiBold), Modifier.padding(top = Space.sp4, bottom = Space.sp3))
        HStack(
            Modifier.fillMaxWidth().pairingBox(Palette.surfaceSunken).padding(vertical = Space.sp3, horizontal = Space.sp4),
            spacing = Space.sp3,
            alignment = Alignment.Top,
        ) {
            PairCommandText(command, Modifier.weight(1f))
            Btn(if (pairing.copied == AddDevicePairing.Copied.scan) S.common.copied else S.common.copy, size = ButtonSize.small) {
                pairing.copy(command, AddDevicePairing.Copied.scan, scope)
            }
        }
        Hint(S.win.pairingScanBody, Modifier.padding(top = Space.sp3).widthIn(max = TextMeasure.ch(FontSize.fs13) * 52))
    }
}

/**
 * "No curl on the host?" and the steps it reveals: install the client with pip, then pair it with
 * the code on screen.
 */
@Composable
internal fun ManualInstall(pairing: AddDevicePairing, origin: String, modifier: Modifier = Modifier) {
    VStack(modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        HStack(spacing = 0.dp, alignment = Alignment.FirstTextBaseline) {
            Text("${S.pairing.noCurl} ", css(FontSize.fs13), color = Palette.inkSecondary)
            PairLink(S.pairing.manualInstall, size = FontSize.fs13) { pairing.manual = !pairing.manual }
        }
        if (pairing.manual) {
            VStack(Modifier.padding(top = Space.sp3), spacing = 6.dp, alignment = Alignment.Start) {
                S.pairing.manualSteps.forEachIndexed { index, line ->
                    ManualStep(number = index + 1) { step ->
                        Text(line, css(FontSize.fs13), step, color = Palette.inkSecondary)
                    }
                }
                ManualStep(number = S.pairing.manualSteps.size + 1) { step ->
                    SelectableText(
                        TextMeasure.breakAll(S.pairing.manualPairCommand(origin, pairing.grant?.code ?: "RC-XXXX-XXXX")),
                        css(FontSize.fs12, mono = true),
                        step.background(Palette.surfaceMuted, RoundedCornerShape(6.dp)).padding(vertical = 2.dp, horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * One item of `ol.pair-manual-steps`: its number hung in the list's 20 px gutter, as a browser
 * draws an outside marker, and the step after it, which takes the modifier that gives it the rest
 * of the line.
 */
@Composable
private fun ManualStep(number: Int, content: @Composable (Modifier) -> Unit) {
    HStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.FirstTextBaseline) {
        // The marker keeps its trailing space at the gutter's edge, as text aligned there would not.
        Box(Modifier.width(Space.sp5), contentAlignment = Alignment.CenterEnd) {
            Text("$number. ", css(FontSize.fs13), color = Palette.inkSecondary)
        }
        content(Modifier.weight(1f, fill = false))
    }
}
