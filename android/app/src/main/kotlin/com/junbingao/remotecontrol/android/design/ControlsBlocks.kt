package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n

/** A screen with nothing to list: one light symbol, a title and a sentence, centred. */
@Composable
fun EmptyStateView(symbol: String, title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Theme.Space.large, vertical = 44.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Theme.Space.small + 2.dp),
    ) {
        Sf.named(symbol)?.let {
            Icon(it, font = SystemFont.system(30f, FontWeight.Light), tint = Theme.inkSecondary)
        }
        Text(title, style = SystemFont.headline, color = Theme.ink)
        Text(message, style = SystemFont.subheadline, color = Theme.inkSecondary, alignment = TextAlign.Center)
    }
}

/** The app mark: a black rounded square with the connected nodes, `point.3.connected.trianglepath.dotted`, at half its size. */
@Composable
fun AppMark(size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .background(Theme.accent, ContinuousShape(size * 0.26f))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Sf.point3ConnectedTrianglepathDotted,
            font = SystemFont.system(size.value * 0.5f, FontWeight.Medium),
            tint = Theme.onAccent,
        )
    }
}

/**
 * A one-line banner used for errors, for unconfirmed delivery and for a session waiting on a
 * resume (A35). It offers at most two actions, because a bar above the transcript that offers
 * three is a toolbar. [identifier] names the banner's own line rather than the bar, so the two
 * actions keep `notice.action` and `notice.secondaryAction`.
 */
@Composable
fun NoticeBanner(
    text: String,
    tint: Color = Theme.danger,
    actionTitle: String? = null,
    action: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
    secondaryActionTitle: String? = null,
    secondaryAction: (() -> Unit)? = null,
    dismiss: (() -> Unit)? = null,
    identifier: String = "notice.text",
) {
    val hairline = Theme.hairline
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface)
            .drawBehind {
                val y = size.height - 0.25.dp.toPx()
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
            }
            .padding(horizontal = Theme.Space.medium, vertical = Theme.Space.small),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(tint, CircleShape).clearAndSetSemantics {})
        // The words take what the actions leave and wrap there; the row's spacing keeps them
        // clear of the first action.
        Text(text, Modifier.weight(1f).testTag(identifier), style = SystemFont.footnote, color = Theme.ink)
        if (actionTitle != null && action != null) {
            BannerAction(actionTitle, action, actionEnabled, "notice.action")
        }
        if (secondaryActionTitle != null && secondaryAction != null) {
            BannerAction(secondaryActionTitle, secondaryAction, actionEnabled, "notice.secondaryAction")
        }
        if (dismiss != null) {
            Button(
                onClick = dismiss,
                modifier = Modifier
                    .size(Theme.Touch.minimum)
                    .semantics { contentDescription = L10n.string("Dismiss") },
            ) {
                Icon(Sf.xmark, font = SystemFont.footnote, tint = Theme.inkSecondary)
            }
        }
    }
}

@Composable
private fun BannerAction(title: String, action: () -> Unit, enabled: Boolean, tag: String) {
    Button(
        onClick = action,
        enabled = enabled,
        modifier = Modifier
            .heightIn(min = Theme.Touch.minimum)
            .alpha(if (enabled) 1f else 0.4f)
            .testTag(tag),
    ) {
        Text(title, style = SystemFont.footnote.weight(FontWeight.Medium), color = Theme.ink)
    }
}

/** A hairline across a surface: the one line allowed inside it. */
@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = Theme.hairline) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(color))
}
