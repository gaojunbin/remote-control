import AppKit
import SwiftUI

/// `.scroll-thin` as the browsers draw it on a Mac. `base.css` sets
/// `scrollbar-width: thin`, which Chrome and Safari both honour over the
/// `::-webkit-scrollbar` rules under it, so a pane gets the system's thin scroll
/// bar and follows the system setting: an overlay that takes no room while scroll
/// bars show when scrolling, and the small legacy scroller, beside the content,
/// while they always show — measured in both engines, 0 and the small scroller's
/// width, and 0 whenever there is nothing to scroll.
///
/// SwiftUI reserves the regular legacy scroller's width in the second case,
/// whatever scroller the view carries, so a small one would leave a gap between
/// the content and the bar. There the scroll view is kept on overlay scrollers,
/// which reserve nothing, its own scroller is kept out of sight, the content takes
/// the small scroller's width as padding while it overflows, and a small legacy
/// scroller of this view's own is drawn in that room (`ThinScrollers.swift`).
public struct ThinScrollView<Content: View>: View {
    let axes: Axis.Set
    let content: Content

    public init(_ axes: Axis.Set = .vertical, @ViewBuilder content: () -> Content) {
        self.axes = axes
        self.content = content()
    }

    public var body: some View {
        ScrollView(axes) { content.scrollThin() }
    }
}

extension View {
    /// The same, for a view that is already the content of a `ScrollView`.
    public func scrollThin() -> some View { modifier(ScrollThinContent()) }
}

public enum ScrollThin {
    /// The room the thin scroll bar takes beside content that overflows: none
    /// while scroll bars are overlays, the small legacy scroller's width while
    /// they always show.
    @MainActor
    public static var gutter: CGFloat {
        NSScroller.preferredScrollerStyle == .legacy ? SmallLegacyScroller.width : 0
    }
}

/// The room the small legacy scroller takes along each axis, as padding.
struct ScrollGutters: Equatable {
    var trailing: CGFloat = 0
    var bottom: CGFloat = 0
}

private struct ScrollThinContent: ViewModifier {
    @State private var gutters = ScrollGutters()

    func body(content: Content) -> some View {
        content
            .padding(.trailing, gutters.trailing)
            .padding(.bottom, gutters.bottom)
            .background(ScrollThinProbe { gutters = $0 })
    }
}

/// Sits in the scrolled content and keeps the scroll view around it thin.
private struct ScrollThinProbe: NSViewRepresentable {
    let report: @MainActor (ScrollGutters) -> Void

    func makeNSView(context: Context) -> ScrollThinView { ScrollThinView() }

    func updateNSView(_ view: ScrollThinView, context: Context) {
        view.report = report
        view.attach()
    }
}

private final class ScrollThinView: NSView {
    var report: (@MainActor (ScrollGutters) -> Void)?
    private weak var scrollView: NSScrollView?
    private let vertical = SmallLegacyScroller(axis: .vertical)
    private let horizontal = SmallLegacyScroller(axis: .horizontal)
    /// The scroll view's own scrollers, put back when scroll bars become overlays.
    private var own: (vertical: NSScroller?, horizontal: NSScroller?) = (nil, nil)
    private var gutters = ScrollGutters()
    private var refreshPending = false

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if window == nil {
            vertical.removeFromSuperview()
            horizontal.removeFromSuperview()
        } else {
            attach()
        }
    }

    func attach() {
        guard let scrollView = enclosingScrollView else { return }
        if self.scrollView !== scrollView { observe(scrollView) }
        refresh()
        // Again once AppKit has tiled what this pass laid out.
        refreshSoon()
    }

    /// This view is the content's background, so its size is the content's.
    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        refreshSoon()
    }

    private func refreshSoon() {
        guard !refreshPending else { return }
        refreshPending = true
        DispatchQueue.main.async { [weak self] in
            self?.refreshPending = false
            self?.refresh()
        }
    }

    private func observe(_ scrollView: NSScrollView) {
        NotificationCenter.default.removeObserver(self)
        self.scrollView = scrollView
        own = (nil, nil)
        for scroller in [vertical, horizontal] {
            scroller.target = self
            scroller.action = #selector(scrolled(_:))
        }
        let center = NotificationCenter.default
        scrollView.contentView.postsBoundsChangedNotifications = true
        center.addObserver(self, selector: #selector(changed), name: NSView.boundsDidChangeNotification,
                           object: scrollView.contentView)
        center.addObserver(self, selector: #selector(changed), name: NSView.frameDidChangeNotification,
                           object: scrollView.contentView)
        // The system puts its own style back on every scroll view when the
        // setting changes, so this one is kept after it.
        center.addObserver(self, selector: #selector(styleChanged),
                           name: NSScroller.preferredScrollerStyleDidChangeNotification, object: nil)
    }

    @objc private func changed() { refresh() }

    @objc private func styleChanged() { refreshSoon() }

    private func refresh() {
        guard let scrollView else { return }
        let next: ScrollGutters
        if NSScroller.preferredScrollerStyle == .legacy {
            keepOverlay(scrollView)
            next = placeLegacy(in: scrollView)
        } else {
            giveBack(scrollView)
            next = ScrollGutters()
        }
        guard next != gutters else { return }
        gutters = next
        let report = report
        DispatchQueue.main.async { report?(next) }
    }

    /// Overlay scroll bars: the system's own, thin, reserving nothing.
    private func giveBack(_ scrollView: NSScrollView) {
        vertical.removeFromSuperview()
        horizontal.removeFromSuperview()
        if let original = own.vertical, scrollView.verticalScroller is HiddenScroller {
            scrollView.verticalScroller = original
        }
        if let original = own.horizontal, scrollView.horizontalScroller is HiddenScroller {
            scrollView.horizontalScroller = original
        }
        own = (nil, nil)
        for scroller in [scrollView.verticalScroller, scrollView.horizontalScroller] {
            if let scroller, scroller.controlSize != .small { scroller.controlSize = .small }
        }
    }

    /// Legacy scroll bars: the scroll view stays on overlays, so SwiftUI reserves
    /// nothing, and its own scroller is out of sight behind this view's.
    private func keepOverlay(_ scrollView: NSScrollView) {
        if scrollView.scrollerStyle != .overlay { scrollView.scrollerStyle = .overlay }
        if scrollView.hasVerticalScroller, let current = scrollView.verticalScroller, !(current is HiddenScroller) {
            own.vertical = current
            scrollView.verticalScroller = HiddenScroller(frame: .zero)
        }
        if scrollView.hasHorizontalScroller, let current = scrollView.horizontalScroller,
           !(current is HiddenScroller) {
            own.horizontal = current
            scrollView.horizontalScroller = HiddenScroller(frame: .zero)
        }
    }

    /// The small legacy scrollers along the axes that overflow, and the room
    /// they take.
    private func placeLegacy(in scrollView: NSScrollView) -> ScrollGutters {
        let width = SmallLegacyScroller.width
        let down = scrollView.hasVerticalScroller ? ScrollAxis.vertical(of: scrollView) : nil
        let across = scrollView.hasHorizontalScroller ? ScrollAxis.horizontal(of: scrollView) : nil
        let tall = down?.overflows ?? false
        let wide = across?.overflows ?? false
        let bounds = scrollView.bounds
        let flipped = scrollView.isFlipped
        if tall, let down {
            let height = bounds.height - (wide ? width : 0)
            vertical.frame = NSRect(x: bounds.maxX - width, y: flipped || !wide ? bounds.minY : bounds.minY + width,
                                    width: width, height: height)
            vertical.show(down, in: scrollView)
        } else {
            vertical.removeFromSuperview()
        }
        if wide, let across {
            horizontal.frame = NSRect(x: bounds.minX, y: flipped ? bounds.maxY - width : bounds.minY,
                                      width: bounds.width - (tall ? width : 0), height: width)
            horizontal.show(across, in: scrollView)
        } else {
            horizontal.removeFromSuperview()
        }
        return ScrollGutters(trailing: tall ? width : 0, bottom: wide ? width : 0)
    }

    @objc private func scrolled(_ sender: SmallLegacyScroller) {
        guard let scrollView else { return }
        let metrics = sender.axis == .vertical ? ScrollAxis.vertical(of: scrollView)
                                               : ScrollAxis.horizontal(of: scrollView)
        guard let metrics else { return }
        let offset: CGFloat
        switch sender.hitPart {
        case .decrementPage: offset = metrics.offset - metrics.page
        case .incrementPage: offset = metrics.offset + metrics.page
        default: offset = metrics.offset(for: sender.doubleValue)
        }
        ScrollAxis.scroll(scrollView, along: sender.axis, to: offset)
    }
}
