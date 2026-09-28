import SwiftUI

/// The one overlay layer (`docs/DESIGN.md` § "The Mac app" → **Overlays are the
/// web's**): popovers, menus, modals and the drawer are drawn in the window,
/// above everything, with the web's surfaces, shadows and placement — never as
/// system popovers, sheets or menus — and they close as the web's do: Escape,
/// a click outside, or the control that opened them.
///
/// A view asks for an overlay with `.modal`, `.drawer`, `.confirmDialog`,
/// `.anchoredPanel` or a `Popover`; the request travels up as a preference and
/// is drawn here, so the overlay's content stays live with the state of the
/// view that asked. Every overlay carries a layer of its own, so a popover
/// inside the drawer, or a confirmation over a modal, is drawn above it.
///
/// The content of an overlay is drawn at the root and reads the root's
/// environment: pass anything a feature keeps in its own environment in
/// explicitly. And a list whose rows open overlays is a `ScrollView`, never a
/// `List`, whose rows AppKit hosts apart and whose preferences never arrive.
///
/// The content is built from the closures the asking view's body last handed
/// over, so build it from values that body reads — the form a dialog edits,
/// not a binding to it. A `Binding` read inside the closure gives the value it
/// held when that body last ran: a modal opened by setting an optional the
/// body never reads opens with nothing in it, whatever order the view chains
/// its overlays in. A binding handed on to a child view's `@Binding` stays
/// current. `OverlayOrderGallery` in the renderer is the check.
struct OverlayHostModifier: ViewModifier {
    @State private var registry = OverlayRegistry()
    @State private var events = OverlayEvents()
    @State private var blocking = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            // `.overlay`'s `backdrop-filter: blur(2px)` over the page.
            .blur(radius: blocking > 0 && !reduceMotion ? 1 : 0)
            .onPreferenceChange(BlockingOverlayKey.self) { blocking = $0 }
            .overlayPreferenceValue(OverlayEntriesKey.self) { entries in
                OverlayLayer(entries: entries)
            }
            .environment(\.overlayRegistry, registry)
            .onAppear { events.start(registry) }
            .onDisappear { events.stop() }
    }
}

extension View {
    /// Installs the overlay layer. `RootView` does it once, around everything.
    public func overlayHost() -> some View { modifier(OverlayHostModifier()) }
}

/// How many modals and drawers are open, which is what blurs the page.
struct BlockingOverlayKey: PreferenceKey {
    static var defaultValue: Int { 0 }
    static func reduce(value: inout Int, nextValue: () -> Int) { value += nextValue() }
}

/// Every overlay asked for at one level, the newest modal on top and every
/// popover above them all (`--z-popover` clears `--z-overlay`).
struct OverlayLayer: View {
    let entries: [OverlayEntry]

    var body: some View {
        let ordered = ordered
        GeometryReader { proxy in
            ZStack(alignment: .topLeading) {
                ForEach(Array(ordered.enumerated()), id: \.element.id) { index, entry in
                    OverlayItem(entry: entry, proxy: proxy, covered: Self.isCovered(index, in: ordered))
                        .overlayPreferenceValue(OverlayEntriesKey.self) { nested in
                            OverlayLayer(entries: nested)
                        }
                        .zIndex(entry.kind.isPopover ? ZLayer.popover : ZLayer.overlay)
                }
            }
            .frame(width: proxy.size.width, height: proxy.size.height, alignment: .topLeading)
        }
        .ignoresSafeArea()
    }

    private var ordered: [OverlayEntry] {
        entries.sorted { lhs, rhs in
            if lhs.kind.isPopover != rhs.kind.isPopover { return !lhs.kind.isPopover }
            return lhs.openedAt < rhs.openedAt
        }
    }

    /// A modal or the drawer opened later at this level lies over this one, and
    /// its backdrop blurs what it covers.
    private static func isCovered(_ index: Int, in ordered: [OverlayEntry]) -> Bool {
        ordered[(index + 1)...].contains { !$0.kind.isPopover }
    }
}

/// One overlay, the size of the window: its backdrop, if it has one, and its
/// panel where the web puts it.
private struct OverlayItem: View {
    let entry: OverlayEntry
    let proxy: GeometryProxy
    /// A modal or the drawer lies over this one at its own level.
    let covered: Bool
    /// How many modals and drawers are open inside this one, whose own layer
    /// is drawn over it.
    @State private var within = 0
    @Environment(\.overlayRegistry) private var registry
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            switch entry.kind {
            case .modal(let width, let closeButton):
                ModalFrame(width: width, startsInField: !closeButton, viewport: proxy.size,
                           dismiss: entry.dismiss) { entry.content }
            case .drawer:
                DrawerFrame(viewport: proxy.size, dismiss: entry.dismiss) { entry.content }
            case .popover(let anchor, let align, let side):
                PopoverFrame(id: entry.id, trigger: proxy[anchor], viewport: proxy.size,
                             origin: proxy.frame(in: .global).origin, align: align, side: side) {
                    entry.content
                }
            }
        }
        .frame(width: proxy.size.width, height: proxy.size.height, alignment: .topLeading)
        // The web's `.overlay` blurs everything under it, a drawer or a modal
        // included, as it blurs the page (`OverlayHostModifier`).
        .onPreferenceChange(BlockingOverlayKey.self) { within = $0 }
        .blur(radius: (covered || within > 0) && !reduceMotion ? 1 : 0)
        .onAppear {
            registry?.register(entry.id, openedAt: entry.openedAt, isPopover: entry.kind.isPopover,
                               dismiss: entry.dismiss)
        }
        .onDisappear { registry?.unregister(entry.id) }
    }
}
