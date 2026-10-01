package com.junbingao.remotecontrol.android.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.system.BackButton
import com.junbingao.remotecontrol.android.system.Backdrop
import com.junbingao.remotecontrol.android.system.BackdropSource
import com.junbingao.remotecontrol.android.system.BarMetrics
import com.junbingao.remotecontrol.android.system.LocalBottomBarReach
import com.junbingao.remotecontrol.android.system.LocalInSheet
import com.junbingao.remotecontrol.android.system.LocalTopBarReach
import com.junbingao.remotecontrol.android.system.OverBackdrop
import com.junbingao.remotecontrol.android.system.SheetMetrics
import com.junbingao.remotecontrol.android.system.blurredBackdrop
import com.junbingao.remotecontrol.android.system.blursHere
import com.junbingao.remotecontrol.android.system.safeArea

/** `.navigationBarTitleDisplayMode(_:)`: a large title that folds into the bar, or the bar's title alone. */
enum class TitleDisplayMode { large, inline }

/** Where a screen's content may draw, and what it must leave clear for the bars over it. */
data class ScreenInsets(val top: Dp, val bottom: Dp) {
    fun padding(): PaddingValues = PaddingValues(top = top, bottom = bottom)
}

/**
 * A screen in a navigation stack, laid out as UIKit lays one out: the bar under the status bar
 * with the back button (when there is somewhere to go back to), [leading] and [trailing] items in
 * glass, and the title — large under the bar, moving up with the content and handing over to the
 * bar's own 17-point title as it goes, or in the bar from the start.
 *
 * The content scrolls under the bars, so it takes [ScreenInsets] as its content padding. [top] is
 * `.safeAreaInset(edge: .top)` — a banner under the title, usually a [com.junbingao.remotecontrol.android.system.TopBar]
 * — and [bottomBar] is `.safeAreaInset(edge: .bottom)`, usually a
 * [com.junbingao.remotecontrol.android.system.BottomBar]: it stands over the tab bar's room and
 * its material paints down to the foot of the screen, as the iPhone's does. Pass [listState] when
 * the content is a lazy list, so the large title follows its scroll exactly.
 */
@Composable
fun NavigationScreen(
    title: String,
    modifier: Modifier = Modifier,
    displayMode: TitleDisplayMode = TitleDisplayMode.large,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    showsBack: Boolean = LocalNavigator.current?.canPop == true,
    onBack: (() -> Unit)? = null,
    listState: LazyListState? = null,
    top: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    background: Color = Theme.canvas,
    content: @Composable (ScreenInsets) -> Unit,
) {
    val safe = safeArea()
    val inSheet = LocalInSheet.current
    val navigator = LocalNavigator.current
    val density = LocalDensity.current
    val barTop = if (inSheet) SheetMetrics.barTop else safe.top
    val barBottom = barTop + BarMetrics.rowHeight
    val large = displayMode == TitleDisplayMode.large
    val titleRoom = if (large) NavigationMetrics.largeTitleHeight else 0.dp
    var topHeight by remember { mutableStateOf(0.dp) }
    var bottomHeight by remember { mutableStateOf(0.dp) }
    val tracker = remember { ScrollTracker() }
    val scrolled = listState?.let { state ->
        if (state.firstVisibleItemIndex > 0) Float.MAX_VALUE else state.firstVisibleItemScrollOffset.toFloat()
    } ?: tracker.offset
    val scrolledDp = with(density) { if (scrolled == Float.MAX_VALUE) 1000.dp else scrolled.toDp() }
    val foot = maxOf(LocalTabBarReserve.current, if (inSheet) 0.dp else safe.bottom)
    val insets = ScreenInsets(
        top = barBottom + titleRoom + topHeight,
        bottom = foot + bottomHeight,
    )
    // The bar's title shows once the large one has gone under the bar.
    val inlineAlpha = if (!large) 1f else ((scrolledDp - NavigationMetrics.handover) / 8.dp).coerceIn(0f, 1f)
    val edge = (scrolledDp / 12.dp).coerceIn(0f, 1f)

    val backdrop = remember { Backdrop() }
    Box(modifier.fillMaxSize().background(background)) {
        // What scrolls under the bars is what their material and glass blur.
        BackdropSource(backdrop, Modifier.fillMaxSize().nestedScroll(tracker.connection)) { content(insets) }
        if (large) {
            LargeTitle(
                title,
                Modifier
                    .offset(y = barBottom - scrolledDp.coerceAtMost(NavigationMetrics.largeTitleHeight + 40.dp))
                    .graphicsLayer { alpha = 1f - inlineAlpha },
            )
        }
        OverBackdrop(backdrop) {
            // Under a screen's own top inset, which carries a bar of its own over what scrolls.
            ScrollEdge(background, barBottom + NavigationMetrics.barFoot, edge)
            if (top != null) {
                Box(
                    Modifier
                        .offset(y = barBottom + titleRoom)
                        .fillMaxWidth()
                        .onSizeChanged { topHeight = with(density) { it.height.toDp() } },
                ) {
                    CompositionLocalProvider(LocalTopBarReach provides barBottom + titleRoom) { top() }
                }
            }
            Bar(
                title = title,
                titleAlpha = inlineAlpha,
                top = barTop,
                showsBack = showsBack,
                onBack = onBack ?: { navigator?.pop(); Unit },
                leading = leading,
                trailing = trailing,
            )
            if (bottomBar != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = foot)
                        .onSizeChanged { bottomHeight = with(density) { it.height.toDp() } },
                ) {
                    CompositionLocalProvider(LocalBottomBarReach provides foot) { bottomBar() }
                }
            }
        }
    }
}

@Composable
private fun LargeTitle(title: String, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(NavigationMetrics.largeTitleHeight)
            .padding(horizontal = NavigationMetrics.largeTitleInset),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            title,
            Modifier
                .offset(y = NavigationMetrics.largeTitleTop)
                .semantics { heading() },
            style = SystemFont.largeTitle.weight(FontWeight.Bold),
            color = Theme.ink,
            lineLimit = 1,
        )
    }
}

/**
 * iOS 26's bar over content that has scrolled under it: a light material from the top of the
 * screen to [foot], the bar's own foot ten points under its row, that blurs what is under it, with
 * a hairline along its foot, so the bar's glass buttons and title stand clear of the content. The
 * iPhone draws it the same way at the top of a screen and in a sheet. Where nothing is blurred,
 * the page itself covers the content instead.
 */
@Composable
private fun ScrollEdge(page: Color, foot: Dp, strength: Float) {
    if (strength <= 0f) return
    val tint = when {
        !blursHere -> page.copy(alpha = 0.94f)
        LocalAppearance.current.isDark -> page.copy(alpha = 0.8f)
        else -> Color.White.copy(alpha = 0.75f)
    }
    val line = Theme.hairline
    Box(
        Modifier
            .fillMaxWidth()
            .height(foot)
            .graphicsLayer { alpha = strength }
            .blurredBackdrop(RectangleShape)
            .background(tint)
            .drawBehind {
                val width = NavigationMetrics.hairline.toPx()
                drawRect(line, topLeft = Offset(0f, size.height - width), size = Size(size.width, width))
            },
    )
}

@Composable
private fun Bar(
    title: String,
    titleAlpha: Float,
    top: Dp,
    showsBack: Boolean,
    onBack: () -> Unit,
    leading: (@Composable RowScope.() -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
    Box(
        Modifier
            .padding(top = top)
            .fillMaxWidth()
            .height(BarMetrics.rowHeight)
            .padding(horizontal = BarMetrics.edge),
    ) {
        Text(
            title,
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = NavigationMetrics.inlineTitleClearance)
                .graphicsLayer { alpha = titleAlpha },
            style = SystemFont.headline,
            color = Theme.ink,
            alignment = TextAlign.Center,
            lineLimit = 1,
        )
        Row(
            Modifier.align(Alignment.CenterStart),
            horizontalArrangement = Arrangement.spacedBy(BarMetrics.spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showsBack) BackButton(onBack)
            leading?.invoke(this)
        }
        Row(
            Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(BarMetrics.spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            trailing?.invoke(this)
        }
    }
}

/**
 * How far the content has scrolled, told by the scroll events that pass up through the screen,
 * for content that is not a lazy list the screen was handed.
 */
private class ScrollTracker {
    var offset by mutableFloatStateOf(0f)

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            offset = (offset - consumed.y).coerceAtLeast(0f)
            return Offset.Zero
        }
    }
}

/** The navigation bar's large title, from the iPhone 17 reference screenshots. */
object NavigationMetrics {
    /** The large title's room under the bar: its baseline 46 below the bar, its box 52 tall. */
    val largeTitleHeight = 52.dp

    /** Where the title's line starts, so its baseline lands 46 below the bar. */
    val largeTitleTop = 13.63.dp
    val largeTitleInset = 16.dp

    /** How far the content scrolls before the bar's own title takes over. */
    val handover = 40.dp

    /** The bar's title keeps clear of the buttons either side of it. */
    val inlineTitleClearance = 56.dp

    /**
     * How far iOS 26's navigation bar reaches below its row of buttons: its material over scrolled
     * content ends there (116 on an iPhone 17), and content laid out against the bar's safe area
     * starts there — a device page's first line, the terminal's status line, the Sessions screen's
     * connection line, a sheet's scroll view (`80-device-page-checking`, `ios-round42-terminal`,
     * `31-origin-and-legend`, `05-add-device`). The content's insets end at the row, so a screen
     * whose content starts at the bar adds this; a list's first card already stands where the
     * iPhone's does.
     */
    val barFoot = 10.17.dp

    /** The line along the material's foot. */
    val hairline = 0.33.dp
}
