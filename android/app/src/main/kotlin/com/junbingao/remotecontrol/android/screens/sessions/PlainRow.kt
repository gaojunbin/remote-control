package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.Theme

/**
 * A row of `List { … }.listStyle(.plain)` as iOS 26 draws one: on the surface across the whole
 * width, its content 16 points in and 15 from its top and bottom, at least 52 tall, and a hairline
 * along its foot that starts where the row's words start ([separatorInset], from the row's own
 * leading inset) and stops 16 points short of the trailing edge. The list draws one more above its
 * first row, on the page, at the bar's foot ([PlainListTop]).
 */
@Composable
internal fun PlainRow(
    separatorInset: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    tag: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface)
            .then(
                if (onClick != null) {
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PlainListMetrics.inset, vertical = PlainListMetrics.vertical)
                .rowHeight(PlainListMetrics.vertical, PlainListMetrics.vertical),
            contentAlignment = Alignment.CenterStart,
        ) { content() }
        PlainSeparator(separatorInset, Modifier.align(Alignment.BottomStart))
    }
}

/** The hairline a plain list draws above its first row, inset as that row's own is ([inset]). */
@Composable
internal fun PlainListTop(inset: Dp) {
    Box(Modifier.fillMaxWidth().height(PlainListMetrics.top)) {
        PlainSeparator(inset, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun PlainSeparator(inset: Dp, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = PlainListMetrics.inset + inset, end = PlainListMetrics.inset)
            .height(FormMetrics.hairline)
            .background(SystemColor.separator),
    )
}

/** A plain list's measurements, from the iPhone 17 reference screenshots (`53-directory-new-folder-clash`, `55-directory-picker-in-new-folder`). */
internal object PlainListMetrics {
    val inset = 16.dp
    val vertical = 15.dp

    /** The hairline above the first row ends at the bar's foot. */
    val top = BarFoot.height
}
