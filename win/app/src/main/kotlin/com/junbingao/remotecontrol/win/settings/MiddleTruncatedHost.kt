package com.junbingao.remotecontrol.win.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.Help
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.css

/**
 * `MiddleTruncated` in `IdentityHeader.tsx`: a host that does not fit loses its middle, not its
 * end — `rc.example…:8443` still says which port. Two pieces and no measuring: the tail is fixed
 * and the head is what the line can spare.
 */
@Composable
fun MiddleTruncatedHost(text: String, modifier: Modifier = Modifier) {
    val (head, tail) = MiddleTruncatedHost.split(text)
    val style = css(FontSize.fs13, lineHeight = 1.4f)
    Help(text, modifier) {
        SettingsShrinkRow(spacing = 0.dp) {
            Text(head, style, Modifier.givesWay(), lineLimit = 1)
            Text(tail, style, lineLimit = 1)
        }
    }
}

object MiddleTruncatedHost {
    /** How much of a long host is kept at its end, so the port always survives. */
    const val tail = 8

    fun split(text: String): Pair<String, String> {
        val cut = maxOf(text.length - tail, 0)
        return text.take(cut) to text.drop(cut)
    }
}
