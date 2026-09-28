import SwiftUI

/// `.terminal-head`: the back arrow, the device's name with the status line
/// under it, and Close at the trailing edge, on a 60-point white strip over a
/// `--line` rule. On the Mac the strip is the window's top strip: the traffic
/// lights sit in its leading edge, the arrow starts after them, and the empty
/// part of it drags the window.
struct TerminalHead<Status: View>: View {
    let title: String
    let onBack: () -> Void
    let onClose: () -> Void
    let status: Status
    @Environment(\.trafficLightInset) private var trafficLightInset

    init(title: String, onBack: @escaping () -> Void, onClose: @escaping () -> Void,
         @ViewBuilder status: () -> Status) {
        self.title = title
        self.onBack = onBack
        self.onClose = onClose
        self.status = status()
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: Space.sp3) {
                IconBtn(.arrowLeft, size: 17, label: S.terminal.back, action: onBack)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .css(FontSize.fs15, weight: .semibold, lineHeight: 1.4, tracking: -0.01)
                        .accessibilityAddTraits(.isHeader)
                    status
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Btn(S.common.close, size: .small, action: onClose)
            }
            .padding(.vertical, Space.sp2)
            .padding(.leading, max(Space.sp4, trafficLightInset))
            .padding(.trailing, Space.sp4)
            // `min-height: var(--header-h)` holds the rule too (border-box).
            .frame(minHeight: LayoutSize.headerH - 1)
            Rectangle().fill(Palette.line).frame(height: 1)
        }
        .background {
            ZStack {
                Palette.surface
                WindowStrip()
            }
        }
        .windowStripHeight(LayoutSize.headerH)
    }
}
