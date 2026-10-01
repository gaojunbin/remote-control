package com.junbingao.remotecontrol.win.app

import androidx.compose.runtime.compositionLocalOf

/**
 * The web's media queries, read from the window's content width: every screen follows the same
 * breakpoints its stylesheet does. `max-width: N px` holds at N and below, so `maxWidth760` is true
 * at 760 px.
 */
data class LayoutClass(val width: Float, val height: Float) {
    /** `@media (max-width: …)`. */
    fun maxWidth(px: Float): Boolean = width <= px

    /** Below this the chat is one pane with a back button (`chat.css`). */
    val maxWidth1023: Boolean get() = maxWidth(1023f)

    /** The compact topbar, pages and device rows (`layout.css`, `devices.css`). */
    val maxWidth760: Boolean get() = maxWidth(760f)

    /** Full-width drawer, modals as bottom sheets, and the narrow Sessions, Settings and Users layouts. */
    val maxWidth640: Boolean get() = maxWidth(640f)

    /** The phone-sized device rows, device page and terminal. */
    val maxWidth480: Boolean get() = maxWidth(480f)

    /** The tightest topbar. */
    val maxWidth420: Boolean get() = maxWidth(420f)
}

/** The window's breakpoints, which the root reads from the window's size once. */
val LocalLayoutClass = compositionLocalOf { LayoutClass(1280f, 860f) }
