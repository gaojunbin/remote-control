import SwiftUI

/// `.overlay`: the dimmed backdrop under a modal or the drawer, which a press
/// on closes (`onMouseDown` on the web: the press, not the release).
struct Backdrop: View {
    let dismiss: @MainActor () -> Void
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Palette.overlay
            .opacity(shown ? 1 : 0)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { _ in dismiss() })
            .onAppear {
                // `rc-fade` over `--dur`.
                withAnimation(Motion.ease(Motion.dur, reduceMotion: reduceMotion)) { shown = true }
            }
    }
}

/// A modal where `.overlay` puts it: centred with 16 points kept to the window,
/// or, at 640 points and narrower, a sheet on the bottom edge — still no wider
/// than it asked for — with its lower corners square.
struct ModalFrame<Content: View>: View {
    let width: CGFloat
    /// False when a close button heads the modal, which is then what focus
    /// moves to rather than its first field (`DialogFocus`).
    let startsInField: Bool
    let viewport: CGSize
    let dismiss: @MainActor () -> Void
    @ViewBuilder let content: () -> Content
    @State private var risen = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let sheet = viewport.width <= 640
        ZStack {
            Backdrop(dismiss: dismiss)
            WholePointCenter(bottom: sheet) {
                CappedHeight(maximum: sheet ? viewport.height * 0.92 : min(viewport.height * 0.88, 820)) {
                    // The width a modal asks for holds as a sheet too: the
                    // web sets it inline, and the narrow rule leaves it alone.
                    content().frame(maxWidth: width)
                }
                .background(ModalShape(sheet: sheet).fill(Palette.surface))
                .background(DialogFocus(startsInField: startsInField))
                .clipShape(ModalShape(sheet: sheet))
                .boxShadow(Shadow.modal, in: ModalShape(sheet: sheet))
                // `rc-rise`: up 8 points and from 99 % over `--dur`.
                .opacity(risen ? 1 : 0)
                .offset(y: risen ? 0 : 8)
                .scaleEffect(risen ? 1 : 0.99)
            }
            .padding(sheet ? 0 : Space.sp4)
        }
        .frame(width: viewport.width, height: viewport.height)
        .onAppear { withAnimation(Motion.ease(Motion.dur, reduceMotion: reduceMotion)) { risen = true } }
    }
}

/// The modal's 16-point radius, square at the bottom when it is a sheet.
struct ModalShape: Shape {
    let sheet: Bool

    func path(in rect: CGRect) -> Path {
        let radius = Radius.lg
        return UnevenRoundedRectangle(topLeadingRadius: radius, bottomLeadingRadius: sheet ? 0 : radius,
                                      bottomTrailingRadius: sheet ? 0 : radius, topTrailingRadius: radius,
                                      style: .circular).path(in: rect)
    }
}

/// `.drawer-overlay` + `.drawer`: the full height of the window on its right,
/// 480 points wide or the whole width at 640 and narrower, sliding in from 24
/// points to the right.
struct DrawerFrame<Content: View>: View {
    let viewport: CGSize
    let dismiss: @MainActor () -> Void
    @ViewBuilder let content: () -> Content
    @State private var slid = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let width = viewport.width <= 640 ? viewport.width : min(480, viewport.width)
        ZStack(alignment: .trailing) {
            Backdrop(dismiss: dismiss)
            content()
                .frame(width: width, height: viewport.height)
                .background(Palette.surface)
                // Its head holds the close button, which is where focus goes.
                .background(DialogFocus(startsInField: false))
                .boxShadow(Shadow.modal, in: Rectangle())
                .opacity(slid ? 1 : 0)
                .offset(x: slid ? 0 : 24)
        }
        .frame(width: viewport.width, height: viewport.height)
        .onAppear { withAnimation(Motion.ease(Motion.dur, reduceMotion: reduceMotion)) { slid = true } }
    }
}

/// `.popover-panel`, placed by `PopoverPlacement` against its trigger: at least
/// 200 points wide, at most 360 or the window less 32, a 4-point inset, a
/// 12-point radius and `--shadow-pop`. It is measured before it is shown, as
/// the web's layout effect places it before the browser paints.
struct PopoverFrame<Content: View>: View {
    let id: UUID
    let trigger: CGRect
    let viewport: CGSize
    /// Where this layer sits in the window, to report the panel in window terms.
    let origin: CGPoint
    let align: PopoverAlign
    let side: PopoverSide
    @ViewBuilder let content: () -> Content
    @State private var size: CGSize?
    @State private var shown = false
    @Environment(\.overlayRegistry) private var registry
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Radius.md, style: .circular)
        let measured = size ?? .zero
        let placement = PopoverPlacement.place(trigger: trigger, panel: measured, viewport: viewport,
                                               align: align, side: side)
        let top = PopoverPlacement.top(of: placement, panelHeight: measured.height,
                                       viewportHeight: viewport.height)
        content()
            .padding(Space.sp1)
            .frame(minWidth: 200, maxWidth: min(360, viewport.width - 32), alignment: .leading)
            .fixedSize()
            .background(shape.fill(Palette.surface))
            .boxShadow(Shadow.pop, in: shape)
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size = $0 }
            // `rc-pop`: from 2 points up and transparent, over `--dur-fast`.
            .opacity(size != nil && shown ? 1 : 0)
            .offset(x: placement.left, y: top + (shown ? 0 : -2))
            .onChange(of: size != nil) { _, measuredNow in
                guard measuredNow else { return }
                withAnimation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion)) { shown = true }
            }
            .onChange(of: PanelFrame(rect: CGRect(x: placement.left, y: top, width: measured.width,
                                                  height: measured.height), trigger: trigger),
                      initial: true) { _, frame in
                registry?.update(id, panel: frame.rect.offsetBy(dx: origin.x, dy: origin.y),
                                 trigger: frame.trigger.offsetBy(dx: origin.x, dy: origin.y))
            }
    }
}

private struct PanelFrame: Equatable {
    let rect: CGRect
    let trigger: CGRect
}

/// `max-height`: the content is offered at most `maximum` and takes what it
/// needs of it, which `frame(maxHeight:)` does not do — that frame is as tall
/// as it is offered.
struct CappedHeight: Layout {
    let maximum: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let content = subviews.first else { return .zero }
        return content.sizeThatFits(capped(proposal))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
    }

    private func capped(_ proposal: ProposedViewSize) -> ProposedViewSize {
        ProposedViewSize(width: proposal.width, height: min(proposal.height ?? maximum, maximum))
    }
}

/// Centres its content, or sets it on the bottom edge, on a whole point: the
/// browser puts a box that a flex container centres 327.5 points down at 328.
/// Inside a scroll view, where nothing is proposed for the height, it is as
/// tall as its content and at least `minimumHeight`.
public struct WholePointCenter: Layout {
    let bottom: Bool
    let minimumHeight: CGFloat

    public init(bottom: Bool = false, minimumHeight: CGFloat = 0) {
        self.bottom = bottom
        self.minimumHeight = minimumHeight
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let content = subviews.first?.sizeThatFits(proposal) ?? .zero
        return CGSize(width: proposal.width ?? content.width,
                      height: proposal.height ?? max(content.height, minimumHeight))
    }

    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let content = subviews.first else { return }
        let size = content.sizeThatFits(ProposedViewSize(width: bounds.width, height: proposal.height))
        let x = (bounds.minX + (bounds.width - size.width) / 2).rounded()
        let y = bottom ? bounds.maxY - size.height : (bounds.minY + (bounds.height - size.height) / 2).rounded()
        content.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
    }
}
