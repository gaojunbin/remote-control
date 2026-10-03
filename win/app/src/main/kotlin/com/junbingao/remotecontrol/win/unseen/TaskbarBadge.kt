package com.junbingao.remotecontrol.win.unseen

import androidx.compose.runtime.snapshotFlow
import com.junbingao.remotecontrol.win.platform.BadgeSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Where the icon's badge goes: Windows' taskbar and notification area once the window is up, and a
 * stand-in wherever there is no window to draw on — the renderer and the test runner. The keeper
 * talks to this and nothing else. The Mac's `DockBadgePlatform`, with Windows named where the Mac
 * names the Dock.
 */
interface TaskbarBadgePlatform {
    /** The label on the icon, or null for none. */
    fun show(label: String?)
}

/** The badge of a process that has no window: kept, and shown to nobody. */
class InertTaskbarBadge : TaskbarBadgePlatform {
    private val shown = mutableListOf<String?>()
    val labels: List<String?> get() = shown

    override fun show(label: String?) {
        shown += label
    }
}

/**
 * Windows' own (`docs/DESIGN.md` § "A red dot for a session that stopped and waits for you" →
 * Windows): a red disc with the number over the taskbar button, and the same disc over the
 * notification-area icon while the window is closed — a closed window has no taskbar button to carry
 * it. It touches Windows only when what it shows changes, and once more each time the window opens
 * again, because that window is given a new taskbar button, which carries nothing until it is told.
 */
class SystemTaskbarBadge(private val surface: BadgeSurface) : TaskbarBadgePlatform {
    private var label: String? = null
    private var windowShown = false
    private var onTaskbar: String? = null
    private var onTray: String? = null

    override fun show(label: String?) {
        this.label = label
        apply(reopened = false)
    }

    /** The window opened, or closed to the notification area. */
    fun windowShown(shown: Boolean) {
        val reopened = shown && !windowShown
        windowShown = shown
        apply(reopened)
    }

    private fun apply(reopened: Boolean) {
        if (label != onTaskbar || (reopened && label != null)) {
            onTaskbar = label
            surface.taskbar(label)
        }
        val corner = label.takeIf { !windowShown }
        if (corner != onTray) {
            onTray = corner
            surface.tray(corner)
        }
    }
}

/**
 * Amendment A47: the icon's badge, kept at the number of sessions with a red dot while the app runs
 * (`docs/DESIGN.md` § "A red dot for a session that stopped and waits for you"): the number, or no
 * badge at all at zero.
 *
 * The Mac's observation tracking is snapshot observation here: [count] is read inside a
 * [snapshotFlow], so what it reads of the model is followed, in the scope [start] is given — the
 * model's, whose end is the keeper's.
 */
class TaskbarBadgeKeeper(platform: TaskbarBadgePlatform, private val count: () -> Int) {
    /** The count the badge shows, or null before the keeper has shown anything. */
    var shown: Int? = null
        private set

    /**
     * Where the badge goes: a stand-in until the window puts Windows' own in its place, as it does
     * the model's toasts, and the new one is given what the badge shows at once.
     */
    var platform: TaskbarBadgePlatform = platform
        set(value) {
            field = value
            shown?.let { value.show(label(it)) }
        }

    private var isFollowing = false

    fun start(tasks: CoroutineScope) {
        if (isFollowing) return
        isFollowing = true
        tasks.launch { snapshotFlow { count() }.collect { follow(it) } }
    }

    private fun follow(value: Int) {
        if (value == shown) return
        shown = value
        platform.show(label(value))
    }

    companion object {
        /** The label a count is drawn as. */
        fun label(count: Int): String? = if (count > 0) "$count" else null
    }
}
