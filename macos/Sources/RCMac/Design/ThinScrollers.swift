import AppKit

enum ScrollDirection {
    case vertical, horizontal
}

/// One axis of a scroll view: how much there is, how much of it shows, and how
/// far it has moved from the start.
struct ScrollAxis: Equatable {
    var content: CGFloat
    var visible: CGFloat
    var offset: CGFloat

    var overflows: Bool { content > visible + 0.5 }
    var maxOffset: CGFloat { max(0, content - visible) }
    var knobProportion: CGFloat { content > 0 ? min(1, visible / content) : 1 }
    /// The scroller's value, 0 at the start and 1 at the end.
    var value: Double { maxOffset > 0 ? Double(min(max(offset / maxOffset, 0), 1)) : 0 }

    func offset(for value: Double) -> CGFloat { CGFloat(min(max(value, 0), 1)) * maxOffset }

    /// A click in the track: WebKit's page, seven eighths of what shows or all of
    /// it but 40 points, whichever is more.
    var page: CGFloat { max(visible * 0.875, visible - 40) }

    @MainActor
    static func vertical(of scrollView: NSScrollView) -> ScrollAxis? {
        guard let document = scrollView.documentView else { return nil }
        let clip = scrollView.contentView.bounds
        let frame = document.frame
        let offset = document.isFlipped ? clip.minY - frame.minY : frame.maxY - clip.maxY
        return ScrollAxis(content: frame.height, visible: clip.height, offset: offset)
    }

    @MainActor
    static func horizontal(of scrollView: NSScrollView) -> ScrollAxis? {
        guard let document = scrollView.documentView else { return nil }
        let clip = scrollView.contentView.bounds
        return ScrollAxis(content: document.frame.width, visible: clip.width, offset: clip.minX - document.frame.minX)
    }

    /// Move the content so that `offset` of it lies before what shows.
    @MainActor
    static func scroll(_ scrollView: NSScrollView, along direction: ScrollDirection, to offset: CGFloat) {
        guard let document = scrollView.documentView else { return }
        let clip = scrollView.contentView.bounds
        let frame = document.frame
        var origin = clip.origin
        switch direction {
        case .vertical:
            let moved = min(max(offset, 0), max(0, frame.height - clip.height))
            origin.y = document.isFlipped ? frame.minY + moved : frame.maxY - clip.height - moved
        case .horizontal:
            origin.x = frame.minX + min(max(offset, 0), max(0, frame.width - clip.width))
        }
        scrollView.contentView.scroll(to: origin)
        scrollView.reflectScrolledClipView(scrollView.contentView)
    }
}

/// The system's small legacy scroller — what `scrollbar-width: thin` is while
/// scroll bars always show — standing in the room the content leaves it.
final class SmallLegacyScroller: NSScroller {
    let axis: ScrollDirection

    /// How much room it takes across: the system's width for the small size.
    static var width: CGFloat { scrollerWidth(for: .small, scrollerStyle: .legacy) }

    init(axis: ScrollDirection) {
        self.axis = axis
        // A scroller is vertical when it is taller than it is wide.
        super.init(frame: axis == .vertical ? NSRect(x: 0, y: 0, width: 11, height: 100)
                                            : NSRect(x: 0, y: 0, width: 100, height: 11))
        scrollerStyle = .legacy
        controlSize = .small
        // Its own layer: the scroll view it stands over draws nothing of what
        // is put over it into its own.
        wantsLayer = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override class var isCompatibleWithOverlayScrollers: Bool { false }

    /// Show it over the scroll view, its knob where the axis stands.
    func show(_ axis: ScrollAxis, in scrollView: NSScrollView) {
        if superview !== scrollView { scrollView.addSubview(self, positioned: .above, relativeTo: nil) }
        knobProportion = axis.knobProportion
        doubleValue = axis.value
        isEnabled = axis.overflows
    }
}

/// The scroll view's own scroller while the small legacy one stands in for it:
/// where AppKit and SwiftUI expect one, but never drawn and never in the way.
final class HiddenScroller: NSScroller {
    override class var isCompatibleWithOverlayScrollers: Bool { true }

    override init(frame: NSRect) {
        super.init(frame: frame)
        super.alphaValue = 0
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override var alphaValue: CGFloat {
        get { 0 }
        set { super.alphaValue = 0 }
    }

    override func draw(_ dirtyRect: NSRect) {}

    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}
