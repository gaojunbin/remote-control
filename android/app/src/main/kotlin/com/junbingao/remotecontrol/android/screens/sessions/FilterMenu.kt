package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.system.BarMetrics
import com.junbingao.remotecontrol.android.system.Menu
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.glass

/**
 * The bar's filter: the one control the Sessions screen has for agents and the Devices screen for
 * platforms (`docs/DESIGN.md` § "Devices can be filtered by platform"), a pull-down menu in the
 * bar's own glass. The glyph is in the secondary ink while the list is whole and in the ink once a
 * choice narrows it, with the choice beside it, so the narrowed list says what it is narrowed to.
 *
 * [description] is what a screen reader calls the control and [value] what it is set to, as the
 * iPhone's `accessibilityLabel` and `accessibilityValue`; [choice] draws the chosen value.
 */
@Composable
fun FilterMenu(
    items: List<MenuItem>,
    narrowed: Boolean,
    description: String,
    value: String,
    tag: String,
    choice: (@Composable () -> Unit)? = null,
) {
    val described = Modifier
        .testTag(tag)
        .semantics {
            contentDescription = description
            stateDescription = value
        }
    Menu(items, described) {
        Box(
            Modifier
                .height(BarMetrics.buttonHeight)
                .widthIn(min = FilterMetrics.width)
                .glass(CapsuleShape)
                .padding(start = if (choice == null) 0.dp else FilterMetrics.leading, end = if (choice == null) 0.dp else FilterMetrics.trailing),
            contentAlignment = Alignment.Center,
        ) {
            Foreground(if (narrowed) Theme.ink else Theme.inkSecondary, Theme.Text.meta) {
                Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.hair + 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Sf.line3HorizontalDecrease,
                        Modifier.padding(end = if (choice == null) 0.dp else FilterMetrics.glyphMargin),
                        font = SystemFont.body.weight(FontWeight.Medium),
                    )
                    choice?.invoke()
                }
            }
        }
    }
}

/** The filter's measurements, from the iPhone 17 reference screenshots (`58-device-platform-filter`, `18-agent-filter`). */
object FilterMetrics {
    /** A menu's button, whose symbol iOS pads rather than centres, is wider than a bar button's circle. */
    val width = 48.dp

    /** With a choice beside the glyph: the capsule's own padding either side. */
    val leading = 13.3.dp
    val trailing = 10.6.dp

    /** SF's symbol box runs wider than its ink, which puts the choice this much further from it. */
    val glyphMargin = 2.7.dp
}
