import RCCore
import SwiftUI

/// `.topbar`: the brand, the three tabs, the reconnecting chip, the gateway's
/// host in mono and the initials, on a 60-point strip of 86 % white over a
/// `--line` rule, across the top of the window. The traffic lights sit in its
/// leading edge and the strip drags the window.
struct Topbar: View {
    /// `--header-h` and the rule under it.
    static let height = LayoutSize.headerH + 1

    @Environment(MacAppModel.self) private var model
    @Environment(\.layoutClass) private var layout
    @Environment(\.trafficLightInset) private var trafficLightInset

    var body: some View {
        let compact = layout.maxWidth760
        let padding = layout.maxWidth420 ? Space.sp3 : (compact ? Space.sp4 : Space.sp6)
        VStack(spacing: 0) {
            HStack(spacing: compact ? Space.sp2 : Space.sp5) {
                brand(compact: compact)
                TopbarTabs(compact: compact, tight: layout.maxWidth420)
                    .frame(maxWidth: compact ? .infinity : nil, alignment: .leading)
                if !compact { Spacer(minLength: 0) }
                trailing(compact: compact)
            }
            .padding(.leading, leadingPadding(padding))
            .padding(.trailing, padding)
            .frame(maxWidth: LayoutSize.contentMax)
            .frame(maxWidth: .infinity)
            .frame(height: LayoutSize.headerH)
            Rectangle().fill(Palette.line).frame(height: 1)
        }
        // The page scrolls under 86 % white; the web also blurs what passes
        // beneath (`backdrop-filter`), which the Mac leaves out.
        .background(Color.white.opacity(0.86))
        .background { WindowStrip() }
        .windowStripHeight(LayoutSize.headerH)
    }

    /// The traffic lights take the window's leading corner, so the brand
    /// starts after them wherever the centred strip would put it under them.
    private func leadingPadding(_ padding: CGFloat) -> CGFloat {
        let stripLeft = max(0, (layout.width - LayoutSize.contentMax) / 2)
        return max(padding, trafficLightInset - stripLeft)
    }

    private func brand(compact: Bool) -> some View {
        HStack(spacing: Space.sp2) {
            Mark()
            // At 760 and narrower the mark stays and the wordmark goes.
            if !compact {
                Text(S.productName)
                    .lineLimit(1)
                    .css(FontSize.fs14, weight: .semibold, tracking: -0.01)
                    .fixedSize()
            }
        }
        .fixedSize()
        .allowsHitTesting(false)
    }

    private func trailing(compact: Bool) -> some View {
        HStack(spacing: compact ? Space.sp2 : Space.sp3) {
            if !model.connection.phase.isOpen {
                Spinner()
                    .help(S.connection.reconnecting)
                    .accessibilityLabel(S.connection.reconnecting)
            }
            if !compact {
                Text(Identity.gatewayHost(model.origin))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .css(FontSize.fs12, mono: true)
                    .foregroundStyle(Palette.inkSecondary)
                    .frame(maxWidth: 220, alignment: .leading)
                    .help(model.origin)
            }
            Avatar(username: model.connection.username)
        }
        .fixedSize(horizontal: !compact, vertical: false)
    }
}

extension ConnectionPhase {
    /// The web's `status === 'open'`: the socket is up, whether or not its
    /// `hello` has landed yet.
    var isOpen: Bool { self == .syncing || self == .connected }
}

/// `.avatar`: the initials on a 28-point ink circle.
struct Avatar: View {
    let username: String

    var body: some View {
        Text(Identity.initials(username.isEmpty ? "?" : username))
            .css(FontSize.fs11, weight: .semibold, tracking: 0.02)
            .foregroundStyle(Palette.inkInverse)
            .frame(width: 28, height: 28)
            .background(Circle().fill(Palette.ink))
            .accessibilityHidden(true)
    }
}
