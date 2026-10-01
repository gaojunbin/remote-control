package com.junbingao.remotecontrol.android.screens.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.markdown.MarkdownTable

/**
 * A table the width its columns want, scrolling sideways inside a rounded white box when that is
 * wider than the conversation, with a line under it that says so. Every column takes the cell's
 * ideal width, as the iPhone's grid of fixed-size cells does, and each cell carries its row's
 * tint and a hairline under it.
 */
@Composable
internal fun MarkdownTableView(table: MarkdownTable) {
    val box = RoundedCornerShape(MarkdownTableMetrics.corner)
    val header = Theme.accent.copy(alpha = 0.10f)
    val stripe = SystemColor.label.copy(alpha = 0.025f)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Theme.surface, box)
                .border(1.dp, SystemColor.label.copy(alpha = 0.09f), box),
        ) {
            SelectionContainer {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        table.headers.forEachIndexed { column, value -> Cell(value, table.alignments.getOrNull(column), header = true, fill = header, spoken = value) }
                    }
                    table.rows.forEachIndexed { index, row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            table.headers.indices.forEach { column ->
                                val value = row.getOrElse(column) { "" }
                                Cell(
                                    value,
                                    table.alignments.getOrNull(column),
                                    header = false,
                                    fill = if (index % 2 == 0) stripe else Color.Transparent,
                                    spoken = "${table.headers[column]}: $value",
                                )
                            }
                        }
                    }
                }
            }
        }
        Foreground(SystemColor.secondaryLabel) {
            Label(
                L10n.string("Scroll sideways to read the table"),
                Sf.arrowLeftAndRight,
                Modifier.clearAndSetSemantics { },
                font = SystemFont.caption2,
            )
        }
    }
}

@Composable
private fun Cell(value: String, alignment: MarkdownTable.Alignment?, header: Boolean, fill: Color, spoken: String) {
    val rule = SystemColor.label.copy(alpha = 0.07f)
    val link = Theme.accent
    val open = LocalMarkdownLink.current
    val attributed = remember(value, link, open) { markdownAttributed(value, link, open) }
    Box(
        Modifier
            .background(fill, RoundedCornerShape(MarkdownTableMetrics.corner))
            .drawBehind {
                val y = size.height - 0.25.dp.toPx()
                drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
            }
            .padding(horizontal = 12.dp, vertical = 11.dp)
            .width(MarkdownTableMetrics.cellWidth)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = when (alignment) {
            MarkdownTable.Alignment.center -> Alignment.Center
            MarkdownTable.Alignment.trailing -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        },
    ) {
        val font = if (header) SystemFont.subheadline.weight(FontWeight.SemiBold) else SystemFont.subheadline
        Text(attributed, style = font.spaced(3f), color = Theme.ink)
    }
}

internal object MarkdownTableMetrics {
    /** A cell's ideal width, which a fixed-size grid gives every column. */
    val cellWidth = 170.dp
    val corner = 10.dp
}
