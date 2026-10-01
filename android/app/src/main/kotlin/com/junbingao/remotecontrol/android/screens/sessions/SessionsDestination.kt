package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.android.screens.chat.ChatView
import com.junbingao.remotecontrol.android.shell.TabRoot

/**
 * What the Sessions tab's stack draws for a route — the iPhone's `NavigationStack(path:)` with
 * `navigationDestination(for: String.self)`: the list at the root, and a conversation for a
 * session key, which is what the model's `path` holds (`AppModel.open` pushes one). A route this
 * tab's screens push of their own is drawn here too, by whoever owns it.
 */
@Composable
fun SessionsDestination(route: Any) {
    when (route) {
        is TabRoot -> SessionsView()
        is String -> ChatView(sessionKey = route)
    }
}
