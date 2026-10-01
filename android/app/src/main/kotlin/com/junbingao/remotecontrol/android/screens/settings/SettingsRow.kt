package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.Menu
import com.junbingao.remotecontrol.android.system.MenuItem

/**
 * The two lines every settings row is built from: the title in the label weight, and one sentence
 * under it in the secondary ink.
 *
 * `docs/DESIGN.md` § "The Settings screen": the sentence belongs to the row, not to a footnote
 * under the group, and a row whose state has something to say — a blocked permission, a gateway
 * with no model, a refused write — says it here in place of the sentence.
 *
 * [title] is a catalogue key, looked up here; [sentence] is a string that has already been said,
 * because most of them are built outside a screen, by a store or by `ResumeText`, and one rule for
 * all of them is the only rule worth having. A string `L10n.string` produced goes on saying what it
 * said in the language it was built in, so every group holds the interface language and is rebuilt
 * when it changes.
 */
@Composable
fun SettingsLabel(
    title: String,
    sentence: String,
    modifier: Modifier = Modifier,
    /**
     * The one row that is not the ink: signing out is the only thing here a person can regret, and
     * the colour says so before the sentence does.
     */
    tint: Color = Theme.ink,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(L10n.string(title), style = Theme.Text.label, color = tint)
        // The row's height is a floor and not a clip: a sentence that wraps makes its own row taller
        // and moves nothing else.
        Text(sentence, style = Theme.Text.caption, color = Theme.inkSecondary)
    }
}

/**
 * A settings row: the two lines on the left, a control at the trailing edge, centred on them.
 *
 * A switch and a button take a [SettingsLabel] as their own label instead, which keeps the whole
 * row one control. Everything else is laid out here, because a control that lays itself out
 * beside a label drops its own choice onto a second line as soon as the sentence beside it wraps.
 */
@Composable
fun SettingsRow(title: String, sentence: String, control: @Composable () -> Unit) {
    Row(
        Modifier.settingsRowPadding(),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLabel(title, sentence, Modifier.weight(1f))
        control()
    }
}

/**
 * The label of a row that is itself the action — a button or a link: the two lines, and a chevron
 * where the row leads somewhere. It carries the row's padding, so the whole row is the button's
 * target.
 */
@Composable
fun SettingsActionLabel(
    title: String,
    sentence: String,
    tint: Color = Theme.ink,
    /** Whether the row opens another screen or a sheet, which the chevron says. */
    leadsOn: Boolean = false,
) {
    Row(
        Modifier.settingsRowPadding(),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLabel(title, sentence, Modifier.weight(1f), tint)
        if (leadsOn) Icon(Sf.chevronRight, font = SystemFont.footnote.weight(FontWeight.SemiBold), tint = Theme.inkTertiary)
    }
}

/** One choice of a [SettingsMenuRow]: the value it stands for, and its words in the menu. */
data class SettingsChoice<T>(val tag: T, val title: String)

/**
 * A row whose control is a menu of more than two choices: the chosen word and a chevron at the
 * trailing edge, and the whole row opens the menu (`docs/DESIGN.md` § "The Settings screen",
 * "Controls by the shape of the choice"). The menu is a picker — its rows with the chosen one
 * checked — and its label is the row, so the row is the target.
 */
@Composable
fun <T> SettingsMenuRow(
    title: String,
    sentence: String,
    selection: T,
    onSelect: (T) -> Unit,
    /** The word for what is chosen, drawn at the trailing edge. */
    chosen: String,
    options: List<SettingsChoice<T>>,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val items = options.map { option ->
        MenuItem.Action(option.title, checked = option.tag == selection) { onSelect(option.tag) }
    }
    Menu(items, if (tag != null) Modifier.testTag(tag) else Modifier, enabled = enabled) {
        Row(
            Modifier.settingsRowPadding(),
            horizontalArrangement = Arrangement.spacedBy(Theme.Space.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsLabel(title, sentence, Modifier.weight(1f))
            // Measured before the label, so the chosen word keeps its width and the sentence wraps.
            Row(
                Modifier.alpha(if (enabled) 1f else 0.5f),
                horizontalArrangement = Arrangement.spacedBy(Theme.Space.hair),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(chosen, style = Theme.Text.label, color = Theme.ink, lineLimit = 1)
                Icon(Sf.chevronUpChevronDown, font = SystemFont.footnote.weight(FontWeight.SemiBold), tint = Theme.ink)
            }
        }
    }
}
