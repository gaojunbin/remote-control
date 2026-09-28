import RCCore
import SwiftUI

/// `.action-error`: a failed action above the composer, in the danger tint,
/// with a way to put it away. 800 points at most, centred, 8 above the next.
struct ActionErrorBanner: View {
    let text: String
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: Space.sp3) {
            Text(verbatim: text)
                .css(FontSize.fs13)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(S.common.dismiss, action: onDismiss)
                .buttonStyle(.btn(.ghost, size: .small))
        }
        .foregroundStyle(Color(hex: 0x9C2C21))
        .bannerFrame()
        .accessibilityElement(children: .contain)
    }
}

/// `.unconfirmed`: sends the device never confirmed, with Retry under their
/// own request ids and Dismiss. Nothing is ever resent automatically.
struct UnconfirmedBanner: View {
    let pending: [PendingSend]
    let onRetry: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: Space.sp3) {
            Text("\(Text(S.composer.deliveryUnconfirmed).fontWeight(.bold)) \(Text(S.composer.deliveryUnconfirmedBody))")
                .css(FontSize.fs13)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(S.common.retry, action: onRetry)
                .buttonStyle(.btn(.standard, size: .small))
            Button(S.common.dismiss, action: onDismiss)
                .buttonStyle(.btn(.ghost, size: .small))
        }
        .foregroundStyle(Palette.ink)
        .bannerFrame()
    }
}

extension View {
    /// The two bars' shared box: 8 by 16 points of padding inside a one-point
    /// `#eccdc9` edge on the danger tint, a 12-point radius, 800 points at
    /// most and centred, 8 points above what follows.
    fileprivate func bannerFrame() -> some View {
        padding(.vertical, Space.sp2)
            .padding(.horizontal, Space.sp4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(1)
            .background(RoundedRectangle(cornerRadius: Radius.md, style: .circular).fill(Palette.dangerSoft))
            .chatBorder(ChatBorder(width: 1, radius: Radius.md), color: Color(hex: 0xECCDC9))
            .frame(maxWidth: 800)
            .frame(maxWidth: .infinity)
            .padding(.bottom, Space.sp2)
    }
}

/// `.empty.card.chat-missing`: the session the route names is not on the
/// gateway any more.
struct ChatMissing: View {
    @Environment(MacAppModel.self) private var model

    var body: some View {
        // `margin: auto` in the column: as wide as what it holds, 420 at most.
        ChatFitWidth(maximum: 420) {
            VStack(spacing: Space.sp3) {
                Text(S.errors.sessionMissing)
                    .css(FontSize.fs14, weight: .medium)
                    .foregroundStyle(Palette.ink)
                    .padding(.bottom, Space.sp1)
                    .multilineTextAlignment(.center)
                Button(S.nav.backToSessions) { model.router.go(.sessions) }
                    .buttonStyle(.btn(.standard, size: .small))
            }
            .padding(.vertical, Space.sp10)
            .padding(.horizontal, Space.sp4)
            .background(RoundedRectangle(cornerRadius: Radius.lg, style: .circular).fill(Palette.surfaceMuted))
        }
        .padding(.vertical, Space.sp10)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}
