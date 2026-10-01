package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.SfMetrics
import com.junbingao.remotecontrol.android.icons.SfSymbol

/** One destination in the tab bar: its words, the filled symbol the iPhone draws, and a tag for tests. */
data class TabItem(val title: String, val symbol: SfSymbol, val tag: String)

/**
 * The iPhone's tab bar as iOS 26 draws it: a floating glass capsule centred over the bottom of the
 * screen, one item per destination — the filled symbol over its name — and a grey pill behind the
 * one that is chosen, four points in from the capsule's rim. Measured from the reference
 * screenshots: 275 by 62.7 points for three items, its foot 20.7 points above the bottom of an
 * iPhone 17, the pill 94 by 54.
 *
 * It floats over the content, which scrolls under it and which its glass blurs; [TabBarMetrics.reserved]
 * is how much of the bottom a screen leaves for it.
 */
@Composable
fun TabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val safe = safeArea()
    val width = TabBarMetrics.itemWidth * items.size + TabBarMetrics.sidePadding * 2
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .padding(bottom = TabBarMetrics.footMargin(safe))
                .width(width)
                .height(TabBarMetrics.height)
                .glass(CapsuleShape),
        ) {
            val pillX by animateDpAsState(
                TabBarMetrics.sidePadding + TabBarMetrics.itemWidth * selected -
                    (TabBarMetrics.pillWidth - TabBarMetrics.itemWidth) / 2,
                tween(if (LocalAppearance.current.reduceMotion) 0 else 250),
                label = "pill",
            )
            Box(
                Modifier
                    .offset { IntOffset(pillX.roundToPx(), ((TabBarMetrics.height - TabBarMetrics.pillHeight) / 2).roundToPx()) }
                    .size(TabBarMetrics.pillWidth, TabBarMetrics.pillHeight)
                    .background(Glass.pill, CapsuleShape),
            )
            Row(Modifier.padding(horizontal = TabBarMetrics.sidePadding).fillMaxHeight()) {
                items.forEachIndexed { index, item ->
                    TabBarItem(item, index == selected) { onSelect(index) }
                }
            }
        }
    }
}

@Composable
private fun TabBarItem(item: TabItem, chosen: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(TabBarMetrics.itemWidth)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                selected = chosen
                contentDescription = item.title
            }
            .testTag(item.tag),
    ) {
        val side = SfMetrics.box(TabBarMetrics.symbolPoints, item.symbol.scale).dp
        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = TabBarMetrics.symbolCentre - side / 2),
            contentAlignment = Alignment.TopCenter,
        ) {
            Icon(item.symbol, side, FontWeight.Normal, tint = Theme.ink)
        }
        Text(
            item.title,
            Modifier
                .fillMaxWidth()
                .offset(y = TabBarMetrics.labelTop),
            style = SystemFont.system(10f).weight(FontWeight.SemiBold),
            color = Theme.ink,
            alignment = TextAlign.Center,
            lineLimit = 1,
        )
    }
}

/** The tab bar's measurements, from the iPhone 17 reference screenshots. */
object TabBarMetrics {
    val height = 62.67.dp
    val itemWidth = 85.8.dp
    val sidePadding = 8.8.dp
    val pillWidth = 94.dp
    val pillHeight = 54.dp

    /** The symbols are set at 22 points, the size that makes the three the iPhone's size. */
    val symbolPoints = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)

    /** Where each symbol's centre and each label's top sit, down from the capsule's top. */
    val symbolCentre = 24.57.dp
    val labelTop = 39.57.dp

    /**
     * How far above the screen's bottom the capsule's foot stands: 20.7 on an iPhone 17, whose bar
     * sits 13.3 into the home indicator's inset, as it does over gesture navigation's handle. Android's
     * three buttons take touches, so there the whole capsule stands clear of them.
     */
    fun footMargin(safe: SafeArea): Dp =
        max(max(safe.bottom - 13.33.dp, 8.dp), if (safe.tappableBottom > 0.dp) safe.tappableBottom + 8.dp else 0.dp)

    /** How much of the bottom a screen leaves for the bar, up to the capsule's top: 83.3 on an iPhone 17. */
    fun reserved(safe: SafeArea): Dp = footMargin(safe) + height
}
