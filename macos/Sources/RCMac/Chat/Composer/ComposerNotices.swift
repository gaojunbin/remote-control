import SwiftUI

/// `.composer-errors`: why a send, an attach or a command failed, in the
/// danger ink, one line each.
struct ComposerErrors: View {
    let errors: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(errors, id: \.self) { message in
                Text(message).css(FontSize.fs13).foregroundStyle(Palette.danger)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isStaticText)
    }
}

/// `.composer-errors.voice-error`: a dictation that failed, and Dismiss.
struct VoiceErrorLine: View {
    let message: String
    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.sp3) {
            Text(message).css(FontSize.fs13).foregroundStyle(Palette.danger)
            Spacer(minLength: 0)
            LinkButton(title: S.common.dismiss, size: FontSize.fs13, color: Palette.danger, action: onDismiss)
        }
    }
}

/// `.voice-status`: the one quiet line above the field while dictation runs,
/// and while the model is writing the words back.
struct VoiceStatusLine: View {
    let text: String

    var body: some View {
        Text(text)
            .css(FontSize.fs13)
            .foregroundStyle(Palette.inkSecondary)
            .padding(.leading, Space.sp1)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A29 — `.polish-note`: what the polish model did, under the field.
/// "Polished · Undo" until the next edit or send, or the one line that says a
/// failed request changed nothing.
struct PolishNote: View {
    let state: DictationPolishState
    let onUndo: () -> Void

    var body: some View {
        Group {
            if state == .failed {
                Text(S.voice.polishFailed).css(FontSize.fs12)
            } else {
                HStack(alignment: .firstTextBaseline, spacing: Space.sp2) {
                    Text(S.voice.polished).css(FontSize.fs12)
                    Text("·").css(FontSize.fs12).accessibilityHidden(true)
                    LinkButton(title: S.voice.undo, size: FontSize.fs12, action: onUndo)
                }
            }
        }
        .foregroundStyle(Palette.inkSecondary)
        .padding(.leading, Space.sp1)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A10 — `.takeover-bar`: the session is the terminal's, and, for an agent
/// that could have been attached, the one quiet line on why it is not. Take
/// over only where the agent has it.
struct TakeoverBar: View {
    let hint: String?
    let canTakeover: Bool
    let onTakeover: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Radius.md, style: .circular)
        HStack(spacing: Space.sp3) {
            VStack(alignment: .leading, spacing: 2) {
                Text(S.status.terminalControlled).css(FontSize.fs13)
                if let hint {
                    Text(hint).css(FontSize.fs12).foregroundStyle(Palette.inkTertiary)
                }
            }
            Spacer(minLength: 0)
            if canTakeover {
                Btn(S.chat.takeOver, size: .small, action: onTakeover)
            }
        }
        .foregroundStyle(Palette.inkSecondary)
        .padding(.vertical, Space.sp2 + 1)
        .padding(.horizontal, Space.sp3 + 1)
        .background(shape.fill(Palette.surfaceSunken))
        .overlay(shape.strokeBorder(Palette.line, lineWidth: 1))
    }
}
