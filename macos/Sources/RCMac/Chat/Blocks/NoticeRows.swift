import RCCore
import SwiftUI

/// `web/src/features/chat/blocks/NoticeRow.tsx`: one line in the notice voice
/// — an icon and a sentence, quiet unless it is a warning or an error.
struct NoticeLine: View {
    enum Tone { case plain, warn, error }

    let icon: LucideIcon
    let text: String
    var tone: Tone = .plain
    var code: String?

    var body: some View {
        HStack(alignment: .center, spacing: 6) {
            Icon(icon, size: 13)
            Text(verbatim: text)
                .css(FontSize.fs13)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
            if let code {
                Text(verbatim: code)
                    .css(FontSize.fs11, mono: true)
                    .foregroundStyle(Palette.inkTertiary)
                    .fixedSize()
            }
        }
        .foregroundStyle(ink)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var ink: Color {
        switch tone {
        case .plain: Palette.inkSecondary
        case .warn: Color(hex: 0x8A5312)
        case .error: Palette.danger
        }
    }
}

/// `NoticeRow`: the device's own notice, at its level.
struct NoticeRow: View {
    let notice: NoticePayload

    var body: some View {
        switch notice.level {
        case .error: NoticeLine(icon: .circleAlert, text: notice.text, tone: .error)
        case .warn: NoticeLine(icon: .alertTriangle, text: notice.text, tone: .warn)
        default: NoticeLine(icon: .info, text: notice.text)
        }
    }
}

/// `ErrorRow`: red, with the device's code after the sentence.
struct ErrorRow: View {
    let error: ErrorPayload

    var body: some View {
        NoticeLine(icon: .circleAlert, text: error.message, tone: .error,
                   code: (error.code ?? "").isEmpty ? nil : error.code)
    }
}

/// `TurnEndRow`: a turn that stopped or failed says so. A35: a turn the
/// vendor's usage limit ended is not a failure the reader can act on, so it
/// reads as a notice and says when the limit resets.
struct TurnEndRow: View {
    let turn: TurnCompletedPayload

    var body: some View {
        if let limit = turn.limit {
            NoticeLine(icon: .clock, text: ResumeWords.limitEndText(limit))
        } else if turn.stopReason == .interrupted {
            NoticeLine(icon: .alertTriangle, text: S.chat.turnInterrupted)
        } else {
            NoticeLine(icon: .alertTriangle, text: S.chat.turnFailed, tone: .error)
        }
    }
}

/// The web's `ResumeRow` — A35 (5.15): what the device did about the resume,
/// in the notice voice. The `fired` step draws nothing.
struct ResumeStepRow: View {
    let resume: ResumePayload

    var body: some View {
        if let text = ResumeWords.rowText(resume) {
            NoticeLine(icon: .clock, text: text)
        }
    }
}
