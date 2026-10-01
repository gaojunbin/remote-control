package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.android.gallery.GalleryIndex
import com.junbingao.remotecontrol.android.gallery.GalleryPage
import com.junbingao.remotecontrol.android.gallery.GalleryPageRoute
import com.junbingao.remotecontrol.android.gallery.GalleryRoute
import com.junbingao.remotecontrol.android.screens.users.UsersRoute
import com.junbingao.remotecontrol.android.screens.users.UsersView
import com.junbingao.remotecontrol.android.shell.TabRoot

/**
 * What the Settings tab's stack draws for a route: Settings at the root and the pages it pushes —
 * the accounts screen, and in a debug build the primitives' gallery, which Diagnostics opens.
 */
@Composable
fun SettingsDestination(route: Any) {
    when (route) {
        is TabRoot -> SettingsView()
        UsersRoute -> UsersView()
        GalleryRoute -> GalleryIndex()
        is GalleryPageRoute -> GalleryPage(route.page)
    }
}
