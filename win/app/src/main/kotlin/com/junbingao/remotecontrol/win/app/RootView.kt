package com.junbingao.remotecontrol.win.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.win.design.WebBase
import com.junbingao.remotecontrol.win.design.overlay.OverlayHost
import com.junbingao.remotecontrol.win.design.overlay.OverlayRegistry

/**
 * What every screen is drawn in: the web's base type and ink on the canvas, the web's breakpoints
 * read from the window's size once, and the overlay layer over all of it. The routed screens —
 * Update required, the login page, the pages inside the topbar layout and the full-window ones —
 * arrive with the app model in stage 2 and the features in stage 3; until then the window and the
 * renderer draw the one view they are handed, as the Mac's `RootView(showing:)` does.
 */
@Composable
fun RootView(overlays: OverlayRegistry = remember { OverlayRegistry() }, showing: @Composable () -> Unit) {
    WebBase(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalLayoutClass provides LayoutClass(maxWidth.value, maxHeight.value)) {
                OverlayHost(overlays) { showing() }
            }
        }
    }
}
