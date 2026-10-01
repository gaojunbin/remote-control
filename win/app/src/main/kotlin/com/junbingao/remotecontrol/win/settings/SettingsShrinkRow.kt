package com.junbingao.remotecontrol.win.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.HStackScope

/**
 * A flex row whose items keep their own width but one, which takes what is left and no more than
 * it needs: the Settings header's meta line, where only the host gives way
 * (`.settings-identity-host { min-width: 0 }`), and the host itself, where only its head does and
 * the tail keeps the port. Each item is centred on the line, as `align-items: center` puts it.
 *
 * The item that gives way is the one marked `givesWay()`: the stack measures it after the others,
 * in the width they leave, at its own width when that is enough.
 */
@Composable
fun SettingsShrinkRow(spacing: Dp, modifier: Modifier = Modifier, content: @Composable SettingsShrinkRowScope.() -> Unit) {
    HStack(modifier, spacing = spacing) { SettingsShrinkRowScopeInstance.content() }
}

/** What an item of a `SettingsShrinkRow` can ask of it. */
interface SettingsShrinkRowScope : HStackScope {
    /** This item gives way: it takes what the others leave, and no more than it needs. */
    fun Modifier.givesWay(): Modifier = weight(1f, fill = false)
}

private object SettingsShrinkRowScopeInstance : SettingsShrinkRowScope
