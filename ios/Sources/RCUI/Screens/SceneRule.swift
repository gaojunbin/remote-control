import SwiftUI

/// What a scene phase means for the app, in one place.
///
/// Only the background is leaving the app. Control Centre, the app switcher's
/// peek, an incoming-call banner and a system permission alert make the scene
/// `.inactive` and nothing more, and `docs/DESIGN.md` § "The composer" (Voice)
/// says what follows from that: "dictation listens through them — as the app
/// lock stays down through them, engaging when the app returns from the
/// background and not before, exactly as its own footer says."
///
/// The privacy shield is the one thing that does follow `.inactive`, because
/// the switcher's snapshot is taken there and must not carry a transcript.
public enum SceneRule {
    /// The app has left the screen altogether: dictation is suspended with its
    /// words kept, the app lock engages, and the draft and transcript are
    /// written out because iOS may reclaim the process without another chance.
    public static func isBackground(_ phase: ScenePhase) -> Bool { phase == .background }

    /// The app is on screen and reading the stream: the conversation holds the
    /// idle timer off, and a finished turn raises the app's own banner.
    public static func isForeground(_ phase: ScenePhase) -> Bool { phase == .active }

    /// The privacy shield covers the app the moment it stops being active, so
    /// nothing of a conversation is in the snapshot the system takes.
    public static func shields(_ phase: ScenePhase) -> Bool { phase != .active }
}
