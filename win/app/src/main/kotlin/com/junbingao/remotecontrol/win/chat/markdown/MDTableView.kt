package com.junbingao.remotecontrol.win.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.chat.support.chatBox
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.TextStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * `.md-table` and `.md table`: a bordered, 8-point-radius box holding a full-width table of
 * 13-point cells, 7 by 12 points of padding, a rule under every row but the last, the header on
 * the sunken surface in 500 weight.
 */
@Composable
fun MDTableView(table: MDTable, modifier: Modifier = Modifier) {
    val columns = max(1, table.columns)
    MDTableLayout(columns, modifier.fillMaxWidth().chatBox(radius = Radius.sm)) {
        for ((cell, ruled) in cells(table, columns)) MDTableCell(cell, ruled)
    }
}

/** Row by row, every row padded out to the table's columns, as the browser adds anonymous cells. */
private fun cells(table: MDTable, columns: Int): List<Pair<MDCell?, Boolean>> {
    val out = mutableListOf<Pair<MDCell?, Boolean>>()
    val rows = (if (table.header.isEmpty()) emptyList() else listOf(table.header)) + table.rows
    for ((index, row) in rows.withIndex()) {
        val header = table.header.isNotEmpty() && index == 0
        // `.md tr:last-child td` drops the rule; a header row keeps it.
        val ruled = header || index < rows.size - 1
        for (column in 0 until columns) out += row.getOrNull(column) to ruled
    }
    return out
}

@Composable
private fun MDTableCell(cell: MDCell?, ruled: Boolean) {
    val header = cell?.header ?: false
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { if (ruled) drawRect(Palette.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .background(if (header) Palette.surfaceSunken else Color.Transparent)
            .padding(bottom = if (ruled) 1.dp else 0.dp),
        contentAlignment = frameAlignment(cell?.align ?: MDCell.Align.leading),
    ) {
        if (cell != null) {
            val weight = if (cell.header) MDWeight.medium else MDWeight.regular
            MDRichText(
                cell.inlines,
                TextStyle(size = FontSize.fs13, weight = weight.fontWeight, lineHeight = 1.65f),
                weight,
                Palette.ink,
                alignment = textAlignment(cell.align),
                modifier = Modifier.padding(vertical = 7.dp, horizontal = Space.sp3).fillMaxWidth(),
            )
        } else {
            Box(Modifier.padding(vertical = 7.dp, horizontal = Space.sp3))
        }
    }
}

private fun textAlignment(align: MDCell.Align): TextAlign = when (align) {
    MDCell.Align.leading -> TextAlign.Start
    MDCell.Align.center -> TextAlign.Center
    MDCell.Align.trailing -> TextAlign.End
}

private fun frameAlignment(align: MDCell.Align): Alignment = when (align) {
    MDCell.Align.leading -> Alignment.CenterStart
    MDCell.Align.center -> Alignment.Center
    MDCell.Align.trailing -> Alignment.CenterEnd
}

/**
 * The browser's automatic table layout, for a table 100% wide: a column is as wide as its widest
 * cell on one line, and the room left over is shared out in proportion to those widths; a table
 * too narrow for that squeezes each column towards the least its cells can wrap to. Every cell of
 * a row is as tall as the tallest, its content centred in it.
 */
@Composable
fun MDTableLayout(columns: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val widths = columnWidths(columns, if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else null, measurables)
        // The columns' edges on whole pixels, from their exact places, so the table ends where its box does.
        val edges = IntArray(columns + 1)
        var x = 0f
        for (column in 0 until columns) {
            edges[column] = x.roundToInt()
            x += widths[column]
        }
        edges[columns] = x.roundToInt()
        val rows = (measurables.size + columns - 1) / columns
        val heights = IntArray(rows) { row ->
            (0 until columns).maxOf { column ->
                measurables.getOrNull(row * columns + column)?.minIntrinsicHeight(edges[column + 1] - edges[column]) ?: 0
            }
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            val column = index % columns
            measurable.measure(Constraints.fixed(edges[column + 1] - edges[column], heights[index / columns]))
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else edges[columns]
        layout(width, heights.sum()) {
            var y = 0
            for (row in 0 until rows) {
                for (column in 0 until columns) placeables.getOrNull(row * columns + column)?.place(edges[column], y)
                y += heights[row]
            }
        }
    }
}

/** The narrowest a cell's content can wrap to, with its padding: a word breaks anywhere in `.md`, so that is about a character. */
private const val MINIMUM_CELL = 24f + 8f

private fun Density.columnWidths(columns: Int, available: Float?, measurables: List<Measurable>): List<Float> {
    val most = FloatArray(columns)
    for ((index, measurable) in measurables.withIndex()) {
        val column = index % columns
        most[column] = max(most[column], measurable.maxIntrinsicWidth(Constraints.Infinity).toFloat())
    }
    if (available == null) return most.toList()
    val least = MINIMUM_CELL * density
    val total = most.sum()
    if (total <= available && total > 0) return most.map { it + (available - total) * it / total }
    val floor = least * columns
    if (available <= floor || total <= floor) return List(columns) { least }
    val share = (available - floor) / (total - floor)
    return most.map { least + (it - least) * share }
}
