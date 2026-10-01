package com.junbingao.remotecontrol.win.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.design.LayoutSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.strings.S

/**
 * `web/src/layout/AppLayout.tsx`: the signed-in shell — the topbar across the top of the page, and
 * the page scrolling under it at the web's content width (1080, padding included) with its
 * paddings: 32 · 24 · 40, and 20 · 16 · 32 at 760 and narrower. The page fills the window at
 * least, on the canvas.
 */
@Composable
fun AppLayout(page: @Composable () -> Unit) {
    val layout = LocalLayoutClass.current
    val compact = layout.maxWidth760
    Box(Modifier.fillMaxSize().background(Palette.canvas)) {
        ThinScrollView(modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = TopbarHeight)
                    .heightIn(min = (layout.height.dp - TopbarHeight).coerceAtLeast(0.dp)),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    Modifier
                        .widthIn(max = LayoutSize.contentMax)
                        .fillMaxWidth()
                        .padding(
                            top = if (compact) Space.sp5 else Space.sp8,
                            start = if (compact) Space.sp4 else Space.sp6,
                            end = if (compact) Space.sp4 else Space.sp6,
                            bottom = if (compact) Space.sp8 else Space.sp10,
                        ),
                ) { page() }
            }
        }
        Topbar()
    }
}

/** `.boot`: the canvas and nothing else, while the app is deciding what to show. */
@Composable
fun BootView() {
    Box(Modifier.fillMaxSize().background(Palette.canvas).semantics { contentDescription = S.common.loading })
}
