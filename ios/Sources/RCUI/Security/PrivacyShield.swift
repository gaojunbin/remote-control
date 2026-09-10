#if os(iOS)
import SwiftUI
import UIKit

/// Covers the whole scene, including any presented sheet, while iOS snapshots it.
struct PrivacyShield: UIViewRepresentable {
    let visible: Bool

    func makeUIView(context: Context) -> ShieldAnchor { ShieldAnchor() }
    func updateUIView(_ view: ShieldAnchor, context: Context) { view.shieldVisible = visible }
    static func dismantleUIView(_ view: ShieldAnchor, coordinator: ()) { view.removeShield() }

    final class ShieldAnchor: UIView {
        var shieldVisible = false { didSet { updateShield() } }
        private var shield: UIWindow?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            updateShield()
        }

        func removeShield() {
            shield?.isHidden = true
            shield?.rootViewController = nil
            shield = nil
        }

        private func updateShield() {
            guard shieldVisible, let scene = window?.windowScene else { removeShield(); return }
            if shield?.windowScene !== scene {
                removeShield()
                let cover = UIWindow(windowScene: scene)
                cover.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue + 1)
                cover.rootViewController = UIHostingController(rootView: AppPrivacyCover())
                cover.isUserInteractionEnabled = false
                shield = cover
            }
            // Do not make this the key window; keyboard and modal ownership stay intact.
            shield?.isHidden = false
        }
    }
}
#endif
