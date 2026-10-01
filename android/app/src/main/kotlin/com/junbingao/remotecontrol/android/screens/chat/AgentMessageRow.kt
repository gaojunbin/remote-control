package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.UserMessagePayload

/**
 * Amendment A34: words another agent put into the conversation — a teammate session's report, a
 * background task's notification.
 *
 * Claude Code files them as user turns, but nobody typed them, so they are not the person's side
 * of the conversation: they sit on the left with the agent's own output, as a muted block on the
 * quiet surface captioned "from another agent", at the width assistant text uses and in no bubble
 * at all (`docs/DESIGN.md` § "The timeline"). The gray bubble on the right holds the person's own
 * words and nothing else.
 *
 * The device has already reduced the text to who reported and what they said, with the envelope
 * and every `<system-reminder>` removed (A30), so the row prints exactly what it was given.
 */
@Composable
internal fun AgentMessageRow(payload: UserMessagePayload) {
    val shape = ContinuousShape(Theme.Radius.control)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme.surfaceSunken, shape)
            .border(0.5.dp, Theme.border, shape)
            .padding(horizontal = Theme.Space.medium, vertical = Theme.Space.small)
            // Nobody said this, so a screen reader is not told the person did.
            .semantics(mergeDescendants = true) { contentDescription = L10n.string("From another agent: %@", payload.text) }
            .testTag("chat.message.agent"),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.hair),
    ) {
        // Where a terminal message says where it was typed, this says that no one typed it.
        Text(L10n.string("from another agent"), style = Theme.Text.caption, color = Theme.inkSecondary)
        SelectionContainer {
            Text(payload.text, style = Theme.Text.label, color = Theme.inkSecondary)
        }
    }
}
