import SwiftUI

/// Hands an overlay's content up to the overlay layer while `isPresented` is
/// true, stamped with the moment it opened.
private struct OverlayPresenter<Panel: View>: ViewModifier {
    @Binding var isPresented: Bool
    let kind: OverlayKind
    let blocking: Bool
    @ViewBuilder let panel: () -> Panel
    @State private var id = UUID()
    @State private var openedAt = 0

    func body(content: Content) -> some View {
        content
            .onChange(of: isPresented, initial: true) { _, open in
                if open { openedAt = OverlayClock.next() }
            }
            .transformPreference(OverlayEntriesKey.self) { entries in
                guard isPresented else { return }
                entries.append(OverlayEntry(id: id, kind: kind, openedAt: openedAt, content: AnyView(panel()),
                                            dismiss: { isPresented = false }))
            }
            .transformPreference(BlockingOverlayKey.self) { count in
                if isPresented && blocking { count += 1 }
            }
    }
}

extension View {
    /// `Modal` (`web/src/components/Modal.tsx`): a dialog over a dimmed page,
    /// `width` at most (580 by default), with a title, an optional close button,
    /// a body that scrolls past the modal's height and a footer on a rule.
    /// Escape, a press on the backdrop, or setting `isPresented` closes it.
    public func modal<Content: View, Footer: View>(
        isPresented: Binding<Bool>, title: String? = nil, width: CGFloat = 580, showClose: Bool = false,
        @ViewBuilder content: @escaping () -> Content, @ViewBuilder footer: @escaping () -> Footer
    ) -> some View {
        modifier(OverlayPresenter(isPresented: isPresented, kind: .modal(width: width, closeButton: showClose), blocking: true) {
            ModalPanel(title: title, showClose: showClose, close: { isPresented.wrappedValue = false },
                       content: content(), footer: footer())
        })
    }

    /// A modal with no footer.
    public func modal<Content: View>(
        isPresented: Binding<Bool>, title: String? = nil, width: CGFloat = 580, showClose: Bool = false,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        modifier(OverlayPresenter(isPresented: isPresented, kind: .modal(width: width, closeButton: showClose), blocking: true) {
            ModalPanel<Content, EmptyView>(title: title, showClose: showClose,
                                           close: { isPresented.wrappedValue = false },
                                           content: content(), footer: nil)
        })
    }

    /// `Drawer`: the right-hand drawer with a title, a subtitle, a close button,
    /// a scrolling body and an optional footer — the web's New session drawer.
    public func drawer<Content: View, Footer: View>(
        isPresented: Binding<Bool>, title: String, subtitle: String? = nil,
        @ViewBuilder content: @escaping () -> Content, @ViewBuilder footer: @escaping () -> Footer
    ) -> some View {
        modifier(OverlayPresenter(isPresented: isPresented, kind: .drawer, blocking: true) {
            DrawerPanel(title: title, subtitle: subtitle, close: { isPresented.wrappedValue = false },
                        content: content(), footer: footer())
        })
    }

    /// A drawer with no footer.
    public func drawer<Content: View>(
        isPresented: Binding<Bool>, title: String, subtitle: String? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        modifier(OverlayPresenter(isPresented: isPresented, kind: .drawer, blocking: true) {
            DrawerPanel<Content, EmptyView>(title: title, subtitle: subtitle,
                                            close: { isPresented.wrappedValue = false },
                                            content: content(), footer: nil)
        })
    }

    /// `ConfirmDialog`: a 440-point modal with the question as its title, one
    /// sentence of `.hint` under it, Cancel on the left and the confirming
    /// button — danger or primary — on the right, disabled while `busy`.
    public func confirmDialog(isPresented: Binding<Bool>, title: String, body: String, confirmLabel: String,
                              danger: Bool = false, busy: Bool = false,
                              onConfirm: @escaping () -> Void) -> some View {
        modal(isPresented: isPresented, title: title, width: 440) {
            Hint(body)
        } footer: {
            Button(S.common.cancel) { isPresented.wrappedValue = false }
                .buttonStyle(.btn())
            Button(confirmLabel, action: onConfirm)
                .buttonStyle(.btn(danger ? .danger : .primary))
                .disabled(busy)
        }
    }

    /// A popover panel anchored to this view, for a panel whose opening is not
    /// a click on the view itself — the web's `Popover` owns its trigger, and
    /// `Popover` below is that; this is the same panel for everything else.
    /// Escape and a press outside the panel and this view close it.
    public func anchoredPanel<Panel: View>(isPresented: Binding<Bool>, align: PopoverAlign = .start,
                                           side: PopoverSide = .bottom,
                                           @ViewBuilder content: @escaping () -> Panel) -> some View {
        modifier(AnchoredPanel(isPresented: isPresented, align: align, side: side, panel: content))
    }
}

private struct AnchoredPanel<Panel: View>: ViewModifier {
    @Binding var isPresented: Bool
    let align: PopoverAlign
    let side: PopoverSide
    @ViewBuilder let panel: () -> Panel
    @State private var id = UUID()
    @State private var openedAt = 0

    func body(content: Content) -> some View {
        content
            .onChange(of: isPresented, initial: true) { _, open in
                if open { openedAt = OverlayClock.next() }
            }
            .transformAnchorPreference(key: OverlayEntriesKey.self, value: .bounds) { entries, anchor in
                guard isPresented else { return }
                entries.append(OverlayEntry(id: id, kind: .popover(anchor: anchor, align: align, side: side),
                                            openedAt: openedAt, content: AnyView(panel()),
                                            dismiss: { isPresented = false }))
            }
    }
}
