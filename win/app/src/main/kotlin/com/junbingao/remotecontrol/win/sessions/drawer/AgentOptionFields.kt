package com.junbingao.remotecontrol.win.sessions.drawer

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Switch
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.overlay.MenuOption
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.design.overlay.SelectMenu
import com.junbingao.remotecontrol.win.sessions.controls.DrawerSelect
import com.junbingao.remotecontrol.win.strings.S

/**
 * What the agent advertises, in the order the drawer asks for it — Model, Effort, Permissions, then
 * the speed tier (A21) — each starting at the agent's own default, and nothing drawn for a list the
 * agent does not have. Each is a section of the drawer of its own.
 */
@Composable
internal fun AgentOptionFields(form: NewSessionForm, agent: AgentInfo?) {
    val models = agent?.models.orEmpty()
    val efforts = agent?.efforts.orEmpty()
    val modes = agent?.permissionModes.orEmpty()
    val speeds = agent?.speeds.orEmpty()
    if (models.isNotEmpty()) {
        OptionList(S.newSession.model, models, form.model(of = agent)) { form.options = form.options.copy(model = it) }
    }
    if (efforts.isNotEmpty()) {
        OptionList(S.newSession.effort, efforts, form.effort(of = agent)) { form.options = form.options.copy(effort = it) }
    }
    if (modes.isNotEmpty()) {
        OptionList(S.newSession.permissions, modes, form.permissionMode(of = agent)) { form.options = form.options.copy(permissionMode = it) }
    }
    if (speeds.isNotEmpty()) {
        HStack(Modifier.fillMaxWidth(), spacing = Space.sp3) {
            Text(S.newSession.speed, css(FontSize.fs13, weight = FontWeight.Medium, lineHeight = 1.4f), color = Palette.inkSecondary)
            Spacer(Modifier.weight(1f))
            SpeedField(speeds, form.speed) { form.options = form.options.copy(speed = SpeedChange(it)) }
        }
    }
}

/** One of the agent's lists: its label, and a menu showing the chosen label. */
@Composable
private fun OptionList(title: String, choices: List<AgentOption>, value: String?, onSelect: (String) -> Unit) {
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        FieldLabel(title)
        DrawerSelect(options = choices.map { MenuOption(it.id, it.label) }, value = value, ariaLabel = title, onSelect = onSelect) {
            Text(choices.firstOrNull { it.id == value }?.label ?: "", css(FontSize.fs13), Modifier.fillMaxHeight(), lineLimit = 1)
        }
    }
}

/**
 * A21: the speed tier a new session starts at. One tier is a switch — on is that tier, off is the
 * standard speed; an agent that ever lists several gets a list with the standard speed first.
 */
@Composable
private fun SpeedField(speeds: List<AgentOption>, value: String?, onChange: (String?) -> Unit) {
    val only = speeds.singleOrNull()
    if (only != null) {
        Switch(isOn = value == only.id, label = S.newSession.speed) { onChange(if (it) only.id else null) }
        return
    }
    SelectMenu(
        options = listOf(MenuOption(STANDARD, S.composer.speedStandard)) + speeds.map { MenuOption(it.id, it.label) },
        value = value,
        ariaLabel = S.newSession.speed,
        align = PopoverAlign.end,
        onSelect = { onChange(if (it == STANDARD) null else it) },
    ) {
        Text(speeds.firstOrNull { it.id == value }?.label ?: S.composer.speedStandard, css(FontSize.fs13), Modifier.fillMaxHeight())
    }
}

/** The row id the list uses for "no tier", which the wire spells as null. */
private const val STANDARD = "standard"
