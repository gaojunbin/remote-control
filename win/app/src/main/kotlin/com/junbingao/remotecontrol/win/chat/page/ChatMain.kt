package com.junbingao.remotecontrol.win.chat.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.PendingSend
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.agent
import com.junbingao.remotecontrol.win.app.device
import com.junbingao.remotecontrol.win.chat.composer.ComposerView
import com.junbingao.remotecontrol.win.chat.header.ChatHeader
import com.junbingao.remotecontrol.win.chat.resume.ResumeNotice
import com.junbingao.remotecontrol.win.chat.timeline.ChatTimeline
import com.junbingao.remotecontrol.win.chat.timeline.StatusLineModel
import com.junbingao.remotecontrol.win.chat.timeline.TranscriptHandlers
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch

/**
 * `.chat-main`: the conversation's column, on the surface — the header, the resume notice while a
 * resume is pending, the transcript, the two bars for what went wrong, and the composer.
 */
@Composable
fun ChatMain(chat: ChatStore, actions: ChatActions) {
    val model = LocalAppModel.current
    val stage = LocalPreviewStage.current
    val session = chat.session
    val device = model.device(session.deviceID)
    val agent = model.agent(session)
    VStack(Modifier.fillMaxSize().background(Palette.surface), spacing = 0.dp) {
        ChatHeader(
            session = session,
            agent = agent,
            deviceName = device?.name ?: session.deviceID,
            todos = chat.timeline.todos,
            stopping = actions.stopping,
            onStop = { model.tasks.launch { actions.stop() } },
        )
        // A35, §8 rule 17: the notice goes when `resume` does.
        session.resume?.let { resume ->
            ResumeNotice(resume, onSet = { actions.setResume(at = it) }, onCancel = { model.tasks.launch { actions.cancelResume() } })
        }
        key(chat.key) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChatTimeline(
                    chat = chat,
                    statusLine = StatusLineModel.of(session, agent, deviceOnline = device?.online ?: false, editingQueued = chat.queuedEdit != null),
                    onTakeover = { model.tasks.launch { actions.takeover() } },
                    handlers = TranscriptHandlers(
                        openFull = { actions.openFull(blockID = it) },
                        approve = { requestID, optionID -> actions.approve(requestID, optionID) },
                        answer = { requestID, answers -> actions.answer(requestID, answers) },
                    ),
                )
            }
        }
        val banner = if (stage == "chat.error") S.errors.approveFailed else actions.banner
        if (banner != null) ActionErrorBanner(banner, onDismiss = actions::dismiss)
        val unconfirmed = unconfirmedSends(chat, stage)
        if (unconfirmed.isNotEmpty()) {
            UnconfirmedBanner(
                pending = unconfirmed,
                onRetry = { model.tasks.launch { for (pending in unconfirmed) actions.retry(pending) } },
                onDismiss = { for (pending in unconfirmed) chat.dismiss(pending) },
            )
        }
        ComposerView(chat)
    }
}

/**
 * Sends whose delivery nobody can vouch for. A render shows the bar with one of its own, because a
 * delivery that went missing takes a network.
 */
private fun unconfirmedSends(chat: ChatStore, stage: String?): List<PendingSend> {
    if (stage == "chat.unconfirmed") {
        return listOf(PendingSend(id = "preview-unconfirmed", text = "", attachments = emptyList(), mode = SendMode.auto, status = PendingSend.Status.Uncertain))
    }
    return chat.pendingSends.filter { it.isUnconfirmed }
}
