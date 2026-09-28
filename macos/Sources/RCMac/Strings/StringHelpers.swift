import Foundation
import RCCore

/// The helpers at the foot of `web/src/strings.ts`: the names the app prints
/// for ids a device or the gateway sent, each falling back to the id itself.
extension S {
    /// Product names, never translated. A25 added the last two.
    public static let agentLabels: [String: String] = [
        "claude": "Claude Code",
        "codex": "Codex",
        "grok": "Grok Build",
        "pi": "pi"
    ]

    public static func agentLabel(_ agent: String) -> String { agentLabels[agent] ?? agent }

    /// The platforms a device reports, written the way their makers write them.
    /// The device row says the word, never the raw id (`docs/DESIGN.md` § "The
    /// device row"); a platform this table does not know is printed as the
    /// device sent it.
    public static let platformLabels: [String: String] = [
        "macos": "macOS",
        "linux": "Linux"
    ]

    public static func platformLabel(_ platform: String) -> String { platformLabels[platform] ?? platform }

    /// A33: the vendors an `AgentAccount.provider` can name, as they write
    /// themselves. Never translated, and never extended with agent ids — a
    /// provider this table does not know is printed as the device reported it.
    public static let vendorLabels: [String: String] = [
        "anthropic": "Anthropic",
        "openai": "OpenAI",
        "xai": "xAI"
    ]

    public static func vendorLabel(_ provider: String) -> String { vendorLabels[provider] ?? provider }

    /// The two interface languages, each written in its own script.
    public static func interfaceLanguageLabel(_ language: InterfaceLanguage) -> String {
        switch language {
        case .en: "English"
        case .zhHans: "中文"
        }
    }

    public static func stateLabel(_ state: String) -> String { labels.state[state] ?? state }

    public static func dotToneLabel(_ tone: String) -> String { labels.dotTone[tone] ?? tone }

    public static func timelineDetailLabel(_ detail: TimelineDetail) -> String { labels.timelineDetail[detail] }

    /// A24: "Admin" or "Member", and the id itself for a role this app is too old for.
    public static func roleLabel(_ role: String) -> String { labels.role[role] ?? role }

    /// A24: "Active" or "Disabled".
    public static func userStateLabel(_ state: String) -> String { labels.userState[state] ?? state }

    /// Row label for a session: where it came from, never what it is doing
    /// (`docs/DESIGN.md` § "The session row says where it came from"). The
    /// state is the dot's colour alone, so a row never says the same thing twice.
    public static func sessionOriginLabel(_ session: Session) -> String {
        labels.origin[session.origin.rawValue] ?? session.origin.rawValue
    }

    /// The name every surface prints for a session. A thread the agent has not
    /// named yet arrives with an empty `title`; the row, the chat header and the
    /// sidebar all fall back to the same words in the title's own type, rather
    /// than leaving a blank line above the meta (`docs/DESIGN.md` § "Session
    /// lists"). The session search reads the same value, so an untitled row is
    /// found by those words too.
    public static func sessionTitle(_ session: Session) -> String {
        let title = session.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? sessions.untitled : title
    }
}
