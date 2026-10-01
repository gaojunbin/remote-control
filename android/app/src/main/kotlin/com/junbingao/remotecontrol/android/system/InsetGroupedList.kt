package com.junbingao.remotecontrol.android.system

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.scrollIndicator

/** A row's look inside an inset grouped list: `listRowInsets`, `listRowBackground`, `listRowSeparator`. */
data class RowStyle(
    val insets: PaddingValues = ListMetrics.defaultInsets,
    /** The cell's surface; transparent puts the row on the page, as `listRowBackground(.clear)` does. */
    val background: Color = Color.Unspecified,
    /** Whether the line under this row is drawn; never after a section's last row. */
    val separator: Boolean = true,
    /** Where the line starts, in from the card's leading edge; the row's leading inset by default. */
    val separatorInset: Dp? = null,
) {
    /** For the styles the design system names, such as `RowStyle.settings`. */
    companion object
}

/** What a list is built from: sections of rows on cards, and items on the page between them. */
interface GroupedListScope {
    /** A section: an optional header on the page, its rows on one card, an optional footer. */
    fun section(
        key: Any? = null,
        header: (@Composable () -> Unit)? = null,
        footer: (@Composable () -> Unit)? = null,
        rows: SectionScope.() -> Unit,
    )

    /** Something on the page itself, outside any card: a legend, a summary line. */
    fun item(key: Any? = null, content: @Composable () -> Unit)
}

/** The rows of one section. */
interface SectionScope {
    /**
     * One row. [onClick] makes the whole row the target, as a row that is a `Button` does;
     * [swipeActions] (edge first) and [contextMenu] are the row's `.swipeActions` and `.contextMenu`.
     */
    fun row(
        key: Any? = null,
        style: RowStyle = RowStyle(),
        onClick: (() -> Unit)? = null,
        swipeActions: List<SwipeAction> = emptyList(),
        contextMenu: List<MenuItem> = emptyList(),
        tag: String? = null,
        content: @Composable () -> Unit,
    )
}

/**
 * `List { … }.listStyle(.insetGrouped)` as iOS 26 draws it: sections on white cards 16 points in
 * from the screen's edges with 26-point continuous corners, headers and footers on the page,
 * hairline separators between rows that start at the row's text and run to the card's edge, and
 * none after a section's last row. It scrolls as one lazy column; [contentPadding] leaves room
 * for the bars that float over it.
 */
@Composable
fun InsetGroupedList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: GroupedListScope.() -> Unit,
) {
    // Rebuilt on every pass, as SwiftUI re-reads a List's body: the rows read the screen's state.
    val sections = ListBuilder().apply(content).blocks
    LazyColumn(modifier.scrollIndicator(state), state = state, contentPadding = contentPadding) {
        var first = true
        var afterFooter = false
        for (block in sections) {
            when (block) {
                is Block.Free -> item(block.key) { block.content() }
                is Block.Section -> {
                    emitSection(block, first, afterFooter)
                    first = false
                    afterFooter = block.footer != null
                }
            }
        }
        item { Spacer(Modifier.height(ListMetrics.bottomPadding)) }
    }
}

private fun LazyListScope.emitSection(section: Block.Section, first: Boolean, afterFooter: Boolean) {
    val header = section.header
    val gap = when {
        first -> ListMetrics.firstSectionTop
        header == null -> ListMetrics.sectionGap
        afterFooter -> ListMetrics.headerTopAfterFooter
        else -> ListMetrics.headerTop
    }
    item(section.key?.let { "$it.top" }) { Spacer(Modifier.height(gap)) }
    if (header != null) {
        item(section.key?.let { "$it.header" }) {
            Box(
                Modifier
                    .padding(start = ListMetrics.margin + ListMetrics.headerInset, end = ListMetrics.margin + ListMetrics.headerInset, bottom = ListMetrics.headerBottom)
                    .heightIn(min = ListMetrics.headerLine),
                contentAlignment = Alignment.CenterStart,
            ) {
                header()
            }
        }
    }
    val rows = section.rows
    rows.forEachIndexed { index, row ->
        item(row.key) {
            val corners = when {
                rows.size == 1 -> ContinuousShape.Corners.all
                index == 0 -> ContinuousShape.Corners.top
                index == rows.lastIndex -> ContinuousShape.Corners.bottom
                else -> ContinuousShape.Corners.none
            }
            ListRow(row, ContinuousShape(ListMetrics.corner, corners), separator = row.style.separator && index < rows.lastIndex)
        }
    }
    val footer = section.footer
    if (footer != null) {
        item(section.key?.let { "$it.footer" }) {
            Box(Modifier.padding(start = ListMetrics.margin + ListMetrics.headerInset, end = ListMetrics.margin + ListMetrics.headerInset, top = ListMetrics.footerTop)) {
                footer()
            }
        }
    }
}

@Composable
private fun ListRow(row: Row, shape: ContinuousShape, separator: Boolean) {
    val background = if (row.style.background == Color.Unspecified) Theme.surface else row.style.background
    val line = SystemColor.separator
    val leading = row.style.separatorInset ?: row.style.insets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    Box(Modifier.padding(horizontal = ListMetrics.margin)) {
        SwipeActions(row.swipeActions) {
            // A column, so a row of a title and a line under it needs no container of its own.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(background)
                    .then(if (row.contextMenu.isNotEmpty()) Modifier.contextMenu(row.contextMenu, row.onClick) else Modifier)
                    .then(
                        if (row.onClick != null && row.contextMenu.isEmpty()) {
                            Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = row.onClick)
                        } else {
                            Modifier
                        },
                    )
                    .then(if (row.tag != null) Modifier.testTag(row.tag) else Modifier)
                    .drawWithContent {
                        drawContent()
                        if (separator) {
                            val y = size.height - 0.33.dp.toPx() / 2
                            drawLine(line, Offset(leading.toPx(), y), Offset(size.width, y), strokeWidth = 0.33.dp.toPx())
                        }
                    }
                    .padding(row.style.insets),
            ) { row.content() }
        }
    }
}

private sealed interface Block {
    class Free(val key: Any?, val content: @Composable () -> Unit) : Block
    class Section(
        val key: Any?,
        val header: (@Composable () -> Unit)?,
        val footer: (@Composable () -> Unit)?,
        val rows: List<Row>,
    ) : Block
}

private class Row(
    val key: Any?,
    val style: RowStyle,
    val onClick: (() -> Unit)?,
    val swipeActions: List<SwipeAction>,
    val contextMenu: List<MenuItem>,
    val tag: String?,
    val content: @Composable () -> Unit,
)

private class ListBuilder : GroupedListScope {
    val blocks = mutableListOf<Block>()

    override fun section(
        key: Any?,
        header: (@Composable () -> Unit)?,
        footer: (@Composable () -> Unit)?,
        rows: SectionScope.() -> Unit,
    ) {
        val collected = mutableListOf<Row>()
        object : SectionScope {
            override fun row(
                key: Any?,
                style: RowStyle,
                onClick: (() -> Unit)?,
                swipeActions: List<SwipeAction>,
                contextMenu: List<MenuItem>,
                tag: String?,
                content: @Composable () -> Unit,
            ) {
                collected += Row(key, style, onClick, swipeActions, contextMenu, tag, content)
            }
        }.rows()
        blocks += Block.Section(key, header, footer, collected)
    }

    override fun item(key: Any?, content: @Composable () -> Unit) {
        blocks += Block.Free(key, content)
    }
}

/** The list's measurements, from the iPhone 17 reference screenshots. */
object ListMetrics {
    val margin = 16.dp
    val corner = 26.dp

    /** A cell's own insets when the screen sets none: UIKit's 20 at the sides. */
    val defaultInsets = PaddingValues(start = 20.dp, top = 11.dp, end = 20.dp, bottom = 11.dp)

    /** A header's words stand at the rows' text, 16 in from the card. */
    val headerInset = 16.dp
    val headerBottom = 10.dp

    /**
     * A header's line: a little taller than its footnote's own box, so a header and the gaps
     * round it come to the iPhone's 53 points between one card and the next.
     */
    val headerLine = 16.dp
    val footerTop = 7.dp

    /** From the bar (or the large title) to the first card. */
    val firstSectionTop = 10.dp

    /** From a card to the next section's header, and to the next card where there is none. */
    val headerTop = 27.dp

    /** From a footer to the next section's header: the footer has already spaced the card off. */
    val headerTopAfterFooter = 16.dp
    val sectionGap = 20.dp
    val bottomPadding = 20.dp
}
