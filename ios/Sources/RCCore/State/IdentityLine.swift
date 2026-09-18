import Foundation

/// What the Settings header says, for a reader who cannot see it.
///
/// The header draws four things — the initials, the username, `role · host` and
/// a dot — and reads as one element, so the words for it are built here: who is
/// signed in, as what, where, and what the link is doing. The dot's word is
/// printed nowhere else (`docs/DESIGN.md` § "The Settings screen").
public enum IdentityLine {
    public static func label(user: UserIdentity, host: String, phase: ConnectionPhase) -> String {
        [user.username, user.role.title, host, ConnectionTone.word(phase)]
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
    }
}
