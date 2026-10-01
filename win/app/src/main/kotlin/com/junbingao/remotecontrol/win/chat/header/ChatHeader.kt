package com.junbingao.remotecontrol.win.chat.header

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.TodoItem
import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.IconBtn
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.btn
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.layout.LocalTrafficLightInset
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.delay

/**
 * `web/src/features/chat/ChatHeader.tsx`: the conversation's title and where it runs, then the
 * todo chip, the usage chip and Stop — on a hairline, 16 by 20 points of padding. Where the sidebar
 * is hidden the way back to Sessions comes first. The window keeps Windows' own title bar above
 * it, so it has no traffic lights to start after and moves no window (`LocalTrafficLightInset`).
 */
@Composable
fun ChatHeader(
    session: Session,
    agent: AgentInfo?,
    deviceName: String,
    todos: List<TodoItem>,
    stopping: Boolean,
    onStop: () -> Unit,
) {
    val model = LocalAppModel.current
    val layout = LocalLayoutClass.current
    val trafficLights = LocalTrafficLightInset.current
    val header = ChatHeaderModel(session, agent, todos, detail = model.settings.timelineDetail)
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp) {
        HStack(
            Modifier
                .fillMaxWidth()
                .padding(
                    top = Space.sp4,
                    bottom = Space.sp4,
                    start = if (layout.maxWidth1023) max(Space.sp5, trafficLights) else Space.sp5,
                    end = Space.sp5,
                ),
            spacing = Space.sp3,
        ) {
            if (layout.maxWidth1023) {
                IconBtn(LucideIcon.arrowLeft, size = 17.dp, label = S.nav.backToSessions) { model.router.go(Route.Sessions) }
            }
            Heading(session, deviceName, Modifier.weight(1f))
            HStack(spacing = Space.sp2) {
                header.todos?.let { TodosPopover(it, todos) }
                if (header.usage != null) UsageChip(session)
                if (header.offersStop) {
                    Disabled(stopping) {
                        Button(onStop, style = btn(ButtonVariant.standard, ButtonSize.small)) {
                            Text(if (stopping) S.chat.stopping else S.chat.stop, softWrap = false)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
    }
}

@Composable
private fun Heading(session: Session, deviceName: String, modifier: Modifier) {
    VStack(modifier, spacing = 0.dp, alignment = Alignment.Start) {
        Text(
            S.sessionTitle(session),
            css(FontSize.fs17, weight = FontWeight.SemiBold, lineHeight = 1.4f, tracking = -0.015f),
            Modifier.semantics { heading() },
            lineLimit = 1,
        )
        Text(subline(session, deviceName), css(FontSize.fs12, lineHeight = 1.45f, mono = true), color = Palette.inkTertiary, lineLimit = 1)
    }
}

/** `device:~/path · branch`, as the template literal writes it. */
private fun subline(session: Session, deviceName: String): String {
    val path = "$deviceName:${Format.tildePath(session.cwd)}"
    val git = session.git ?: return path
    return "$path · ${git.branch ?: "null"}"
}

/** `.pill.quiet.usage-chip`: the tokens the session has spent and how long its turn has run, ticking once a second while it runs. */
@Composable
private fun UsageChip(session: Session) {
    var now by remember { mutableLongStateOf(Format.nowMillis) }
    LaunchedEffect(session.turn != null) {
        while (true) {
            delay(if (session.turn == null) 3_600_000 else 1_000)
            now = Format.nowMillis
        }
    }
    val usage = ChatHeaderModel(session, agent = null, todos = emptyList(), detail = TimelineDetail.simple, now = now).usage ?: return
    Box(Modifier.height(28.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Text(usage, css(FontSize.fs12, mono = true), color = Palette.inkSecondary, softWrap = false)
    }
}
