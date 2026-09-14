import SwiftUI
import RCCore
#if os(iOS)
import UIKit
#endif

/// The conversations on screen at this moment.
///
/// A notification tap pushes a second conversation over the one being read, and
/// SwiftUI delivers the new view's `onAppear` before the covered view's
/// `onDisappear`. Counting is what stops the idle timer being handed back while
/// a conversation is still in front of the reader.
@MainActor
private final class OpenConversations {
    static let shared = OpenConversations()
    private var count = 0

    var any: Bool { count > 0 }
    func entered() { count += 1 }
    func left() { count = max(0, count - 1) }
}

/// Holds the idle timer off while a conversation is on screen and the app is in
/// the foreground. `ScreenAwakeRule` in RCCore is the rule; this is the only
/// place in the app that touches the timer, and `ChatView` is its only caller.
private struct ScreenAwakeModifier: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .onAppear {
                OpenConversations.shared.entered()
                apply(phase: scenePhase)
            }
            .onDisappear {
                OpenConversations.shared.left()
                apply(phase: scenePhase)
            }
            .onChange(of: scenePhase) { _, phase in apply(phase: phase) }
    }

    private func apply(phase: ScenePhase) {
        let awake = ScreenAwakeRule.awake(chatOnScreen: OpenConversations.shared.any,
                                          sceneActive: phase == .active)
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = awake
        #endif
    }
}

extension View {
    /// Dictating a long message, or watching a turn with the phone propped up,
    /// must never end because the screen locked on its own.
    func keepsScreenAwake() -> some View { modifier(ScreenAwakeModifier()) }
}
