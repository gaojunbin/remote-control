import Foundation

/// Whether the phone's idle timer is held off right now.
///
/// `docs/DESIGN.md` § "The screen stays awake in a conversation": the
/// conversation is the unit, not the microphone. Dictating a long message and
/// waiting for a turn with the phone propped up are the same posture, and
/// neither may end because the screen locked on its own. The list, Settings and
/// every other screen leave the timer alone, and an app in the background never
/// holds the screen of whatever is in front of it.
public enum ScreenAwakeRule {
    public static func awake(chatOnScreen: Bool, sceneActive: Bool) -> Bool {
        chatOnScreen && sceneActive
    }
}
