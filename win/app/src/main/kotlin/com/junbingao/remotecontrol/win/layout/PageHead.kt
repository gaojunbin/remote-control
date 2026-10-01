package com.junbingao.remotecontrol.win.layout

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.HStackScope
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css

/**
 * `.page-head`: a page's title — 30 px, 600, tightened by 2 %, 22 at 760 and narrower — with an
 * optional `.hint` under it and the page's actions at the trailing edge, 24 px above the page's
 * content.
 */
@Composable
fun PageHead(title: String, hint: String? = null, modifier: Modifier = Modifier, actions: @Composable HStackScope.() -> Unit = {}) {
    val size = if (LocalLayoutClass.current.maxWidth760) FontSize.fs22 else FontSize.fs30
    HStack(modifier.fillMaxWidth().padding(bottom = Space.sp6), spacing = Space.sp4, alignment = Alignment.Top) {
        VStack(Modifier.weight(1f), spacing = 0.dp, alignment = Alignment.Start) {
            Text(title, css(size, weight = FontWeight.SemiBold, tracking = -0.02f), Modifier.semantics { heading() })
            if (hint != null) Hint(hint, Modifier.padding(top = 2.dp))
        }
        actions()
    }
}
