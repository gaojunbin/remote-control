package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * A label on the left and a value on the right, one line each.
 *
 * Settings itself no longer reads like this — its rows are two lines and a control — but a screen
 * that has nothing to offer and only something to state still does: the blocking update screen
 * says which build this is and which one the gateway asks for. [key] is looked up, as the
 * iPhone's `LocalizedStringKey`; [value] is drawn as it is.
 */
@Composable
fun ValueRow(key: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(
        modifier
            .settingsRowLayout()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.medium),
    ) {
        Text(L10n.string(key), Modifier.alignByBaseline(), style = Theme.Text.label, color = Theme.ink)
        Text(
            value,
            Modifier
                .weight(1f)
                .alignByBaseline(),
            style = if (mono) Theme.Text.metaMono else Theme.Text.meta,
            color = Theme.inkSecondary,
            lineLimit = 1,
            truncation = if (mono) Truncation.middle else Truncation.tail,
            alignment = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/**
 * The sentence under a group. Caption weight, secondary, never a box. Settings has none left — a
 * state speaks in the row it belongs to — but the accounts screen still captions its one switch,
 * and the resume notice its picker.
 */
@Composable
fun SettingsFooter(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = Theme.Space.hair), style = Theme.Text.caption, color = Theme.inkSecondary)
}

/**
 * The inset every settings row shares, so titles, toggles and pickers line up and the surface has
 * room around them: 12 above and below, 16 at the sides, at least 28 tall. No separator: the rows
 * of a group sit on one surface with spacing between the groups instead.
 */
fun Modifier.settingsRowLayout(): Modifier =
    fillMaxWidth()
        .heightIn(min = 28.dp + 24.dp)
        .padding(horizontal = Theme.Space.medium, vertical = 12.dp)
