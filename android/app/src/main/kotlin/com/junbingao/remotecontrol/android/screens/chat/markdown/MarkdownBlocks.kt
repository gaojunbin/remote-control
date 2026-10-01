package com.junbingao.remotecontrol.android.screens.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.LocalFont
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.monospacedDigit
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.markdown.MarkdownVisualKind
import com.junbingao.remotecontrol.android.markdown.MarkdownVisualView
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.markdown.MarkdownBlock
import com.junbingao.remotecontrol.core.markdown.MarkdownMath

@Composable
fun MarkdownBlockView(block: MarkdownBlock) {
    when (val content = block.content) {
        is MarkdownBlock.Content.Paragraph -> MarkdownInlineView(content.text)
        is MarkdownBlock.Content.Heading -> {
            val font = when (content.level) {
                1 -> SystemFont.title2.weight(FontWeight.Bold)
                2 -> SystemFont.title3.weight(FontWeight.SemiBold)
                else -> SystemFont.headline
            }
            MarkdownInlineView(
                content.text,
                Modifier
                    .padding(top = if (content.level <= 2) 5.dp else 2.dp)
                    .semantics { heading() },
                font = font,
            )
        }
        is MarkdownBlock.Content.Code -> MarkdownCodeCard(content.language, content.source, content.closed)
        is MarkdownBlock.Content.Quote -> MarkdownQuote(content.blocks)
        is MarkdownBlock.Content.List -> MarkdownList(content)
        is MarkdownBlock.Content.Table -> MarkdownTableView(content.table)
        MarkdownBlock.Content.ThematicBreak ->
            Box(Modifier.padding(vertical = 5.dp).fillMaxWidth().height(0.5.dp).background(SystemColor.separator))
        is MarkdownBlock.Content.Math -> MarkdownCodeCard("math", content.text, closed = true)
    }
}

/**
 * A paragraph of inline Markdown: native text, or the renderer when it holds a formula. The lines
 * stand five points further apart than the font's own, as the iPhone's `.lineSpacing(5)` sets them.
 */
@Composable
fun MarkdownInlineView(text: String, modifier: Modifier = Modifier, font: TextStyle = LocalFont.current) {
    val hasMath = remember(text) { MarkdownMath.spans(text).any { it.isMath } }
    val open = LocalMarkdownLink.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        if (hasMath) {
            MarkdownVisualView(MarkdownVisualKind.inlineMath, text, inlineRuns = ::markdownRuns, onLink = { open(it.toString()) })
        } else {
            val link = Theme.accent
            val attributed = remember(text, link, open) { markdownAttributed(text, link, open) }
            Text(attributed, style = font.spaced(MarkdownMetrics.lineSpacing))
        }
    }
}

/** The left bar and the quotation beside it, in the secondary ink. */
@Composable
private fun MarkdownQuote(blocks: List<MarkdownBlock>) {
    val bar = Theme.accent.copy(alpha = 0.55f)
    Row(
        Modifier
            .padding(vertical = 4.dp)
            .height(IntrinsicSize.Min)
            .semantics { contentDescription = L10n.string("Quotation") },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(bar, ContinuousShape(2.dp)))
        Foreground(SystemColor.secondaryLabel) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (child in blocks) key(child.id) { MarkdownBlockView(child) }
            }
        }
    }
}

@Composable
private fun MarkdownList(list: MarkdownBlock.Content.List) {
    val accent = Theme.accent
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (item in list.items) {
            key(item.id) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val checked = item.checked
                    val marker = Modifier.alignByBaseline()
                    if (checked != null) {
                        // A symbol stands on the text's baseline a little above its own foot.
                        Icon(
                            if (checked) Sf.checkmarkCircleFill else Sf.circle,
                            Modifier.alignBy { (it.measuredHeight * 0.85f).toInt() },
                            tint = if (checked) accent else SystemColor.secondaryLabel,
                            contentDescription = L10n.string(if (checked) "Completed" else "Not completed"),
                        )
                    } else {
                        Text(
                            if (list.ordered) "${item.number ?: 1}." else "•",
                            marker
                                .widthIn(min = if (list.ordered) 22.dp else 10.dp)
                                .semantics { hideFromAccessibility() },
                            style = LocalFont.current.monospacedDigit(),
                            color = SystemColor.secondaryLabel,
                            alignment = TextAlign.End,
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .alignByBaseline(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (child in item.blocks) key(child.id) { MarkdownBlockView(child) }
                    }
                }
            }
        }
    }
}

internal object MarkdownMetrics {
    /** `.lineSpacing(5)`: added to the font's own line, which SwiftUI sets SF's 1.19 of the size apart. */
    const val lineSpacing = 5f

    /** SF's own line height, in ems: its ascent and descent. */
    const val naturalLine = 1.193f
}

/** A style whose lines stand [spacing] points further apart than SF's own line. */
internal fun TextStyle.spaced(spacing: Float): TextStyle {
    val size = fontSize
    if (!size.isSp) return this
    return copy(lineHeight = (size.value * MarkdownMetrics.naturalLine + spacing).sp)
}
