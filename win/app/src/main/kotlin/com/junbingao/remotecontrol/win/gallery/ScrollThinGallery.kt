package com.junbingao.remotecontrol.win.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.card
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.layout.PageHead

/**
 * `.scroll-thin` in the three shapes the app uses it: a list taller than its pane, one that fits,
 * and a line wider than its box. A render shows no bar and no room taken, as the Mac renderer
 * draws its overlay scrollers; the bar appears while a pane scrolls.
 */
@Composable
fun ScrollThinGallery() {
    VStack(Modifier.fillMaxSize().background(Palette.canvas).padding(Space.sp8), spacing = Space.sp4, alignment = Alignment.Start) {
        PageHead("Thin scroll bars")
        HStack(spacing = Space.sp5, alignment = Alignment.Top) {
            Pane(rows = 30)
            Pane(rows = 3)
            ThinScrollView(Orientation.Horizontal, Modifier.width(300.dp).card()) {
                Text(
                    "a line wider than its box ".repeat(6),
                    css(FontSize.fs13, mono = true),
                    Modifier.background(Palette.surfaceMuted).padding(Space.sp3),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun Pane(rows: Int) {
    ThinScrollView(modifier = Modifier.size(260.dp, 240.dp).card()) {
        VStack(Modifier.fillMaxWidth(), spacing = 0.dp) {
            for (index in 0 until rows) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(if (index % 2 == 0) Palette.surfaceMuted else Palette.surface)
                        .padding(horizontal = Space.sp3),
                    contentAlignment = Alignment.CenterStart,
                ) { Text("Row ${index + 1}", css(FontSize.fs14)) }
            }
        }
    }
}
