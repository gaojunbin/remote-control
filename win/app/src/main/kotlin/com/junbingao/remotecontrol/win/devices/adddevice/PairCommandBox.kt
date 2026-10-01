package com.junbingao.remotecontrol.win.devices.adddevice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.devices.TextMeasure
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.pair-command`: the full one-liner in a sunken mono block with Copy beside it, and under a rule
 * the pairing code, marked single use, with its countdown — or "expired" and New code once it has
 * run out.
 */
@Composable
internal fun PairCommandBox(
    pairing: AddDevicePairing,
    /** Now, on the gateway's clock: `expires_at` is a gateway timestamp. */
    now: Long,
    newCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    VStack(modifier.fillMaxWidth().pairingBox(Palette.surfaceSunken), spacing = 0.dp) {
        HStack(Modifier.fillMaxWidth().padding(Space.sp4), spacing = Space.sp3, alignment = Alignment.Top) {
            PairCommandText(pairing.command.ifEmpty { " " }, Modifier.weight(1f))
            Disabled(pairing.command.isEmpty()) {
                Btn(if (pairing.copied == AddDevicePairing.Copied.code) S.common.copied else S.common.copy, size = ButtonSize.small) {
                    pairing.copy(pairing.command, AddDevicePairing.Copied.code, scope)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
        Foot(
            pairing, now, newCode,
            Modifier.fillMaxWidth().background(Palette.surface).padding(vertical = 10.dp, horizontal = Space.sp4),
        )
    }
}

@Composable
private fun Foot(pairing: AddDevicePairing, now: Long, newCode: () -> Unit, modifier: Modifier) {
    val expired = pairing.hasExpired(now)
    val left = if (expired) S.pairing.expired else S.pairing.expiresIn(Format.clock(pairing.remaining(now).toDouble()))
    HStack(modifier, spacing = Space.sp3) {
        Text(pairing.grant?.code ?: "—", css(FontSize.fs12, mono = true, tracking = 0.02f))
        Box(Modifier.size(width = 1.dp, height = 12.dp).background(Palette.lineStrong))
        Text("${S.pairing.singleUse} · $left", css(FontSize.fs12), color = Palette.inkSecondary)
        if (expired) PairLink(S.pairing.newCode, size = FontSize.fs12, action = newCode)
    }
}

/**
 * A one-liner as the modal prints it: 13 px mono on 1.7 lines, breaking anywhere, and selectable
 * so a part of it can be copied too.
 */
@Composable
internal fun PairCommandText(text: String, modifier: Modifier = Modifier) {
    SelectableText(TextMeasure.breakAll(text), css(FontSize.fs13, lineHeight = 1.7f, mono = true), modifier)
}

/** `.link-btn`: a button drawn as underlined text in the ink, at the size of the line it sits in. */
@Composable
internal fun PairLink(title: String, size: Float, action: () -> Unit) {
    Button(action) {
        Text(AnnotatedString(title, SpanStyle(textDecoration = TextDecoration.Underline)), css(size), color = Palette.ink)
    }
}

/**
 * The bordered blocks of the pairing modal: a 1 px `--line` edge on a 12 px radius, the fill
 * inside it, and what they hold clipped to it.
 */
internal fun Modifier.pairingBox(fill: Color): Modifier {
    val shape = RoundedCornerShape(Radius.md)
    return clip(shape).background(fill).border(1.dp, Palette.line, shape).padding(1.dp)
}
