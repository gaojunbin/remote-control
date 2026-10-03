package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AgentChip
import com.junbingao.remotecontrol.android.design.CodeText
import com.junbingao.remotecontrol.android.design.FolderGlyph
import com.junbingao.remotecontrol.android.design.SessionOriginLabel
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.unseenDot
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.RowStyle
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.agentLabel
import com.junbingao.remotecontrol.core.state.RelativeTime
import com.junbingao.remotecontrol.core.state.dotTone

/**
 * Three lines (`docs/DESIGN.md` § "The session row"): the title with the time at the trailing
 * edge; the agent chip with the dot and where the session came from at the trailing edge; and the
 * working directory alone, after a folder glyph, so the path has the whole width and the second
 * line says two things, not three.
 *
 * [online]: a session on a machine that is not reachable shows a grey dot whatever it last
 * reported, so the row never claims work is under way.
 */
@Composable
fun SessionRow(session: Session, online: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = label(session) },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
            Text(
                session.title.ifEmpty { L10n.string("Untitled session") },
                // Amendment A47: in the gutter `sessionRowLayout` leaves, on the title's line.
                Modifier.weight(1f).alignByBaseline().unseenDot(session.unseen, gutter = SessionRowGutter),
                style = Theme.Text.title,
                color = Theme.ink,
                lineLimit = 1,
            )
            Text(RelativeTime.short(since = session.updatedAt), Modifier.alignByBaseline(), style = Theme.Text.caption, color = Theme.inkSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
            AgentChip(session.agent)
            Spacer(Modifier.weight(1f).widthIn(min = Theme.Space.small))
            if (session.archived) {
                Text(L10n.string("Archived"), style = Theme.Text.caption, color = Theme.inkSecondary)
                Text("·", style = Theme.Text.caption, color = Theme.inkSecondary)
            }
            SessionOriginLabel(session.dotTone(online = online), session.originLabel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            // The path has the line to itself and truncates from the head, so the folder at its end
            // is what survives a tight row.
            FolderGlyph()
            CodeText(session.cwd, font = Theme.Text.metaMono)
        }
    }
}

/** What a screen reader hears for the row, as one sentence; the red dot is said after the title it sits beside (A47). */
private fun label(session: Session): String {
    val title = session.title.ifEmpty { "Untitled session" }
    val parts = mutableListOf(title)
    if (session.unseen) parts.add(L10n.string("not yet opened"))
    parts.addAll(listOf(session.agentLabel, session.originLabel))
    if (session.archived) parts.add("archived")
    parts.add(session.cwd)
    return parts.joinToString(", ")
}

/**
 * What a session row says beside the dot: where the session came from, not what it is doing
 * (`docs/DESIGN.md` § "The session row says where it came from"). A terminal started it, or this
 * app or the browser did — and which of the two pressed New session is nobody's business
 * afterwards, so both read the same word. The state is the dot's alone.
 */
val Session.originLabel: String
    get() = L10n.string(if (origin == EventSource.terminal) "Terminal" else "Remote Control")

/**
 * `sessionRowLayout()`: rows sit on one soft surface with room to breathe and no rule between
 * them; 14 points of vertical space already tells one from the next.
 */
val RowStyle.Companion.sessionRowLayout: RowStyle
    get() = RowStyle(
        insets = PaddingValues(start = SessionRowGutter, top = 14.dp, end = Theme.Space.medium, bottom = 14.dp),
        separator = false,
    )

/** The row's leading inset, which is also the gutter the red dot sits in (A47). */
private val SessionRowGutter = Theme.Space.medium
