package com.junbingao.remotecontrol.win.platform

import java.awt.Taskbar
import java.awt.Window
import kotlin.math.roundToInt

/** Where Windows draws the icon's badge (A47): the taskbar button and the notification-area icon. A test records it instead. */
interface BadgeSurface {
    /** The disc with `label` over the taskbar button, or nothing for null. */
    fun taskbar(label: String?)

    /** The disc with `label` over the notification-area icon, or the plain icon for null. */
    fun tray(label: String?)
}

/**
 * Windows' own: `Taskbar.setWindowIconBadge`, which Windows draws as the taskbar button's overlay
 * icon — a small icon, so the disc is drawn at 16 px of the window's scale — and the notification
 * area's icon. Where the feature is missing (the app run on a Mac to develop it) the taskbar has
 * nothing to show, and where there is no notification area there is no tray.
 */
class WindowsBadgeSurface(private val tray: AppTray?) : BadgeSurface {
    /** The main window, once it exists. */
    var window: Window? = null

    override fun taskbar(label: String?) {
        val window = window ?: return
        if (!Taskbar.isTaskbarSupported()) return
        val taskbar = Taskbar.getTaskbar()
        if (!taskbar.isSupported(Taskbar.Feature.ICON_BADGE_IMAGE_WINDOW)) return
        val scale = window.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        taskbar.setWindowIconBadge(window, label?.let { BadgeImage.disc(it, (16 * scale).roundToInt()) })
    }

    override fun tray(label: String?) {
        tray?.badge(label)
    }
}
