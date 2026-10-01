package com.junbingao.remotecontrol.android.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf

/** One screen on a stack: the value that says what it shows, and an identity its saved state is kept under. */
class NavigationEntry internal constructor(val route: Any, val id: Long)

/**
 * One tab's `NavigationStack(path:)`: the root screen and the screens pushed over it. A route is
 * any value the tab's destinations understand — a device id, a session key — as the iPhone's
 * `navigationDestination(for:)` answers a value. Changes happen on the main thread.
 */
class Navigator(root: Any) {
    private var nextId = 0L
    internal val entries = mutableStateListOf(NavigationEntry(root, nextId++))

    /** The routes from the root to the top, as the iPhone's path plus its root. */
    val routes: List<Any> get() = entries.map { it.route }

    val top: NavigationEntry get() = entries.last()

    /** Whether Back has a screen to leave, which is also when the back button shows. */
    val canPop: Boolean get() = entries.size > 1

    fun push(route: Any) {
        entries.add(NavigationEntry(route, nextId++))
    }

    /** Leaves the top screen; false at the root, where Back belongs to the system. */
    fun pop(): Boolean {
        if (!canPop) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    fun popToRoot() {
        while (entries.size > 1) entries.removeAt(entries.lastIndex)
    }

    /**
     * Makes the stack the root and then [routes], as assigning the iPhone's path does: a link that
     * opens a conversation over another one replaces it rather than stacking on it.
     */
    fun setPath(routes: List<Any>) {
        val root = entries.first()
        entries.clear()
        entries.add(root)
        routes.forEach { entries.add(NavigationEntry(it, nextId++)) }
    }
}

/** The stack the screen being drawn belongs to, for its back button and its pushes. */
val LocalNavigator = compositionLocalOf<Navigator?> { null }
