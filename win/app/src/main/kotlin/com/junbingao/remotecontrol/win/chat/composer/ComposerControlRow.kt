package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.design.overlay.PopoverSide
import com.junbingao.remotecontrol.win.shared.LabelPair
import com.junbingao.remotecontrol.win.shared.SessionOptions
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch

/**
 * `ComposerBottomRow` (`docs/DESIGN.md` § "The control row", A43, A44): from the leading edge, Up
 * next, the model card, the permission mode. The web transcribes only on the gateway, whose
 * provider detects the language, so the row has no dictation language to offer. What the device
 * cannot change is drawn as the value the terminal chose (A17, A40).
 */
@Composable
fun ComposerControlRow(composer: ComposerModel, opening: ComposerStage.Opening?, modifier: Modifier = Modifier) {
    val session = composer.session
    val agent = composer.agent
    val gates = composer.gates
    val models = agent?.models.orEmpty()
    val efforts = agent?.efforts.orEmpty()
    val speeds = agent?.speeds.orEmpty()
    val modes = agent?.permissionModes.orEmpty()
    val modelText = ComposerLabels.label(models, session.model)
    val effortText = ComposerLabels.label(efforts, session.effort)
    val speedText = ComposerLabels.label(speeds, session.speed)
    // A40: the card is the one control for three settings, so it opens as soon as one of them is the
    // device's; the rows inside it decide.
    val cardLive = gates.canSet(SharedSetting.model) || gates.canSet(SharedSetting.effort) || gates.canSet(SharedSetting.speed)
    val hasCard = models.isNotEmpty() || efforts.isNotEmpty() || speeds.isNotEmpty()
    val cardText = ComposerLabels.line(LabelPair(model = modelText, effort = effortText))
    val modeText = ComposerLabels.label(modes, session.permissionMode)

    ChipFlow(modifier, spacing = Space.sp2) {
        UpNext(composer, initiallyOpen = opening == ComposerStage.Opening.upNext)
        if (cardLive) {
            if (hasCard) {
                Popover(
                    align = PopoverAlign.start,
                    side = PopoverSide.top,
                    chevron = false,
                    triggerStyle = ComposerChipStyle,
                    ariaLabel = S.composer.modelCard,
                    initiallyOpen = opening == ComposerStage.Opening.modelCard || opening == ComposerStage.Opening.modelList,
                    label = {
                        SizedBox(LabelPair.pairs(models, efforts), alternative = { ModelChipLabel(it, glyph = speeds.isNotEmpty()) }) {
                            ModelChipLabel(LabelPair(model = modelText, effort = effortText), glyph = speedText != null,
                                           fallback = S.agentLabel(session.agent))
                        }
                    },
                ) {
                    ModelCard(composer.chat, models, efforts, speeds, gates, picking = opening == ComposerStage.Opening.modelList,
                              onSet = { composer.setOption(it) })
                }
            }
        } else if (cardText.isNotEmpty()) {
            TerminalSettingChip(name = S.composer.modelCard, text = cardText, speed = speedText)
        }
        if (gates.canSet(SharedSetting.permissionMode)) {
            if (modes.isNotEmpty()) {
                Popover(
                    align = PopoverAlign.start,
                    side = PopoverSide.top,
                    triggerStyle = ComposerChipStyle,
                    ariaLabel = S.composer.permissionMode,
                    initiallyOpen = opening == ComposerStage.Opening.permissions,
                    label = { Text(modeText ?: S.composer.permissionMode, css(FontSize.fs12), softWrap = false) },
                ) { close ->
                    MenuList(Modifier.semantics { contentDescription = S.composer.permissionMode }) {
                        for (mode in modes) {
                            MenuItemRow(mode.label, selected = session.permissionMode == mode.id) {
                                composer.setOption(SessionOptions(permissionMode = mode.id))
                                close()
                            }
                        }
                    }
                }
            }
        } else if (modeText != null) {
            TerminalSettingChip(name = S.composer.permissionMode, text = modeText, speed = null)
        }
    }
}

/**
 * Every change the card and the menu make goes through the store, which draws it at once and puts
 * the previous value back on a refusal (A21) — except on a shared session, where the reply is what
 * is drawn (A40). A refusal is the page's banner, as it is on the web.
 */
fun ComposerModel.setOption(patch: SessionOptions) {
    host.tasks.launch {
        chat.set(model = patch.model, permissionMode = patch.permissionMode, effort = patch.effort, speed = patch.speed)
    }
}
