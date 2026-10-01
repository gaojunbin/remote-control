package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * `.refreshable`: a page pulled down past its top shows UIKit's spinner in the gap under the bar
 * and asks again when it is let go, and the spinner stays until the answer is in. [top] is where
 * the page's content starts, under the bars that float over it.
 */
@Composable
fun Refreshable(onRefresh: suspend () -> Unit, top: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val refresh by rememberUpdatedState(onRefresh)
    val density = LocalDensity.current
    val threshold = with(density) { RefreshMetrics.threshold.toPx() }
    val held = with(density) { RefreshMetrics.held.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    var refreshing by remember { mutableStateOf(false) }
    val connection = remember(threshold) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pushing back up takes the pull away before the page scrolls.
                if (pull > 0f && available.y < 0f && !refreshing) {
                    val taken = max(available.y, -pull)
                    pull += taken
                    return Offset(0f, taken)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f || refreshing) return Offset.Zero
                // The page resists as it is pulled, as a scroll view's rubber band does.
                pull += available.y * RefreshMetrics.resistance
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (refreshing || pull <= 0f) return Velocity.Zero
                if (pull < threshold) {
                    pull = 0f
                    return Velocity.Zero
                }
                refreshing = true
                scope.launch {
                    try {
                        refresh()
                    } finally {
                        refreshing = false
                        pull = 0f
                    }
                }
                return available
            }
        }
    }
    val shift = if (refreshing) held else pull
    Box(modifier.fillMaxSize().nestedScroll(connection)) {
        Box(Modifier.fillMaxSize().offset { IntOffset(0, shift.roundToInt()) }) { content() }
        if (shift > 0f) {
            val room = with(density) { shift.toDp() }
            Box(Modifier.fillMaxWidth().padding(top = top).height(room), contentAlignment = Alignment.Center) {
                ActivityIndicator()
            }
        }
    }
}

/** The pull's measurements, as UIKit's refresh control behaves. */
object RefreshMetrics {
    /** How far the page has to come down before letting go asks again. */
    val threshold = 64.dp

    /** The gap the spinner holds open while the answer is on its way. */
    val held = 56.dp

    const val resistance = 0.5f
}
