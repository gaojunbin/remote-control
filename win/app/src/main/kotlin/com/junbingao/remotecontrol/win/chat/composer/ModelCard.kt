package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonConfiguration
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.shared.LabelPair
import com.junbingao.remotecontrol.win.shared.SessionOptions
import com.junbingao.remotecontrol.win.strings.S

/**
 * A21 — the card the model chip opens: the speed tier and the model on one row, the effort slider
 * under it. It stays open until it is dismissed, so several changes can be made in one visit, and
 * the model list takes the card over rather than stacking a second popover on it. It reads the
 * session live, because a change is drawn the moment it is made and the device's reply confirms it
 * (A40: on a shared session, only the reply).
 */
@Composable
fun ModelCard(
    chat: ChatStore,
    models: List<AgentOption>,
    efforts: List<AgentOption>,
    speeds: List<AgentOption>,
    gates: ComposerGates,
    picking: Boolean = false,
    onSet: (SessionOptions) -> Unit,
) {
    var listing by remember { mutableStateOf(picking) }
    val stop = efforts.indexOfFirst { it.id == chat.session.effort }
    // The stop the thumb is on, which the word beside the model name reads: the card says what was
    // chosen the moment it is chosen.
    var index by remember { mutableIntStateOf(stop) }
    // A change from anywhere else — a `/model` in the terminal, another window — wins over the
    // position the thumb was left in.
    LaunchedEffect(stop) { index = stop }
    if (listing) {
        MenuList(Modifier.semantics { contentDescription = S.composer.model }) {
            for (model in models) {
                MenuItemRow(model.label, selected = chat.session.model == model.id) {
                    listing = false
                    if (chat.session.model != model.id) onSet(SessionOptions(model = model.id))
                }
            }
        }
    } else {
        Card(chat, models, efforts, speeds, gates, index, onIndex = { index = it }, onPick = { listing = true }, onSet = onSet)
    }
}

@Composable
private fun Card(
    chat: ChatStore,
    models: List<AgentOption>,
    efforts: List<AgentOption>,
    speeds: List<AgentOption>,
    gates: ComposerGates,
    index: Int,
    onIndex: (Int) -> Unit,
    onPick: () -> Unit,
    onSet: (SessionOptions) -> Unit,
) {
    val session = chat.session
    val modelText = ComposerLabels.label(models, session.model) ?: S.agentLabel(session.agent)
    val effortText = efforts.getOrNull(index)?.label ?: ComposerLabels.label(efforts, session.effort)
    val nameRow: @Composable () -> Unit = {
        SizedBox(LabelPair.pairs(models, efforts), alternative = { ModelNameLabel(it) }) {
            ModelNameLabel(LabelPair(model = modelText, effort = effortText))
        }
    }
    VStack(
        Modifier.widthIn(min = 220.dp).padding(start = Space.sp2, top = Space.sp1, end = Space.sp2, bottom = Space.sp3),
        spacing = Space.sp3,
        alignment = Alignment.Start,
    ) {
        HStack(spacing = Space.sp1) {
            if (speeds.isNotEmpty() && gates.canSet(SharedSetting.speed)) {
                SpeedToggle(speeds, current = session.speed, onSet = onSet)
            }
            if (models.isNotEmpty() && gates.canSet(SharedSetting.model)) {
                Button(onPick, style = ModelNameStyle, accessibilityLabel = S.composer.option(S.composer.model, modelText)) {
                    HStack(spacing = 6.dp) {
                        nameRow()
                        Icon(LucideIcon.chevronRight, size = 14.dp, color = Palette.inkTertiary)
                    }
                }
            } else {
                Box(Modifier.modelNameChrome(highlighted = false)) { nameRow() }
            }
        }
        if (efforts.isNotEmpty() && gates.canSet(SharedSetting.effort)) {
            EffortSlider(efforts, index = index, onIndex = onIndex) { picked ->
                val effort = efforts[picked]
                if (effort.id != chat.session.effort) onSet(SessionOptions(effort = effort.id))
            }
        }
    }
}

/** The model name and, beside it, the effort word the thumb is on. */
@Composable
private fun ModelNameLabel(pair: LabelPair) {
    HStack(spacing = 6.dp) {
        Text(pair.model ?: "", css(FontSize.fs14), softWrap = false)
        pair.effort?.let { Text(it, css(FontSize.fs13), color = Palette.inkSecondary, softWrap = false) }
    }
}

/** `.model-card-name`: the row that opens the model list, tinted under the pointer; `.static` where the model is not the device's to change. */
private object ModelNameStyle : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, modifier: Modifier) {
        Box(modifier.modelNameChrome(highlighted = configuration.isHovered || configuration.isPressed)) { configuration.label() }
    }
}

private fun Modifier.modelNameChrome(highlighted: Boolean): Modifier =
    background(if (highlighted) Palette.surfaceHover else Color.Transparent, RoundedCornerShape(Radius.sm))
        .padding(vertical = 6.dp, horizontal = Space.sp2)
