package com.junbingao.remotecontrol.win.sessions

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.SessionListLayout
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.design.AgentChip
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.RowHeight
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.StatusDot
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.devices.ExactFrame
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import com.junbingao.remotecontrol.win.unseen.showsUnseenDot
import com.junbingao.remotecontrol.win.unseen.unseenTitle
import com.junbingao.remotecontrol.win.unseen.unseenValue

/**
 * `web/src/features/sessions/SessionRow.tsx`: three lines, as `docs/DESIGN.md` § "The session row"
 * rules — the title with the time at the trailing edge; the agent at the leading edge with the dot
 * and the session's origin at the trailing edge; the working directory alone after a folder,
 * truncated from the head so the folder it ends in survives. The state is the dot's colour alone.
 * Every row reserves the gutter the close action sits in (A39), and a session nobody has opened
 * since it stopped working carries a red dot in its leading gutter (A47).
 */
@Composable
internal fun SessionRow(
    session: Session,
    online: Boolean,
    /** A preview stage's: the close question drawn open. */
    asksToClose: Boolean = false,
) {
    val model = LocalAppModel.current
    val narrow = LocalLayoutClass.current.maxWidth640
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    // The tint eases in; the close action does not, as the web's opacity has no transition.
    val tint by animateColorAsState(if (isHovered) Palette.hover else Color.Transparent, Motion.ease(Motion.durFast, LocalReduceMotion.current))
    Box(Modifier.fillMaxWidth().height(RowHeight.rowHThree).background(tint).hoverable(hover), contentAlignment = Alignment.CenterEnd) {
        // The padding before the lines is the gutter a red dot sits in (A47).
        val gutter = if (narrow) Space.sp4 else Space.sp5
        Button(
            action = { model.router.go(Route.Chat(deviceId = session.deviceID, sessionId = session.sessionID)) },
            modifier = Modifier.fillMaxSize().padding(end = 40.dp).unseenValue(model.showsUnseenDot(session)),
            accessibilityLabel = S.sessions.open,
        ) {
            ExactFrame(Modifier.fillMaxSize(), height = RowHeight.rowHThree) {
                VStack(Modifier.padding(horizontal = gutter), spacing = 3.dp, alignment = Alignment.Start) {
                    TitleLine(session, unseen = model.showsUnseenDot(session), gutter = gutter)
                    AgentLine(session, online)
                    PathLine(session)
                }
            }
        }
        // A39: only a session the device drives can be closed. A terminal holds its own row until
        // it exits, and a row in the Archive comes back by being written to, so neither offers
        // anything.
        if (SessionListLayout.offersClose(session)) {
            SessionCloseButton(
                session, online,
                asksOnAppear = asksToClose,
                modifier = Modifier.padding(end = Space.sp3).alpha(if (isHovered || narrow) 1f else 0f),
            )
        }
    }
}

/** The title and the time; the red dot (A47) is centred in the padding the lines start after, `gutter`. */
@Composable
private fun TitleLine(session: Session, unseen: Boolean, gutter: Dp) {
    HStack(Modifier.fillMaxWidth(), spacing = Space.sp3) {
        Text(
            S.sessionTitle(session),
            css(FontSize.fs15, weight = FontWeight.SemiBold, lineHeight = 1.4f, tracking = -0.01f),
            Modifier.weight(1f).unseenTitle(unseen, reach = gutter, gutter = gutter),
            lineLimit = 1,
        )
        Text(Format.relativeTime(session.updatedAt), css(FontSize.fs12, lineHeight = 1.45f), color = Palette.inkTertiary, lineLimit = 1)
    }
}

/**
 * Where the session came from, whatever it is doing (`docs/DESIGN.md` § "The session row says where
 * it came from"); a hand-archived row says so before its origin.
 */
@Composable
private fun AgentLine(session: Session, online: Boolean) {
    val origin = S.sessionOriginLabel(session)
    HStack(Modifier.fillMaxWidth(), spacing = Space.sp3) {
        AgentChip(session.agent)
        Spacer(Modifier.weight(1f))
        HStack(spacing = Space.sp2) {
            StatusDot(session.state, session.control, online = online)
            Text(
                if (session.archived) "${S.sessions.archived} · $origin" else origin,
                css(FontSize.fs13, lineHeight = 1.45f),
                color = Palette.inkSecondary,
                lineLimit = 1,
            )
        }
    }
}

/** A folder drawn to the same rule as the laptop before a device, then the path in mono, cut at its head. */
@Composable
private fun PathLine(session: Session) {
    HStack(spacing = 5.dp) {
        Icon(LucideIcon.folder, size = 14.dp, strokeWidth = 1.5f, color = Palette.ink)
        HeadTruncatedText(Format.tildePath(session.cwd), css(FontSize.fs12, lineHeight = 1.45f, mono = true), color = Palette.inkSecondary)
    }
}
