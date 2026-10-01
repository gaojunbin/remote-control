package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AgentLogo
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.CodeText
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.TextField
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.GroupedListScope
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.Segment
import com.junbingao.remotecontrol.android.system.SegmentedControl
import com.junbingao.remotecontrol.android.system.Toggle
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.GitStatus
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.core.state.RelativeTime

// The sections of `NewSessionSheet`, in the order `docs/DESIGN.md` gives every form.

internal fun GroupedListScope.deviceSection(form: NewSessionForm, online: List<Device>, actions: NewSessionForm.Actions) {
    section(key = "device", header = { FormHeader("Device") }) {
        row(key = "device", style = FormRow) {
            FormRowContent(separator = online.isEmpty()) {
                FormPicker(
                    title = null,
                    value = online.firstOrNull { it.deviceID == form.deviceID }?.name ?: "",
                    items = online.map { device ->
                        MenuItem.Action(device.name, checked = device.deviceID == form.deviceID, tag = "newsession.device.${device.deviceID}") {
                            actions.selectDevice(device.deviceID)
                        }
                    },
                    tag = "newsession.device",
                    description = L10n.string("Device"),
                )
            }
        }
        if (online.isEmpty()) {
            row(key = "noDevice", style = FormRow) {
                FormRowContent(separator = false) {
                    Text(L10n.string("No device is online. Add one from the Devices tab."), style = SystemFont.footnote, color = Theme.inkSecondary)
                }
            }
        }
    }
}

/**
 * `docs/DESIGN.md` § "Agents": four agents do not fit a segmented control by name, so each segment
 * carries the agent's logo alone and the line under the control names the one that is chosen. The
 * logo is for the eye only — assistive technology reads the name.
 */
internal fun GroupedListScope.agentSection(form: NewSessionForm, device: Device?, agent: AgentInfo?, actions: NewSessionForm.Actions) {
    val agents = device?.availableAgents.orEmpty()
    section(key = "agent", header = { FormHeader("Agent") }) {
        row(key = "agents", style = FormRow) {
            FormRowContent(separator = agent != null, top = FormMetrics.segmentedTop, bottom = FormMetrics.segmentedBottom) {
                SegmentedControl(
                    agents.map { info -> Segment(info.displayName, AgentLogo.vector(info.agent), tag = "newsession.agent.${info.agent}") },
                    selected = agents.indexOfFirst { it.agent == form.agentID },
                    onSelect = { index -> actions.selectAgent(agents[index].agent) },
                    fill = true,
                    tag = "newsession.agent",
                )
            }
        }
        if (agent != null) {
            row(key = "agentLine", style = FormRow) {
                FormRowContent(separator = false) {
                    Row(
                        Modifier.semantics(mergeDescendants = true) {}.testTag("newsession.agentLine"),
                        horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(agent.displayName, style = Theme.Text.meta, color = Theme.inkSecondary)
                        Text(subtitle(agent), style = Theme.mono, color = Theme.inkSecondary)
                    }
                }
            }
        }
    }
}

/**
 * What the agent runs and how hard, in the order `docs/DESIGN.md` gives every form: model, effort,
 * permissions, and the speed tier after them where the agent offers one (A21). Each list is the
 * agent's own, and a section whose list is empty is not drawn at all (A25).
 */
internal fun GroupedListScope.settingsSections(form: NewSessionForm, agent: AgentInfo?) {
    if (agent == null) return
    if (agent.models.isNotEmpty()) {
        pickerSection("model", "Model", agent.models, form.modelID, "newsession.model") { form.modelID = it }
    }
    if (agent.supports(AgentCapability.effort) && agent.efforts.isNotEmpty()) {
        pickerSection("effort", "Effort", agent.efforts, form.effort, "newsession.effort") { form.effort = it }
    }
    if (agent.permissionModes.isNotEmpty()) {
        pickerSection("permissions", "Permissions", agent.permissionModes, form.permissionMode, "newsession.permissions") { form.permissionMode = it }
    }
    if (agent.speeds.isNotEmpty()) {
        section(key = "speed", header = { FormHeader("Speed") }) {
            row(key = "speed", style = FormRow) {
                FormRowContent(separator = false) { SpeedPicker(agent.speeds, form.speed, "newsession.speed") { form.speed = it } }
            }
        }
    }
}

private fun GroupedListScope.pickerSection(
    key: String,
    title: String,
    options: List<AgentOption>,
    selection: String,
    tag: String,
    choose: (String) -> Unit,
) {
    section(key = key, header = { FormHeader(title) }) {
        row(key = key, style = FormRow) {
            FormRowContent(separator = false) {
                FormPicker(
                    title = L10n.string(title),
                    value = options.firstOrNull { it.id == selection }?.label ?: "",
                    items = options.map { option -> MenuItem.Action(option.label, checked = option.id == selection, tag = "$tag.${option.id}") { choose(option.id) } },
                    tag = tag,
                )
            }
        }
    }
}

internal fun GroupedListScope.directorySection(form: NewSessionForm, actions: NewSessionForm.Actions) {
    val recent = form.recent
    section(
        key = "directory",
        header = {
            FormHeader("Working directory") {
                Button(onClick = actions.browse, Modifier.testTag("newsession.browse")) {
                    Text(L10n.string("Browse"), style = SystemFont.caption.weight(FontWeight.Medium), color = Theme.ink)
                }
            }
        },
    ) {
        row(key = "cwd", style = FormRow) {
            FormRowContent(separator = recent.isNotEmpty()) {
                TextField(
                    L10n.string("~/dev/project"),
                    form.cwd,
                    { form.cwd = it },
                    style = Theme.monoBody,
                    onSubmit = actions.refreshGit,
                    tag = "newsession.cwd",
                )
            }
        }
        recent.forEachIndexed { index, entry ->
            row(
                key = "recent.${entry.path}",
                style = FormRow,
                onClick = {
                    form.cwd = entry.path
                    actions.refreshGit()
                },
            ) {
                FormRowContent(separator = index < recent.lastIndex) {
                    Row(Modifier.fillMaxWidth().heightIn(min = Theme.Touch.minimum), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CodeText(entry.path, Modifier.weight(1f), color = Theme.ink)
                        Text(RelativeTime.short(since = entry.lastUsed), style = SystemFont.caption, color = Theme.inkSecondary)
                    }
                }
            }
        }
    }
}

internal fun GroupedListScope.gitSection(form: NewSessionForm, agent: AgentInfo?) {
    val git = form.git ?: return
    if (!git.isRepo) return
    val isolates = agent?.supports(AgentCapability.worktree) == true
    section(
        key = "git",
        header = { FormHeader("Git") },
        footer = if (isolates) {
            {
                Text(
                    L10n.string("A worktree gives the agent its own checkout, so your working copy stays untouched."),
                    style = SystemFont.caption,
                    color = SystemColor.secondaryLabel,
                )
            }
        } else {
            null
        },
    ) {
        row(key = "branch", style = FormRow) {
            FormRowContent(separator = isolates) {
                Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small), verticalAlignment = Alignment.CenterVertically) {
                    Text(git.branch ?: "detached", style = Theme.monoBody, color = Theme.ink)
                    Text(gitDetail(git), style = SystemFont.footnote, color = Theme.inkSecondary)
                }
            }
        }
        if (isolates) {
            row(key = "worktree", style = FormRow) {
                FormRowContent(separator = false) {
                    Toggle(L10n.string("Isolate in a worktree"), form.worktree, { form.worktree = it }, tag = "newsession.worktree")
                }
            }
        }
    }
}

internal fun GroupedListScope.errorSection(message: String) {
    item(key = "error.gap") { Spacer(Modifier.height(RowMetrics.headerlessGap)) }
    section(key = "error") {
        row(key = "error", style = FormRow) {
            FormRowContent(separator = false) { Text(message, style = SystemFont.footnote, color = Theme.danger) }
        }
    }
}

/**
 * Amendment A21: the standard speed and every tier the agent lists, as one list picker. Forms use
 * it; the composer's card uses the lightning toggle.
 */
@androidx.compose.runtime.Composable
fun SpeedPicker(speeds: List<AgentOption>, selection: SpeedChange, tag: String, choose: (SpeedChange) -> Unit) {
    val standard = L10n.string("Standard")
    val chosen = (selection as? SpeedChange.Tier)?.let { tier -> speeds.firstOrNull { it.id == tier.id }?.label } ?: standard
    FormPicker(
        title = L10n.string("Speed"),
        value = chosen,
        items = listOf(MenuItem.Action(standard, checked = selection == SpeedChange.Standard, tag = "$tag.standard") { choose(SpeedChange.Standard) }) +
            speeds.map { option ->
                MenuItem.Action(option.label, checked = selection == SpeedChange.Tier(option.id), tag = "$tag.${option.id}") { choose(SpeedChange.Tier(option.id)) }
            },
        tag = tag,
    )
}

/** What the device detected, after the agent's name: the version it found and the model that agent would start on. */
private fun subtitle(agent: AgentInfo): String =
    listOfNotNull(agent.version, agent.modelLabel(agent.defaultModel)).joinToString(" · ")

private fun gitDetail(git: GitStatus): String {
    val parts = mutableListOf(L10n.string(if (git.dirty == true) "dirty" else "clean"))
    git.ahead?.takeIf { it > 0 }?.let { parts.add(L10n.string("%lld ahead", it)) }
    git.behind?.takeIf { it > 0 }?.let { parts.add(L10n.string("%lld behind", it)) }
    return parts.joinToString(" · ")
}
