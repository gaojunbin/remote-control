import RCCore
import SwiftUI

/// `web/src/features/chat/blocks/UserMessageRow.tsx`: the person's own words,
/// in a light grey bubble on the right that hugs them (`docs/DESIGN.md` § "The
/// timeline"). The bubble is inert: a message is a record, not a control.
struct UserMessageRow: View {
    let message: UserMessagePayload
    /// A12: set while this app is still waiting for the device to echo the send.
    var pending: OptimisticMessage?
    @Environment(\.layoutClass) private var layout

    var body: some View {
        ChatFractionalWidth(fraction: layout.maxWidth1023 ? 0.88 : 0.78, trailing: true) {
            bubble
        }
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let origin = originLabel {
                Text(origin)
                    .css(FontSize.fs11, lineHeight: 1.55)
                    .foregroundStyle(Palette.inkTertiary)
                    .padding(.bottom, 2)
            }
            Text(verbatim: ChatPreText.display(message.text))
                .css(FontSize.fs14, lineHeight: 1.55)
                .textSelection(.enabled)
            if !message.attachments.isEmpty {
                HStack(spacing: 5) {
                    Icon(.paperclip, size: 12)
                    Text(S.chat.attachments(message.attachments.count)).css(FontSize.fs12, lineHeight: 1.55)
                }
                .foregroundStyle(Palette.inkSecondary)
                .padding(.top, 6)
            }
            if let pending {
                BubbleLine { PendingChip(message: pending) }
            } else if let delivery = Attach.deliveryLabel(message.delivery?.rawValue) {
                BubbleLine { DeliveryChip(text: delivery) }
            }
        }
        .padding(.vertical, 10)
        .padding(.horizontal, Space.sp4)
        .background(RoundedRectangle(cornerRadius: Radius.lg, style: .circular).fill(Palette.surfaceMuted))
        .opacity(pending == nil ? 1 : 0.62)
    }

    /// The caption above the words. A message this app or another one sent
    /// needs none; a terminal-typed one says where it was typed. A34: another
    /// agent's words never reach this row. A35: the one prompt the device wrote
    /// for the person, once the usage limit reset, says so.
    private var originLabel: String? {
        switch message.source {
        case .terminal: S.chat.fromTerminal
        case .resume: S.chat.fromResume
        default: nil
        }
    }
}

/// The chip's line inside the bubble: an inline box on a line of its own, 6
/// points below the text, on the bubble's 14-point, 1.55 strut.
private struct BubbleLine<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) { self.content = content() }

    var body: some View {
        ChatStrutLine(size: FontSize.fs14, lineHeight: 1.55) {
            content.padding(.top, 6)
        }
    }
}

/// `.delivery-chip`: where a message sent into an attached terminal got to.
struct DeliveryChip: View {
    let text: String

    var body: some View {
        Text(text)
            .css(FontSize.fs11, lineHeight: 1.55)
            .lineLimit(1)
            .fixedSize()
            .foregroundStyle(Palette.inkTertiary)
            .padding(.vertical, 1)
            .padding(.horizontal, 7)
            .background(Capsule().fill(Palette.surface))
    }
}

/// The chip on a send the device has not confirmed, re-read on a slow clock.
/// A14: a steered message is accepted at once but reaches the agent only at
/// its next step, so it says so instead of counting towards a delivery
/// problem it does not have.
private struct PendingChip: View {
    let message: OptimisticMessage

    var body: some View {
        TimelineView(.periodic(from: .now, by: 5)) { context in
            DeliveryChip(text: label(at: context.date))
        }
    }

    private func label(at now: Date) -> String {
        if message.isSteering { return S.chat.steering }
        if message.isUnconfirmed(at: now) { return S.composer.deliveryUnconfirmed }
        return S.chat.sending
    }
}
