import AppKit
import SwiftUI

/// `.scroll-thin`: the scroll bar the web draws in its own panes — a 10-point
/// gutter with no track, and a 4-point pill of `--line-strong` inset 3 points
/// on every side — which takes its room beside the content and shows only when
/// there is something to scroll, as a WebKit scroll bar does.
public struct ThinScrollView<Content: View>: View {
    let axes: Axis.Set
    let content: Content

    public init(_ axes: Axis.Set = .vertical, @ViewBuilder content: () -> Content) {
        self.axes = axes
        self.content = content()
    }

    public var body: some View {
        ScrollView(axes) { content.background(ScrollThinProbe()) }
    }
}

extension View {
    /// The same, for a view that is already the content of a `ScrollView`.
    public func scrollThin() -> some View { background(ScrollThinProbe()) }
}

/// Sits in the scrolled content and restyles the scroll view around it.
private struct ScrollThinProbe: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { ScrollThinView() }
    func updateNSView(_ view: NSView, context: Context) { (view as? ScrollThinView)?.apply() }
}

private final class ScrollThinView: NSView {
    private var observing = false

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        apply()
        guard !observing else { return }
        observing = true
        // The system's own scroll bar preference is put back on every scroll
        // view when it changes, so the thin one is put back after it. A
        // selector observer goes when this view does.
        NotificationCenter.default.addObserver(self, selector: #selector(preferenceChanged),
                                               name: NSScroller.preferredScrollerStyleDidChangeNotification,
                                               object: nil)
    }

    @objc private func preferenceChanged() {
        DispatchQueue.main.async { [weak self] in self?.apply() }
    }

    func apply() {
        guard let scrollView = enclosingScrollView else { return }
        if scrollView.scrollerStyle != .legacy { scrollView.scrollerStyle = .legacy }
        scrollView.autohidesScrollers = true
        if scrollView.hasVerticalScroller, !(scrollView.verticalScroller is ThinScroller) {
            scrollView.verticalScroller = ThinScroller()
        }
        if scrollView.hasHorizontalScroller, !(scrollView.horizontalScroller is ThinScroller) {
            scrollView.horizontalScroller = ThinScroller()
        }
    }
}

/// The WebKit scroll bar of `base.css`: a knob and nothing else.
final class ThinScroller: NSScroller {
    override class var isCompatibleWithOverlayScrollers: Bool { false }

    override class func scrollerWidth(for controlSize: NSControl.ControlSize, scrollerStyle: NSScroller.Style) -> CGFloat {
        10
    }

    override func draw(_ dirtyRect: NSRect) { drawKnob() }

    override func drawKnobSlot(in slotRect: NSRect, highlight flag: Bool) {}

    override func drawKnob() {
        let knob = rect(for: .knob).insetBy(dx: 3, dy: 3)
        guard knob.width > 0, knob.height > 0 else { return }
        let radius = min(knob.width, knob.height) / 2
        NSColor(Palette.lineStrong).setFill()
        NSBezierPath(roundedRect: knob, xRadius: radius, yRadius: radius).fill()
    }
}
