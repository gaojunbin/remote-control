package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.system.Menu
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.RowStyle

/**
 * A `Form` row on iOS 26: 16 points in from the card at both sides, at least 52 tall with what it
 * holds in the middle, and — between two rows — a hairline that starts at the row's text and stops
 * at its trailing inset, which is why the row draws its own rather than taking the list's.
 */
internal val FormRow = RowStyle(insets = PaddingValues(horizontal = Theme.Space.medium), separator = false)

@Composable
internal fun FormRowContent(separator: Boolean, top: Dp = FormMetrics.vertical, bottom: Dp = top, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(top = top, bottom = bottom).rowHeight(top, bottom), contentAlignment = Alignment.CenterStart) {
            content()
        }
        if (separator) Box(Modifier.fillMaxWidth().height(FormMetrics.hairline).background(SystemColor.separator))
    }
}

/**
 * `Picker` in a form, as iOS 26 draws one: the title at the leading edge and the chosen value with
 * the up-and-down chevrons at the trailing edge, a menu of the choices with the chosen one checked
 * on a tap. With no title — `labelsHidden()` — the menu's own button is the row, its value at the
 * leading edge, padded as that button is.
 */
@Composable
internal fun FormPicker(title: String?, value: String, items: List<MenuItem>, tag: String, description: String = title ?: value) {
    Menu(items, Modifier.fillMaxWidth().testTag(tag).semantics {
        contentDescription = description
        stateDescription = value
    }) {
        if (title != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.small), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = Theme.ink, lineLimit = 1)
                PickerValue(value)
            }
        } else {
            Box(
                Modifier.fillMaxWidth().heightIn(min = FormMetrics.buttonHeight).padding(horizontal = FormMetrics.buttonInset),
                contentAlignment = Alignment.CenterStart,
            ) { PickerValue(value) }
        }
    }
}

@Composable
private fun PickerValue(value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(FormMetrics.chevronGap), verticalAlignment = Alignment.CenterVertically) {
        Text(value, color = Theme.ink, lineLimit = 1)
        Icon(Sf.chevronUpChevronDown, font = SystemFont.footnote.weight(FontWeight.SemiBold), tint = Theme.ink)
    }
}

/** A form's measurements, from the iPhone 17 reference screenshots (`04-new-session`, `82-new-session-agents`). */
internal object FormMetrics {
    val vertical = 11.dp
    val hairline = 0.33.dp

    /** The segmented control's row, 60 tall in all: the control 15 points under its top and 13 over its foot. */
    val segmentedTop = 15.dp
    val segmentedBottom = 13.dp

    /** A menu's own button, which is the row when the picker shows no title: 43 tall, its value 12 in. */
    val buttonHeight = 43.dp
    val buttonInset = 12.dp
    val chevronGap = 6.dp
}
