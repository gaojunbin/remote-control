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
        GeometryReader { proxy in
            ZStack(alignment: .topLeading) {
                ForEach(ordered) { entry in
                    OverlayItem(entry: entry, proxy: proxy)
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
}

/// One overlay, the size of the window: its backdrop, if it has one, and its
/// panel where the web puts it.
private struct OverlayItem: View {
    let entry: OverlayEntry
    let proxy: GeometryProxy
    @Environment(\.overlayRegistry) private var registry

    var body: some View {
        Group {
            switch entry.kind {
            case .modal(let width):
                ModalFrame(width: width, viewport: proxy.size, dismiss: entry.dismiss) { entry.content }
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
        .onAppear {
            registry?.register(entry.id, openedAt: entry.openedAt, isPopover: entry.kind.isPopover,
                               dismiss: entry.dismiss)
        }
        .onDisappear { registry?.unregister(entry.id) }
    }
}
