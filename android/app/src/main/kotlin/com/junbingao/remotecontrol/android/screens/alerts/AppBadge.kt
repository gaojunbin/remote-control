package com.junbingao.remotecontrol.android.screens.alerts

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import com.junbingao.remotecontrol.android.push.HuaweiBadge
import com.junbingao.remotecontrol.android.push.LauncherBadge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where the icon's badge is set, so the rule can be driven without Android's notification manager in a check. */
interface BadgePlatform {
    fun setBadge(count: Int)
}

/**
 * The launcher's own badge, which Android draws from the app's notifications: one quiet
 * notification carries the number while it is above zero ([LauncherBadge]), and none at zero.
 * Setting it asks nothing of the person: it is posted only where notifications are allowed, which
 * Notify me asks for. Huawei's and Honor's launchers are told the same number through their own
 * interface ([HuaweiBadge]) — exactly what the notification shows, so 0 wherever none was posted.
 */
class SystemBadge(context: Context) : BadgePlatform {
    private val context = context.applicationContext

    override fun setBadge(count: Int) {
        val posted = count > 0 && LauncherBadge.post(context, count)
        if (!posted) LauncherBadge.remove(context)
        HuaweiBadge.set(context, if (posted) count else 0)
    }
}

/**
 * Amendment A47: the app icon's badge, kept at the number of sessions with a red dot while the app
 * runs (`docs/DESIGN.md` § "A red dot for a session that stopped and waits for you"). Android has no
 * push channel yet, so the number changes only while the app runs, and stays as it was when the app
 * last ran.
 *
 * The iPhone's observation tracking is snapshot observation here: [count] is read inside a
 * [snapshotFlow], so whatever snapshot state it reads is followed, in [tasks].
 */
class AppBadge(private val platform: BadgePlatform, private val tasks: CoroutineScope, private val count: () -> Int) {
    /** What the app last put on the icon, or null before it has put anything. */
    var shown: Int? = null
        private set

    private var following: Job? = null

    /** Follow the count for the life of this keeper. A second call does nothing. */
    fun start() {
        if (following != null) return
        following = tasks.launch { snapshotFlow { count() }.collect { show(it) } }
    }

    /**
     * Put the count on the icon again, whatever the app last put there: the person may have swiped
     * the notification away, or allowed notifications since it was refused.
     */
    fun reassert() {
        shown = null
        show(count())
    }

    private fun show(value: Int) {
        if (value == shown) return
        shown = value
        platform.setBadge(value)
    }
}
