package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.PromptShield
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.monospacedDigit
import com.junbingao.remotecontrol.android.design.scaledMetric
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.Menu
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.ComposerControl
import com.junbingao.remotecontrol.core.state.DictationLanguage
import com.junbingao.remotecontrol.core.state.SettingsStore
import com.junbingao.remotecontrol.core.state.TerminalSetting

// Amendment A44: on the phone the composer's control row is icons, because four words do not fit
// beside Send (`docs/DESIGN.md` § "The control row"). Each is a 44-point target that opens exactly
// what its words opened, and each accessible name carries the value the icon draws. The order is
// `ComposerControl`'s; the model card's gauge is `ModelCardChip`.

/** One glyph of the row, centred on its 44-point target. */
@Composable
internal fun ControlGlyph(glyph: @Composable () -> Unit) {
    Box(Modifier.sizeIn(minWidth = Theme.Touch.minimum, minHeight = Theme.Touch.minimum), contentAlignment = Alignment.Center) {
        glyph()
    }
}

/**
 * Amendment A43: what waits behind the turn — a notepad with the count in a small dark badge at
 * its top-right corner. It opens the Up next sheet.
 */
@Composable
internal fun UpNextControl(count: Int, open: () -> Unit) {
    val badge = scaledMetric(15.dp, SystemFont.caption2)
    var badgeSize by remember { mutableStateOf(IntSize.Zero) }
    Button(
        onClick = open,
        modifier = Modifier
            .semantics {
                contentDescription = L10n.string("Up next")
                stateDescription = ComposerControl.upNextValue(count)
            }
            .testTag("composer.queue"),
    ) {
        ControlGlyph {
            Layout(
                content = {
                    // The clear ring cut around the badge, as a symbol's own badge has, so the
                    // digits never run into the notepad under them: the badge grown by the gap,
                    // on the badge's own centre, so the ring is even all round. The notepad is
                    // drawn around the ring rather than cleared under it, so no layer of its own
                    // is needed.
                    Icon(
                        Sf.noteText,
                        Modifier.drawWithContent {
                            if (badgeSize == IntSize.Zero) {
                                drawContent()
                                return@drawWithContent
                            }
                            val gap = UpNextMetrics.gap.toPx()
                            val right = size.width + badge.toPx() * 0.55f + gap
                            val top = -badge.toPx() * 0.5f - gap
                            val width = badgeSize.width + gap * 2
                            val height = badgeSize.height + gap * 2
                            val ring = Path().apply {
                                addRoundRect(RoundRect(right - width, top, right, top + height, CornerRadius(height / 2)))
                            }
                            clipPath(ring, ClipOp.Difference) { this@drawWithContent.drawContent() }
                        },
                        tint = Theme.ink,
                    )
                    Box(
                        Modifier
                            .onSizeChanged { badgeSize = it }
                            .sizeIn(minWidth = badge, minHeight = badge)
                            .background(Theme.ink, CapsuleShape)
                            .padding(horizontal = 4.dp)
                            .clearAndSetSemantics { },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$count", style = SystemFont.caption2.weight(FontWeight.SemiBold).monospacedDigit(), color = Theme.onAccent, lineLimit = 1)
                    }
                },
            ) { measurables, constraints ->
                val icon = measurables[0].measure(constraints)
                val count = measurables[1].measure(Constraints())
                layout(icon.width, icon.height) {
                    icon.place(0, 0)
                    // Its top-right corner on the notepad's, then moved out by half its own size,
                    // so its middle stands on the notepad's top-right corner.
                    val right = icon.width + (badge.toPx() * 0.55f).toInt()
                    count.place(right - count.width, -(badge.toPx() * 0.5f).toInt())
                }
            }
        }
    }
}

internal object UpNextMetrics {
    val gap = 1.5.dp
}

/**
 * Amendment A44: the language the phone's own recogniser listens for, which the composer draws
 * only while the phone is the one listening. The list is the recogniser's, with no Automatic: it
 * cannot detect a language.
 */
@Composable
internal fun DictationLanguageControl(settings: SettingsStore) {
    // The language read as the recogniser hears it — a legacy `auto` shows as Chinese — and
    // written only when one is picked.
    val items = DictationLanguage.codes.map { code ->
        MenuItem.Action(DictationLanguage.name(code, settings.language), checked = code == settings.dictationLanguage) {
            settings.voiceLanguage = code
        }
    }
    Foreground(Theme.ink) {
        Menu(
            items,
            Modifier
                .semantics {
                    contentDescription = L10n.string("Dictation language")
                    stateDescription = DictationLanguage.name(settings.dictationLanguage, settings.language)
                }
                .testTag("composer.language"),
        ) {
            ControlGlyph { Icon(Sf.translate) }
        }
    }
}

/** What the session may do: a plain list of the agent's own modes with the current one marked and nothing else on it, behind the shield. */
@Composable
internal fun PermissionControl(chat: ChatStore, agent: AgentInfo?) {
    val model = LocalAppModel.current
    val current = chat.session.permissionMode ?: agent?.defaultPermissionMode ?: ""
    val items = (agent?.permissionModes ?: emptyList()).map { option ->
        MenuItem.Action(option.label, checked = option.id == current) { model.perform { chat.set(permissionMode = option.id) } }
    }
    Foreground(Theme.ink) {
        Menu(
            items,
            Modifier
                .semantics {
                    contentDescription = L10n.string("Permissions")
                    stateDescription = agent?.permissionModeLabel(chat.session.permissionMode) ?: chat.session.permissionMode ?: ""
                }
                .testTag("composer.permissions"),
        ) {
            ControlGlyph { PromptShield() }
        }
    }
}

/**
 * Amendment A17: what the terminal chose, behind the same icon its control would have. A tap shows
 * the value in a menu with nothing to choose, so the reader still learns what the terminal set and
 * nobody reaches for a control that cannot move.
 */
@Composable
internal fun TerminalValueControl(setting: TerminalSetting, glyph: @Composable () -> Unit) {
    val values = buildList {
        add(MenuItem.Action(setting.text, enabled = false))
        // Amendment A21: the tier it runs at, which the bolt only draws.
        setting.speed?.let { add(MenuItem.Action(it, symbol = Sf.boltFill, enabled = false)) }
    }
    Foreground(Theme.ink) {
        Menu(
            listOf(MenuItem.Section(L10n.string("Set in the terminal"), values)),
            Modifier
                .semantics {
                    contentDescription = setting.field.label
                    stateDescription = setting.spokenValue
                }
                .testTag("composer.readonly.${setting.field.rawValue}"),
        ) {
            ControlGlyph { glyph() }
        }
    }
}
