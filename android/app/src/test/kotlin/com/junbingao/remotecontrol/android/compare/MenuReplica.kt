package com.junbingao.remotecontrol.android.compare

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.pageBackground
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.icons.SfSymbol
import com.junbingao.remotecontrol.android.system.Menu
import com.junbingao.remotecontrol.android.system.MenuItem

/**
 * The composer's control row as the iPhone 17 lays it out (`ios-a44-composer-on-phone`): five
 * 44-point glyphs along the foot of the screen, three of them pull-down menus: the attach menu
 * (`ios-attach-menu-no-camera`), the language menu (`ios-a44-language-menu`) and the permission
 * menu (`13-codex-permissions`).
 */
@Composable
fun ComposerMenuReplica() {
    Box(Modifier.fillMaxSize().pageBackground()) {
        ControlGlyph(19.5f, Sf.plus, "composer.attach", listOf(MenuItem.Action("Photos", Sf.photo), MenuItem.Action("Files", Sf.folder)))
        ControlGlyph(64f, Sf.mic)
        ControlGlyph(114f, Sf.translate, "composer.language", picker(listOf("Chinese", "English", "Japanese", "German", "French", "Spanish"), 0))
        ControlGlyph(156f, Sf.gaugeWithDotsNeedle33percent)
        ControlGlyph(202f, Sf.shield, "composer.permissions", picker(listOf("Ask for everything", "Ask when needed", "Never ask"), 1))
    }
}

private fun picker(titles: List<String>, chosen: Int): List<MenuItem> =
    titles.mapIndexed { index, title -> MenuItem.Action(title, checked = index == chosen) }

@Composable
private fun ControlGlyph(x: Float, symbol: SfSymbol, tag: String? = null, items: List<MenuItem> = emptyList()) {
    val at = Modifier.offset(x = x.dp, y = 784.dp).size(44.dp)
    val glyph: @Composable () -> Unit = {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(symbol, tint = Theme.ink) }
    }
    if (tag == null) Box(at) { glyph() } else Menu(items, at.testTag(tag), label = glyph)
}
