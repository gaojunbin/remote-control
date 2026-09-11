import SwiftUI
#if os(iOS)
import UIKit
#endif

extension View {
    /// A tap that lands anywhere on this screen other than a text field puts
    /// the keyboard away.
    ///
    /// It is a gesture recogniser on the screen's own view rather than a
    /// transparent overlay: an overlay would have to decide what to let
    /// through, and every control it guessed wrong about would need two taps —
    /// one to dismiss and one to act. This one cancels no touches at all, so
    /// buttons, menus, disclosures and links keep working on the first tap and
    /// the keyboard goes at the same time.
    public func dismissesKeyboardOnBackgroundTap() -> some View {
        #if os(iOS)
        background(BackgroundTapDismiss().allowsHitTesting(false))
        #else
        self
        #endif
    }
}

#if os(iOS)
/// Installs the recogniser for `dismissesKeyboardOnBackgroundTap()` and takes
/// it away again when the screen goes.
private struct BackgroundTapDismiss: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let probe = ProbeView()
        probe.isUserInteractionEnabled = false
        probe.backgroundColor = .clear
        probe.onAttach = { [weak coordinator = context.coordinator] host in
            coordinator?.attach(to: host)
        }
        probe.onDetach = { [weak coordinator = context.coordinator] in coordinator?.detach() }
        return probe
    }

    func updateUIView(_ view: UIView, context: Context) {}

    static func dismantleUIView(_ view: UIView, coordinator: Coordinator) {
        coordinator.detach()
    }

    /// A view that says nothing except when it joins and leaves a window. The
    /// recogniser cannot be installed before then, because the screen it
    /// belongs to does not exist yet.
    private final class ProbeView: UIView {
        var onAttach: ((UIView) -> Void)?
        var onDetach: (() -> Void)?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil, let host = Self.screen(of: self) else {
                onDetach?()
                return
            }
            onAttach?(host)
        }

        /// The view of the controller this screen belongs to, so a sheet
        /// presented over it carries its own gesture rather than inheriting
        /// one that reaches the whole app.
        private static func screen(of view: UIView) -> UIView? {
            var responder: UIResponder? = view
            while let next = responder?.next {
                if let controller = next as? UIViewController { return controller.view }
                responder = next
            }
            return view.window
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private weak var host: UIView?
        private var recognizer: UITapGestureRecognizer?

        func attach(to host: UIView) {
            guard self.host !== host else { return }
            detach()
            let tap = UITapGestureRecognizer(target: self, action: #selector(handle))
            // Nothing is swallowed: whatever was under the tap still receives
            // it, so no control ever needs a second tap.
            tap.cancelsTouchesInView = false
            tap.delaysTouchesBegan = false
            tap.delaysTouchesEnded = false
            tap.delegate = self
            host.addGestureRecognizer(tap)
            self.host = host
            recognizer = tap
        }

        func detach() {
            if let recognizer { host?.removeGestureRecognizer(recognizer) }
            recognizer = nil
            host = nil
        }

        @objc
        func handle(_ recognizer: UITapGestureRecognizer) {
            guard let host = recognizer.view else { return }
            let point = recognizer.location(in: host)
            // A tap inside the field is a request to put the cursor somewhere,
            // never a request to close the keyboard.
            if let hit = host.hitTest(point, with: nil), Self.isTextInput(hit) { return }
            host.window?.endEditing(true)
        }

        /// The recogniser runs alongside whatever the tap actually hit.
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }

        private static func isTextInput(_ view: UIView) -> Bool {
            var candidate: UIView? = view
            while let current = candidate {
                if current is UITextView || current is UITextField { return true }
                candidate = current.superview
            }
            return false
        }
    }
}
#endif
