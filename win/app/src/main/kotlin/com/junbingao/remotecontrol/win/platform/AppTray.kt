package com.junbingao.remotecontrol.win.platform

import com.junbingao.remotecontrol.win.strings.S
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * The app's icon in the notification area (`docs/DESIGN.md` § "The Windows app" → **The window is
 * Windows'**): closing the window leaves the app running and connected there, as Windows chat apps
 * do, so notifications keep arriving; a click on the icon opens the window again, and its menu
 * offers Open and Quit. The app's notifications are shown from this icon too (`TrayToasts`), and
 * a click on one opens the conversation it names.
 *
 * Every callback arrives on the AWT event thread, which is the thread the window's Compose runs on.
 */
class AppTray private constructor(private val tray: SystemTray) {
    /** Open the window, or the conversation a clicked notification names. */
    var onOpen: (ToastTarget?) -> Unit = {}
    var onQuit: () -> Unit = {}

    internal val clicks = ToastClicks()
    private val open = MenuItem()
    private val quit = MenuItem()
    internal val icon = TrayIcon(AppIcon.trayImage, S.productName, PopupMenu().apply { add(open); add(quit) })

    init {
        icon.isImageAutoSize = true
        open.addActionListener { onOpen(null) }
        quit.addActionListener { onQuit() }
        icon.addActionListener { onOpen(clicks.action()) }
        icon.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = clicks.iconPressed()

            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && event.clickCount == 1) onOpen(null)
            }
        })
        relabel()
    }

    /** The menu in the interface language, which it follows as the window's words do. */
    fun relabel() {
        open.label = S.win.trayOpen
        quit.label = S.win.trayQuit
    }

    /** A47: the red disc with `label` over the icon, or the plain icon for null. */
    fun badge(label: String?) {
        icon.image = if (label == null) AppIcon.trayImage else AppIcon.trayImage(label)
    }

    fun remove() {
        tray.remove(icon)
    }

    companion object {
        /** The icon, or null where there is no notification area: then closing the window quits. */
        fun install(): AppTray? {
            if (!SystemTray.isSupported()) return null
            val tray = SystemTray.getSystemTray()
            return AppTray(tray).also { tray.add(it.icon) }
        }
    }
}
