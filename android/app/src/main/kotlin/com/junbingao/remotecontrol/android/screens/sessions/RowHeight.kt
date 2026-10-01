package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.system.ListMetrics

/**
 * A list row is never shorter than iOS 26 makes one, 52 points with its insets, and what is
 * shorter than that stands in the middle of it, as a one-line caption under a device row does
 * (`91-device-tap-refused`) and a form's picker row (`04-new-session`). Applied to a row's content,
 * so [top] and [bottom] are the row's own insets.
 */
fun Modifier.rowHeight(top: Dp, bottom: Dp): Modifier =
    heightIn(min = (RowMetrics.minimum - top - bottom).coerceAtLeast(0.dp)).wrapContentHeight(Alignment.CenterVertically)

object RowMetrics {
    /** iOS 26's `defaultMinListRowHeight`, measured on the reference screenshots. */
    val minimum = 52.dp

    /**
     * iOS 26 sets a section that has no header 35 points under the card above it, where the shell's
     * list leaves 20 (`18-agent-filter`, `69-sessions-bottom-bar`): the rest, for the first row of
     * such a section to stand down by.
     */
    val headerlessGap = 35.dp - ListMetrics.sectionGap
}
