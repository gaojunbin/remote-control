package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Help
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.shared.LabelPair
import com.junbingao.remotecontrol.win.strings.S

/**
 * `labelOf` in `Composer.tsx`: the label an agent's list gives an id, or the id itself. The
 * agent's own ids need not appear in its lists — `auto` is a real Claude permission mode the device
 * does not advertise — so an unknown one is shown as it arrived (A17).
 */
object ComposerLabels {
    fun label(options: List<AgentOption>, value: String?): String? {
        if (value.isNullOrEmpty()) return null
        return options.firstOrNull { it.id == value }?.label ?: value
    }

    /** "Opus 4.6 High": what runs, and how hard, in one line. */
    fun line(pair: LabelPair): String = listOfNotNull(pair.model, pair.effort).joinToString(" ")
}

/**
 * The model chip's contents: the lightning while a faster tier is on, then "<model> <effort>".
 * Every copy is drawn the same way, because the hidden ones are what give the chip its width.
 */
@Composable
fun ModelChipLabel(pair: LabelPair, glyph: Boolean, fallback: String = "") {
    HStack(spacing = 5.dp) {
        if (glyph) Icon(LucideIcon.zap, size = 12.dp, color = Palette.attention)
        Text(ComposerLabels.line(pair).ifEmpty { fallback }, css(FontSize.fs12), softWrap = false)
    }
}

/**
 * A17: one value a terminal chose, where its picker would be. It is the shape of the trigger beside
 * it, opens nothing, and carries the whole sentence for assistive technology, because on its own
 * "auto" says nothing about who set it. The caller draws none for a value the device has not seen.
 */
@Composable
fun TerminalSettingChip(name: String, text: String, speed: String?) {
    val shown = speed?.let { "$text · $it" } ?: text
    val sentence = S.composer.setInTerminal(name, shown)
    Help(sentence) {
        HStack(
            Modifier
                .height(26.dp)
                .background(Palette.surfaceMuted, RoundedCornerShape(percent = 50))
                .padding(horizontal = 11.dp)
                .clearAndSetSemantics { contentDescription = sentence },
            spacing = 4.dp,
        ) {
            WithForeground(Palette.inkSecondary) {
                if (speed != null) Icon(LucideIcon.zap, size = 12.dp, color = Palette.attention)
                Text(text, css(FontSize.fs12), softWrap = false)
            }
        }
    }
}
