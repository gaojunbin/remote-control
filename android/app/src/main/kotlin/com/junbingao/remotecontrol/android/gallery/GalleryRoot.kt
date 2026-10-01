package com.junbingao.remotecontrol.android.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.junbingao.remotecontrol.android.navigation.NavigationStack
import com.junbingao.remotecontrol.android.navigation.Navigator
import com.junbingao.remotecontrol.android.shell.BackRouter

/**
 * The primitives' gallery on a stack of its own, for a debug build launched with `--gallery`:
 * every primitive and system piece without a gateway, an account or a screen around them.
 */
@Composable
fun GalleryRoot() {
    val navigator = remember { Navigator(GalleryRoute) }
    BackRouter(navigator)
    NavigationStack(navigator) { route ->
        when (route) {
            GalleryRoute -> GalleryIndex()
            is GalleryPageRoute -> GalleryPage(route.page)
        }
    }
}
