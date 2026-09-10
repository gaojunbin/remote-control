#if os(iOS)
import SwiftUI
import UIKit

/// Hosts the lock screen in its own window, above the alert level.
///
/// A sheet is presented by UIKit above the hosting controller, so a lock drawn
/// as a sibling view renders *behind* it. `PrivacyShield` already solves this
/// with a separate window; the lock needs the same treatment, plus touch input
/// so the unlock button works.
struct AppLockWindow: UIViewRepresentable {
    let locked: Bool
    let onUnlock: () -> Void

    func makeUIView(context: Context) -> LockAnchor { LockAnchor() }

    func updateUIView(_ view: LockAnchor, context: Context) {
        view.onUnlock = onUnlock
        view.locked = locked
    }

    static func dismantleUIView(_ view: LockAnchor, coordinator: ()) { view.removeLock() }

    final class LockAnchor: UIView {
        var onUnlock: (() -> Void)?
        var locked = false { didSet { updateLock() } }
        private var lock: UIWindow?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            updateLock()
        }

        func removeLock() {
            lock?.isHidden = true
            lock?.rootViewController = nil
            lock = nil
        }

        private func updateLock() {
            guard locked, let scene = window?.windowScene else { removeLock(); return }
            if lock?.windowScene !== scene {
                removeLock()
                let cover = UIWindow(windowScene: scene)
                cover.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue + 2)
                cover.rootViewController = UIHostingController(
                    rootView: AppLockView { [weak self] in self?.onUnlock?() })
                lock = cover
            }
            // Never the key window: keyboard and modal ownership stay with the
            // app. Touches still reach it, which is all the unlock button needs.
            lock?.isHidden = false
        }
    }
}
#endif
