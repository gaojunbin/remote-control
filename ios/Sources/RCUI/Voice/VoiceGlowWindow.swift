import SwiftUI
#if os(iOS)
import UIKit
#endif

/// Puts the listening glow around the whole display rather than around the
/// control that started it.
///
/// On iOS the light needs its own window: a sheet is presented by UIKit above
/// the hosting controller, so an overlay drawn as a sibling view renders behind
/// it, and the composer's own bounds are nowhere near the edge of the screen.
/// `PrivacyShield` and `AppLockWindow` solve the same problem the same way.
/// The macOS preview host has no windows to stack, so it keeps the overlay.
struct VoiceGlowPresenter: View {
    let active: Bool
    let level: Double
    let reduceMotion: Bool

    var body: some View {
        #if os(iOS)
        VoiceGlowWindow(active: active, level: level, reduceMotion: reduceMotion)
            .frame(width: 0, height: 0)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        #else
        VoiceGlowRoot(active: active, level: level, reduceMotion: reduceMotion,
                      cornerRadius: DisplayCorner.fallbackRadius)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        #endif
    }
}

/// The hosted root. It is a named type rather than an opaque one so the
/// hosting controller keeps its identity, and with it the glow's animation
/// state, across the many level updates a second brings.
struct VoiceGlowRoot: View {
    let active: Bool
    let level: Double
    let reduceMotion: Bool
    let cornerRadius: CGFloat

    var body: some View {
        VoiceEdgeGlow(active: active, level: level, reduceMotion: reduceMotion,
                      cornerRadius: cornerRadius)
            .ignoresSafeArea()
    }
}

#if os(iOS)
private struct VoiceGlowWindow: UIViewRepresentable {
    let active: Bool
    let level: Double
    let reduceMotion: Bool

    func makeUIView(context: Context) -> GlowAnchor { GlowAnchor() }

    func updateUIView(_ view: GlowAnchor, context: Context) {
        view.reduceMotion = reduceMotion
        view.level = level
        view.active = active
    }

    static func dismantleUIView(_ view: GlowAnchor, coordinator: ()) { view.removeGlow() }

    final class GlowAnchor: UIView {
        var reduceMotion = false
        var level = 0.0 { didSet { refresh() } }
        var active = false { didSet { updateGlow() } }
        private var glow: UIWindow?
        private var host: UIHostingController<VoiceGlowRoot>?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            updateGlow()
        }

        func removeGlow() {
            glow?.isHidden = true
            glow?.rootViewController = nil
            glow = nil
            host = nil
        }

        private func updateGlow() {
            guard active, let scene = window?.windowScene else { removeGlow(); return }
            if glow?.windowScene !== scene {
                removeGlow()
                let cover = UIWindow(windowScene: scene)
                // Above the app and anything it presents, below an alert and
                // below the privacy shield and the lock, which must cover this.
                cover.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.normal.rawValue + 1)
                cover.isUserInteractionEnabled = false
                cover.backgroundColor = .clear
                let host = UIHostingController(rootView: content)
                host.view.backgroundColor = .clear
                host.view.isOpaque = false
                cover.rootViewController = host
                self.host = host
                glow = cover
            }
            // Never the key window: keyboard and modal ownership stay with the
            // app, which is the whole point of drawing this somewhere else.
            glow?.isHidden = false
            refresh()
        }

        private func refresh() {
            host?.rootView = content
        }

        private var content: VoiceGlowRoot {
            VoiceGlowRoot(active: active, level: level, reduceMotion: reduceMotion,
                          cornerRadius: DisplayCorner.radius(bottomSafeArea: window?.safeAreaInsets.bottom ?? 0))
        }
    }
}
#endif
