package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.FieldLabel
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.system.GroupedListScope
import com.junbingao.remotecontrol.android.system.RowStyle

/**
 * A group of settings rows: a caption on the canvas, and the rows on one soft surface with nothing
 * but spacing between them.
 *
 * `docs/DESIGN.md` § "The Settings screen". The rows are one list cell, not one cell each, as on
 * the iPhone, where `List` draws a separator of its own between two cells under conditions no
 * `listRowSeparator` reaches and a surface that is a single cell has no boundary for it to draw on.
 * The insets are the rows' own ([settingsRowPadding]), so a row that is a button or a menu is its
 * whole padded width. [title] is the catalogue key the caption is looked up by.
 *
 * A section of the list rather than a composable, because the list is built outside composition;
 * it keeps the iPhone view's name, as do the groups built on it.
 */
fun GroupedListScope.SettingsGroup(title: String, rows: @Composable () -> Unit) {
    section(key = title, header = { FieldLabel(title) }) {
        row(key = "$title.rows", style = RowStyle(insets = PaddingValues(0.dp), separator = false)) {
            Column(Modifier.fillMaxWidth()) { rows() }
        }
    }
}

/**
 * The inset every settings row shares, so titles, switches and menus line up and the surface has
 * room around them. On the row itself rather than on the cell: the rows of a group share one cell
 * ([SettingsGroup]).
 */
fun Modifier.settingsRowPadding(): Modifier =
    fillMaxWidth()
        .padding(vertical = 12.dp)
        .padding(horizontal = Theme.Space.medium)
