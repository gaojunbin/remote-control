import SwiftUI

/// A scrolling area as tall as what it holds and no taller, which scrolls only
/// once its container cannot give it that much: `.modal-body` with
/// `overflow-y: auto` and the thin scroll bar.
public struct FittingScroll<Content: View>: View {
    let content: Content
    @State private var height: CGFloat = 0

    public init(@ViewBuilder content: () -> Content) { self.content = content() }

    public var body: some View {
        ThinScrollView {
            content.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(maxHeight: height)
    }
}

/// `.modal-head` / `.drawer-head`: the 22-point, 600-weight title with the
/// optional close button at the trailing edge.
struct OverlayHead: View {
    let title: String
    let subtitle: String?
    let showClose: Bool
    let close: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: Space.sp4) {
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .css(FontSize.fs22, weight: .semibold, tracking: -0.01)
                    .accessibilityAddTraits(.isHeader)
                if let subtitle { Hint(subtitle) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if showClose { IconBtn(.x, size: 16, label: S.common.close, action: close) }
        }
    }
}

/// `web/src/components/Modal.tsx`'s markup: the head, the body that scrolls
/// past the modal's height, and the footer on a 1-point `--line` rule.
struct ModalPanel<Content: View, Footer: View>: View {
    let title: String?
    let showClose: Bool
    let close: () -> Void
    let content: Content
    let footer: Footer?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let title {
                OverlayHead(title: title, subtitle: nil, showClose: showClose, close: close)
                    .padding(.horizontal, Space.sp6)
                    .padding(.top, Space.sp6)
            }
            FittingScroll {
                // `.modal-body` is a block: what it holds stacks with no gap.
                VStack(alignment: .leading, spacing: 0) { content }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, Space.sp4)
                    .padding(.horizontal, Space.sp6)
                    .padding(.bottom, Space.sp6)
            }
            if let footer {
                // The rule is the footer's own top border, and takes its point.
                Rectangle().fill(Palette.line).frame(height: 1)
                SpaceBetween(minimumSpacing: Space.sp3) { footer }
                    .padding(.vertical, Space.sp4)
                    .padding(.horizontal, Space.sp6)
            }
        }
    }
}

/// `Drawer` in `Modal.tsx`: the head with its title, subtitle and close button,
/// the body that fills and scrolls with 20 points between its sections, and the
/// optional footer.
struct DrawerPanel<Content: View, Footer: View>: View {
    let title: String
    let subtitle: String?
    let close: () -> Void
    let content: Content
    let footer: Footer?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            OverlayHead(title: title, subtitle: subtitle, showClose: true, close: close)
                .padding(.horizontal, Space.sp6)
                .padding(.top, Space.sp6)
                .padding(.bottom, Space.sp4)
            ThinScrollView {
                VStack(alignment: .leading, spacing: Space.sp5) { content }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.sp6)
                    .padding(.bottom, Space.sp6)
            }
            .frame(maxHeight: .infinity)
            if let footer {
                footer
                    .frame(maxWidth: .infinity)
                    .padding(.top, Space.sp4)
                    .padding(.horizontal, Space.sp6)
                    .padding(.bottom, Space.sp6)
            }
        }
    }
}

/// `display: flex; justify-content: space-between`: the first item at the
/// leading edge, the last at the trailing edge, the rest spread between, each
/// vertically centred. A single item sits at the leading edge.
public struct SpaceBetween: Layout {
    let minimumSpacing: CGFloat

    public init(minimumSpacing: CGFloat = 0) { self.minimumSpacing = minimumSpacing }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let natural = sizes.reduce(0) { $0 + $1.width } + minimumSpacing * CGFloat(max(0, sizes.count - 1))
        let height = sizes.map(\.height).max() ?? 0
        return CGSize(width: proposal.width ?? natural, height: height)
    }

    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let total = sizes.reduce(0) { $0 + $1.width }
        let gaps = CGFloat(max(1, sizes.count - 1))
        let spacing = sizes.count > 1 ? max(minimumSpacing, (bounds.width - total) / gaps) : 0
        var x = bounds.minX
        for (index, subview) in subviews.enumerated() {
            subview.place(at: CGPoint(x: x, y: bounds.midY), anchor: .leading, proposal: ProposedViewSize(sizes[index]))
            x += sizes[index].width + spacing
        }
    }
}
