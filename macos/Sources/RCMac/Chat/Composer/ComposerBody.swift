import RCCore
import SwiftUI

/// `.composer-wrap`: everything of the composer, top to bottom — the files,
/// the errors, the takeover bar, the dictation's line, the edit's strip, the
/// box, the polish note and the control row — at most 800 points wide in the
/// middle of the chat column, 20 points in from its sides (16 below 1024).
struct ComposerBody: View {
    let composer: ComposerModel
    let stage: ComposerStage?
    @Environment(\.layoutClass) private var layout

    var body: some View {
        let gates = composer.gates
        VStack(alignment: .leading, spacing: 0) {
            if !composer.attachments.isEmpty {
                AttachmentChips(attachments: composer.attachments) { composer.removeAttachment(at: $0) }
                    .padding(.bottom, Space.sp2)
            }
            if !composer.errors.isEmpty {
                ComposerErrors(errors: composer.errors).padding(.bottom, Space.sp2)
            }
            if composer.voice.state == .error, let error = composer.voice.error {
                VoiceErrorLine(message: error) { composer.voice.dismissError() }
                    .padding(.bottom, Space.sp2)
            }
            if gates.terminalControlled {
                TakeoverBar(hint: Attach.attachHint(composer.agent), canTakeover: gates.canTakeover) {
                    Task { await composer.chat.takeover() }
                }
                .padding(.bottom, Space.sp2)
            }
            if let status = statusLine {
                VoiceStatusLine(text: status).padding(.bottom, Space.sp2)
            }
            if composer.editing != nil {
                EditingStrip(canCancel: composer.chat.canCancelEdit) { composer.cancelEdit() }
                    .padding(.bottom, Space.sp2)
            }
            ComposerBox(composer: composer, sendMenuOpen: stage?.opening == .sendMenu)
                .zIndex(ZLayer.sticky)
            switch composer.polish {
            case .polished, .failed:
                PolishNote(state: composer.polish) { composer.undoPolish() }.padding(.top, Space.sp2)
            case .idle, .polishing:
                EmptyView()
            }
            ComposerControlRow(composer: composer, opening: stage?.opening)
                .padding(.top, Space.sp3)
        }
        .padding(.horizontal, layout.maxWidth1023 ? Space.sp4 : Space.sp5)
        .padding(.bottom, Space.sp4)
        .frame(maxWidth: 800)
        .frame(maxWidth: .infinity)
    }

    /// The one quiet line above the field while the words are on their way.
    private var statusLine: String? {
        switch composer.voice.state {
        case .starting: return S.voice.connecting
        case .finishing: return S.voice.finishing
        case .listening: return S.voice.transcribing
        case .idle, .error: return composer.polish == .polishing ? S.voice.polishing : nil
        }
    }
}
