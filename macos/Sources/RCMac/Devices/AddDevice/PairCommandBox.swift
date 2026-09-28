import RCCore
import SwiftUI

/// `.pair-command`: the full one-liner in a sunken mono block with Copy beside
/// it, and under a rule the pairing code, marked single use, with its countdown
/// — or "expired" and New code once it has run out.
struct PairCommandBox: View {
    let pairing: AddDevicePairing
    /// Now, on the gateway's clock: `expires_at` is a gateway timestamp.
    let now: Int64
    let newCode: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: Space.sp3) {
                PairCommandText(pairing.command.isEmpty ? " " : pairing.command)
                Btn(pairing.copied == .code ? S.common.copied : S.common.copy, size: .small) {
                    pairing.copy(pairing.command, as: .code)
                }
                .disabled(pairing.command.isEmpty)
            }
            .padding(Space.sp4)
            Rectangle().fill(Palette.line).frame(height: 1)
            foot
                .padding(.vertical, 10)
                .padding(.horizontal, Space.sp4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.surface)
        }
        .pairingBox(fill: Palette.surfaceSunken)
    }

    private var foot: some View {
        let expired = pairing.hasExpired(now: now)
        return HStack(spacing: Space.sp3) {
            Text(pairing.grant?.code ?? "—")
                .css(FontSize.fs12, mono: true, tracking: 0.02)
            Rectangle().fill(Palette.lineStrong).frame(width: 1, height: 12)
            Text("\(S.pairing.singleUse) · \(expired ? S.pairing.expired : S.pairing.expiresIn(Format.clock(Double(pairing.remaining(now: now)))))")
                .css(FontSize.fs12)
                .foregroundStyle(Palette.inkSecondary)
            if expired {
                PairLink(S.pairing.newCode, size: FontSize.fs12, action: newCode)
            }
        }
    }
}

/// A one-liner as the modal prints it: 13-point mono on 1.7 lines, breaking
/// anywhere, and selectable so a part of it can be copied too.
struct PairCommandText: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(TextMeasure.breakAll(text))
            .css(FontSize.fs13, lineHeight: 1.7, mono: true)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// `.link-btn`: a button drawn as underlined text in the ink, at the size of
/// the line it sits in.
struct PairLink: View {
    let title: String
    let size: CGFloat
    let action: () -> Void

    init(_ title: String, size: CGFloat, action: @escaping () -> Void) {
        self.title = title
        self.size = size
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title).underline().css(size)
        }
        .buttonStyle(.plain)
        .foregroundStyle(Palette.ink)
        .pointerStyle(.link)
    }
}

extension View {
    /// The bordered blocks of the pairing modal: a 1-point `--line` edge on a
    /// 12-point radius, the fill inside it, and what they hold clipped to it.
    func pairingBox(fill: Color) -> some View {
        let shape = RoundedRectangle(cornerRadius: Radius.md, style: .circular)
        return padding(1)
            .background(fill)
            .clipShape(shape)
            .overlay(shape.strokeBorder(Palette.line, lineWidth: 1))
    }
}
