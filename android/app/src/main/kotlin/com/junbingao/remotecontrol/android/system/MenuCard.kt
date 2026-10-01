package com.junbingao.remotecontrol.android.system

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.Divider
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf

/**
 * The menu itself, as iOS 26 draws it: a glass panel 250 points wide with deeply rounded corners,
 * rows 42 points apart in the body size, a destructive row in red, and a section's header in the
 * secondary ink. Where the words start depends on what the rows carry ([MenuColumns]).
 */
@Composable
internal fun MenuCard(items: List<MenuItem>, close: () -> Unit) {
    val columns = MenuColumns.of(items)
    Column(
        Modifier
            .width(MenuMetrics.width)
            .heightIn(max = MenuMetrics.maxHeight)
            .glassPanel(ContinuousShape(MenuMetrics.corner))
            .verticalScroll(rememberScrollState())
            .padding(vertical = MenuMetrics.verticalPadding),
    ) {
        MenuRows(items, columns, close, top = true)
    }
}

/**
 * The leading columns of a menu's rows, which iOS 26 decides for the whole menu by what any row
 * carries: rows with images (a `Label`) put the image in a column of its own and the words after
 * it, a picker's rows keep a narrower column for the checkmark, and words alone start near the
 * edge. Measured on the iPhone 17: the attach menu (images), the language and permission menus
 * (a picker).
 */
internal enum class MenuColumns(val glyphCentre: Dp, val textInset: Dp) {
    images(40.dp, 64.dp),
    checks(27.5.dp, 44.dp),
    plain(0.dp, 20.dp);

    companion object {
        fun of(items: List<MenuItem>): MenuColumns {
            val actions = actions(items)
            return when {
                actions.any { it.symbol != null || it.image != null } -> images
                actions.any { it.checked } -> checks
                else -> plain
            }
        }

        private fun actions(items: List<MenuItem>): List<MenuItem.Action> = items.flatMap { item ->
            when (item) {
                is MenuItem.Action -> listOf(item)
                is MenuItem.Section -> actions(item.items)
                MenuItem.Divider -> emptyList()
            }
        }
    }
}

@Composable
private fun MenuRows(items: List<MenuItem>, columns: MenuColumns, close: () -> Unit, top: Boolean) {
    items.forEachIndexed { index, item ->
        when (item) {
            is MenuItem.Action -> MenuRow(item, columns, close)
            is MenuItem.Divider -> MenuSeparator()
            is MenuItem.Section -> {
                if (!(top && index == 0)) MenuSeparator()
                if (item.title != null) {
                    Text(
                        item.title,
                        Modifier.padding(start = columns.textInset, end = 16.dp, top = 6.dp, bottom = 4.dp),
                        style = SystemFont.footnote,
                        color = SystemColor.secondaryLabel,
                    )
                }
                MenuRows(item.items, columns, close, top = false)
            }
        }
    }
}

@Composable
private fun MenuSeparator() {
    Divider(Modifier.padding(vertical = 6.dp), color = SystemColor.separator.copy(alpha = 0.18f))
}

@Composable
private fun MenuRow(item: MenuItem.Action, columns: MenuColumns, close: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = when {
        !item.enabled -> SystemColor.tertiaryLabel
        item.role == ActionRole.destructive -> AlertMetrics.destructive
        else -> Theme.ink
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(MenuMetrics.rowHeight)
            .padding(horizontal = MenuMetrics.highlightInset)
            .background(if (pressed) Glass.selection else Color.Transparent, ContinuousShape(12.dp))
            .clickable(interaction, indication = null, enabled = item.enabled, role = Role.Button) {
                close()
                item.action()
            }
            .semantics { if (item.checked) selected = true }
            .then(if (item.tag != null) Modifier.testTag(item.tag) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (columns != MenuColumns.plain) {
            // A box twice as wide as the column's centre is from the row's edge, so whatever it
            // centres stands on that centre.
            Box(Modifier.width((columns.glyphCentre - MenuMetrics.highlightInset) * 2), contentAlignment = Alignment.Center) {
                MenuGlyph(item, columns, ink)
            }
        }
        Text(
            item.title,
            Modifier.padding(start = columns.textInset - MenuMetrics.highlightInset, end = 16.dp),
            style = SystemFont.body,
            color = ink,
            lineLimit = 1,
        )
    }
}

/**
 * The row's leading mark: in a picker the checkmark, smaller and heavier than the words; in a
 * menu of images the row's own image, or the checkmark where a row spends its image on one.
 */
@Composable
private fun MenuGlyph(item: MenuItem.Action, columns: MenuColumns, ink: Color) {
    when {
        columns == MenuColumns.checks -> if (item.checked) {
            Icon(Sf.checkmark, side = MenuMetrics.checkSide, weight = FontWeight.Bold, tint = ink)
        }
        item.checked -> Icon(Sf.checkmark, font = SystemFont.body, tint = ink)
        item.symbol != null -> Icon(item.symbol, font = SystemFont.body, tint = ink)
        item.image != null -> Image(
            item.image,
            contentDescription = null,
            colorFilter = ColorFilter.tint(ink),
            modifier = Modifier.size(Theme.Mark.control),
        )
    }
}

/** A menu's measurements, from the iPhone 17 reference screenshots. */
object MenuMetrics {
    val width = 250.dp
    val corner = 34.dp
    val rowHeight = 42.dp
    val verticalPadding = 10.dp
    val maxHeight = 480.dp

    /** How far in from the card's edges a pressed row's highlight is drawn. */
    val highlightInset = 8.dp

    /** The box a picker's checkmark is drawn in: 10.7 by 10 points of ink, as the iPhone's. */
    val checkSide = 15.9.dp

    /** The nearest a menu comes to the safe area's edges. */
    val margin = 8.dp

    /** How far a context menu stands from the element it was opened from. */
    val contextGap = 8.dp
}
