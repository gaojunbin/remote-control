package com.junbingao.remotecontrol.win.devices.page

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AgentLimit
import com.junbingao.remotecontrol.win.design.CSSLine
import com.junbingao.remotecontrol.win.design.FirstTextBaseline
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.S

/**
 * `QuotaMeter.tsx`: one rate-limit window — its name, the percentage and when it resets on one
 * line, and under both a 4 px meter filled to the used share (`docs/DESIGN.md` § "Quota is a meter,
 * drawn for accounts only").
 */
@Composable
internal fun QuotaMeter(limit: AgentLimit) {
    val name = AccountWords.windowName(limit)
    val used = AccountWords.usedPercent(limit)
    VStack(Modifier.fillMaxWidth(), spacing = 8.dp, alignment = Alignment.Start) {
        HStack(Modifier.fillMaxWidth(), spacing = Space.sp2, alignment = Alignment.FirstTextBaseline) {
            Text(name, css(FontSize.fs13), color = Palette.ink)
            Spacer(Modifier.weight(1f))
            // `font-variant-numeric: tabular-nums`.
            Text(AnnotatedString(S.devicePage.percent(used), SpanStyle(fontFeatureSettings = "tnum")), css(FontSize.fs12), color = Palette.inkSecondary)
            val resetsAt = limit.resetsAt
            if (resetsAt != null) {
                CSSLine(css(FontSize.fs12)) {
                    HStack(spacing = Space.sp2) {
                        Text("·", color = Palette.lineStrong)
                        Text(AccountWords.resetsText(resetsAt), color = Palette.inkTertiary)
                    }
                }
            }
        }
        MeterTrack(
            fraction = used / 100.0,
            tone = AccountWords.meterTone(limit.usedPercent),
            modifier = Modifier.semantics {
                contentDescription = S.devicePage.usage(name)
                stateDescription = S.devicePage.percent(used)
            },
        )
    }
}

/**
 * `.device-page-meter-track`: the ink colour until the window is nearly spent; no other colour
 * appears on the page.
 */
@Composable
private fun MeterTrack(fraction: Double, tone: AccountWords.MeterTone, modifier: Modifier) {
    val shown by animateFloatAsState(fraction.toFloat(), Motion.ease(Motion.dur, LocalReduceMotion.current))
    val fill = when (tone) {
        AccountWords.MeterTone.ink -> Palette.ink
        AccountWords.MeterTone.warn -> Palette.attention
        AccountWords.MeterTone.danger -> Palette.danger
    }
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Palette.surfaceMuted)) {
        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircleShape).background(fill))
    }
}
