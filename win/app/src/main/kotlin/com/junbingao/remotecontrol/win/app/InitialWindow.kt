package com.junbingao.remotecontrol.win.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment

/**
 * The window's first size: the Mac's 1280 × 860, or as much of the screen's work area — the screen
 * less the taskbar, with a margin round it — as there is, where that is smaller. AppKit fits a window
 * to the visible frame on its own; Windows places a window larger than the screen with its title bar
 * above the top, out of reach.
 */
object InitialWindow {
    val preferred = DpSize(1280.dp, 860.dp)

    /** The narrowest the window goes, as the Mac's: 480 wide, so the web's 480 breakpoint is reachable. */
    val minimum = DpSize(480.dp, 560.dp)

    private val margin = 24.dp

    /** The first size on a screen whose work area is [workArea]. */
    fun size(workArea: DpSize): DpSize = DpSize(
        width = (workArea.width - margin * 2).coerceIn(minimum.width, preferred.width),
        height = (workArea.height - margin * 2).coerceIn(minimum.height, preferred.height),
    )

    /** The first size on this machine's main screen, or [preferred] where there is no screen to ask. */
    fun size(): DpSize = workArea()?.let(::size) ?: preferred

    /** The main screen's work area in AWT's logical pixels, the window's own units; null headless. */
    private fun workArea(): DpSize? = runCatching {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        DpSize(bounds.width.dp, bounds.height.dp)
    }.getOrNull()
}
