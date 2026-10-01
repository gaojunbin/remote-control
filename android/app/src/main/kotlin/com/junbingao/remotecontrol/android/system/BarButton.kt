package com.junbingao.remotecontrol.android.system

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.icons.SfSymbol
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * A button in a navigation bar as iOS 26 draws one: its own glass capsule, 44 points tall, a
 * circle when it holds one symbol and a pill when it holds words. [prominent] is the bar's
 * confirming button (Done), which iOS sets in the semibold weight; a disabled one greys its words.
 */
@Composable
fun BarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    tag: String? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .height(BarMetrics.buttonHeight)
            .widthIn(min = BarMetrics.buttonHeight)
            .glass(CapsuleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .alpha(if (pressed) 0.6f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Foreground(if (enabled) Theme.ink else SystemColor.tertiaryLabel, SystemFont.body) { content() }
    }
}

/**
 * A bar button holding one symbol: Stop, a filter. A circle 44 across, as iOS draws one; a menu's
 * button, whose symbol iOS pads rather than centres, passes the wider [width] it measures at.
 */
@Composable
fun BarIconButton(
    symbol: SfSymbol,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    width: Dp = BarMetrics.buttonHeight,
    tag: String? = null,
) {
    BarButton(onClick, modifier.width(width), enabled, contentDescription, tag) {
        Icon(symbol, font = SystemFont.body.weight(FontWeight.Medium), tint = tint)
    }
}

/** The navigation bar's back button: the chevron in its circle, larger than a text-sized one. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, tag: String = "nav.back") {
    BarButton(onClick, modifier.width(BarMetrics.buttonHeight), contentDescription = L10n.string("Back"), tag = tag) {
        // A chevron pointing back sits a point and a half before the circle's centre, as the
        // iPhone balances it.
        Icon(Sf.chevronBackward, Modifier.offset(x = (-1.5).dp), font = SystemFont.body.weight(FontWeight.Medium))
    }
}

/** A bar button holding words: Cancel, Done, Close. */
@Composable
fun BarTextButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
    tag: String? = null,
) {
    BarButton(onClick, modifier, enabled, tag = tag) {
        Text(
            title,
            Modifier.padding(horizontal = BarMetrics.textPadding),
            style = if (prominent) SystemFont.body.weight(FontWeight.SemiBold) else SystemFont.body,
            lineLimit = 1,
        )
    }
}

/** A bar button's symbol and words next to each other: a filter showing what it filters to. */
@Composable
fun BarLabelButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    leading: @Composable () -> Unit,
) {
    BarButton(onClick, modifier, tag = tag) {
        Row(
            Modifier.padding(horizontal = BarMetrics.iconPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            leading()
            Text(title, style = SystemFont.body, lineLimit = 1)
        }
    }
}

/** The navigation bar's measurements, from the iPhone 17 reference screenshots. */
object BarMetrics {
    /** The bar's row under the status bar, and every button in it. */
    val rowHeight = 44.dp
    val buttonHeight = 44.dp

    /** A bar's buttons stand this far in from the screen's edges. */
    val edge = 16.dp
    val spacing = 8.dp

    /** Around a symbol beside words, as a filter shows what it filters to. */
    val iconPadding = 15.dp
    val textPadding = 16.dp
}
