package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.system.BarMetrics

/**
 * `.searchable` in the navigation bar's drawer, as iOS 26 keeps it on a list: folded away while
 * the list rests, drawn out under the large title as the list is pulled down past its top, folded
 * away again before the list moves when it is scrolled on, and in the bar itself — the title gone,
 * a close button beside it — while it is being used. Closing it empties the query and hands the
 * list back to what was stored (`docs/DESIGN.md` § "Session lists: by device, then by activity").
 */
class SearchDrawer internal constructor(out: Float, active: Boolean, private val full: Float) {
    /** How much of the drawer is out, in pixels: none while it rests, [full] once it is drawn out. */
    var shown by mutableFloatStateOf(out)
        private set
    var isActive by mutableStateOf(active)
        private set

    val isRevealed: Boolean get() = shown > 0f

    /**
     * The drawer is part of what the list scrolls: scrolling on folds it away first, and a pull past
     * the list's top — a drag or a fling that reaches it — draws it out. Let go part of the way and
     * it settles in or out, whichever is nearer.
     */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f || shown <= 0f) return Offset.Zero
            val taken = minOf(shown, -available.y)
            shown -= taken
            return Offset(0f, -taken)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y <= 0f || shown >= full) return Offset.Zero
            val taken = minOf(full - shown, available.y)
            shown += taken
            return Offset(0f, taken)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (shown > 0f && shown < full) {
                animate(shown, if (shown >= full / 2) full else 0f) { value, _ -> shown = value }
            }
            return Velocity.Zero
        }
    }

    fun activate() {
        shown = full
        isActive = true
    }

    /** The close button: the field goes back to the drawer and the query with it. */
    fun close() {
        isActive = false
    }
}

/** The drawer of one list, kept while the screen is covered, as the iPhone's search state is. */
@Composable
fun rememberSearchDrawer(): SearchDrawer {
    val full = with(LocalDensity.current) { SearchDrawerMetrics.height.toPx() }
    val saver = listSaver<SearchDrawer, Any>(
        save = { listOf(it.shown, it.isActive) },
        restore = { SearchDrawer(out = it[0] as Float, active = it[1] as Boolean, full = full) },
    )
    return rememberSaveable(saver = saver) { SearchDrawer(out = 0f, active = false, full = full) }
}

/** The drawer's measurements, from the iPhone 17's list pulled past its top (`30-status-tone-auth`). */
object SearchDrawerMetrics {
    /** From the large title's room to the field. */
    val top = 10.dp

    /** All of the drawer: that gap and the 44-point field under it. */
    val height = top + BarMetrics.buttonHeight
}
