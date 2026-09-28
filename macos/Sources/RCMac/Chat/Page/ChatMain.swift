import RCCore
import SwiftUI

/// `.chat-main`: the conversation's column, on the surface — the header, the
/// resume notice while a resume is pending, the transcript, the two bars for
/// what went wrong, and the composer.
struct ChatMain: View {
    let chat: ChatStore
    let actions: ChatActions
    @Environment(MacAppModel.self) private var model
    @Environment(\.previewStage) private var stage

    var body: some View {
        let session = chat.session
        let device = model.device(session.deviceID)
        let agent = model.agent(for: session)
        VStack(spacing: 0) {
            ChatHeader(session: session, agent: agent, deviceName: device?.name ?? session.deviceID,
                       todos: chat.timeline.todos, stopping: actions.stopping,
                       onStop: { Task { await actions.stop() } })
            // A35, §8 rule 17: the notice goes when `resume` does.
            if let resume = session.resume {
                ResumeNotice(resume: resume, onSet: { await actions.setResume(at: $0) },
                             onCancel: { Task { await actions.cancelResume() } })
            }
            ChatTimeline(chat: chat,
                         statusLine: StatusLineModel.of(session: session, agent: agent,
                                                        deviceOnline: device?.online ?? false,
                                                        editingQueued: chat.queuedEdit != nil),
                         onTakeover: { Task { await actions.takeover() } },
                         handlers: TranscriptHandlers(
                            openFull: { await actions.openFull(blockID: $0) },
                            approve: { await actions.approve(requestID: $0, optionID: $1) },
                            answer: { await actions.answer(requestID: $0, answers: $1) }))
                .id(chat.key)
            if let banner = stage == "chat.error" ? S.errors.approveFailed : actions.banner {
                ActionErrorBanner(text: banner, onDismiss: actions.dismiss)
            }
            let unconfirmed = unconfirmedSends
            if !unconfirmed.isEmpty {
                UnconfirmedBanner(pending: unconfirmed,
                                  onRetry: { Task { for pending in unconfirmed { await actions.retry(pending) } } },
                                  onDismiss: { for pending in unconfirmed { chat.dismiss(pending) } })
            }
            ComposerView(chat: chat)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.surface)
    }

    /// Sends whose delivery nobody can vouch for. A render shows the bar with
    /// one of its own, because a delivery that went missing takes a network.
    private var unconfirmedSends: [PendingSend] {
        if stage == "chat.unconfirmed" {
            return [PendingSend(id: "preview-unconfirmed", text: "", attachments: [], mode: .auto, status: .uncertain)]
        }
        return chat.pendingSends.filter(\.isUnconfirmed)
    }
}
