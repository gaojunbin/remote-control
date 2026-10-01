package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.scaledMetric
import com.junbingao.remotecontrol.android.haptics.selectionFeedback
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.Command
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.SlashDraft
import kotlinx.coroutines.launch

/**
 * The terminal's `/` menu, on the phone (amendment A27).
 *
 * A card over the keyboard, anchored above the message field: one row per command, `/name` in the
 * monospace face at the leading edge, its description after it and the argument placeholder in the
 * tertiary colour where the command takes one. It is filtered by prefix as more letters are typed,
 * sectioned by group only where there is more than one to tell apart, and never taller than eight
 * rows before it scrolls.
 *
 * `docs/DESIGN.md` § "The composer" holds the rule; `ChatStore` decides what is in it, so the web
 * app and this one draw the same list. [taking] is called after a row is taken: the screen's
 * background tap puts the keyboard away whenever a touch lands outside the message field, and a row
 * is outside it, so the field asks for the keyboard back rather than leaving the argument to be
 * typed after a second tap.
 */
@Composable
internal fun CommandPanel(chat: ChatStore, taking: () -> Unit) {
    // Bumped on every taken row, which is what the selection haptic fires on.
    var taken by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val rowHeight = scaledMetric(44.dp, Theme.Text.label)
    val headerHeight = scaledMetric(26.dp, Theme.Text.caption)
    val sections = chat.commandSections
    val showsHeaders = sections.size > 1
    // The card stops growing at eight rows and scrolls inside itself, which is measured rather
    // than guessed so a header counts towards the cap too.
    val rows = sections.sumOf { it.commands.size }
    val headers = if (showsHeaders) sections.size else 0
    val height = min(rowHeight * rows + headerHeight * headers, rowHeight * SlashDraft.visibleRows)
    val first = sections.firstOrNull()?.commands?.firstOrNull()?.id
    val shape = ContinuousShape(Theme.Radius.card)
    val hairline = Theme.hairline
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surface, shape)
            .border(0.5.dp, Theme.border, shape)
            // A row scrolling past the top or the bottom stops at the card's own corners rather
            // than at the square edge of its content.
            .clip(shape)
            // A row's whole business is what it writes into the field, so the tap gives the same
            // confirmation a picker does: one selection haptic.
            .selectionFeedback(taken)
            .testTag("composer.commands"),
    ) {
        // A maximum rather than a height: the card is as tall as its rows up to the cap, and
        // gives way when the keyboard leaves less than that, so the control row under the field
        // is never pushed off the screen by a long list.
        Column(Modifier.heightIn(max = height).verticalScroll(rememberScrollState())) {
            for (section in sections) {
                key(section.id) {
                    val title = section.title
                    if (showsHeaders && title != null) SectionHeader(title, Modifier.height(headerHeight))
                    for (command in section.commands) {
                        key(command.id) {
                            Button(
                                onClick = {
                                    taken += 1
                                    chat.take(command)
                                    // After the tap, not inside it: the background gesture that
                                    // lowers the keyboard runs on the same touch.
                                    scope.launch { taking() }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowHeight)
                                    .alpha(if (chat.commandsWaitForTurn) 0.4f else 1f)
                                    .disabledLook(!chat.commandsWaitForTurn)
                                    .drawBehind {
                                        if (command.id != first) {
                                            drawLine(hairline, Offset(0f, 0.25.dp.toPx()), Offset(size.width, 0.25.dp.toPx()), strokeWidth = 0.5.dp.toPx())
                                        }
                                    }
                                    .semantics { contentDescription = CommandWords.label(command) }
                                    .testTag("command.${command.name}"),
                                enabled = !chat.commandsWaitForTurn,
                            ) {
                                // One element, read as the label above says it rather than piece by piece.
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Theme.Space.medium)
                                        .clearAndSetSemantics { },
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    CommandLabel(command)
                                }
                            }
                        }
                    }
                }
            }
        }
        // A turn is running, so nothing here can be run yet and the card says so once, under the
        // rows, rather than on every one of them.
        if (chat.commandsWaitForTurn) {
            Text(
                L10n.string("Available when the turn finishes"),
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(hairline, Offset(0f, 0.25.dp.toPx()), Offset(size.width, 0.25.dp.toPx()), strokeWidth = 0.5.dp.toPx())
                    }
                    .padding(horizontal = Theme.Space.medium, vertical = Theme.Space.small)
                    .testTag("composer.commands.footer"),
                style = Theme.Text.caption,
                color = Theme.inkSecondary,
            )
        }
    }
}

/**
 * Where a group of commands came from: the device's own word, never translated, because it names
 * a directory on that machine. A header, so a screen reader can jump between the sources rather
 * than read every row to find out where the next one came from.
 */
@Composable
private fun SectionHeader(title: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = Theme.Space.medium), contentAlignment = Alignment.CenterStart) {
        Text(title, Modifier.semantics { heading() }, style = Theme.Text.caption, color = Theme.inkSecondary)
    }
}

/**
 * One command as it reads on a row and on the hint line under the card: the name in the monospace
 * face, what it does after it, and where the argument goes at the trailing edge. The description is
 * what gives way when the line is tight, because the name and the placeholder are what is acted on.
 */
@Composable
internal fun CommandLabel(command: Command) {
    PriorityRow(Modifier.fillMaxWidth(), spacing = Theme.Space.small) {
        Text(command.slash, Modifier.layoutPriority(2), style = Theme.mono, color = Theme.ink, lineLimit = 1)
        Text(
            command.description,
            Modifier.flexible().hugsLines(command.description, Theme.Text.meta, lineLimit = 1),
            style = Theme.Text.meta,
            color = Theme.inkSecondary,
            lineLimit = 1,
        )
        RowSpacer()
        command.argument?.let {
            Text(it, Modifier.layoutPriority(1), style = Theme.Text.metaMono, color = Theme.inkTertiary, lineLimit = 1)
        }
    }
}

/**
 * The line the panel hands over to: the first word is a complete command, so the card has closed
 * and what is left to say is where the argument goes — or, while a turn runs, that the command has
 * to wait for it.
 */
@Composable
internal fun CommandHintLine(chat: ChatStore, command: Command) {
    Box(Modifier.fillMaxWidth().testTag("composer.commandHint"), contentAlignment = Alignment.CenterStart) {
        if (chat.commandsWaitForTurn) {
            Text(L10n.string("Available when the turn finishes"), style = Theme.Text.caption, color = Theme.inkSecondary)
        } else {
            CommandLabel(command)
        }
    }
}

internal object CommandWords {
    fun label(command: Command): String {
        val argument = command.argument ?: return L10n.string("Command, %@, %@", command.slash, command.description)
        return L10n.string("Command, %@, %@, takes %@", command.slash, command.description, argument)
    }
}
