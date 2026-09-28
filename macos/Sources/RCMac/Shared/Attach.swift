import Foundation
import RCCore

/// `web/src/features/chat/attach.ts` — amendments A10/A11: how a session
/// shared with a live terminal behaves.
///
/// `shared` means a CLI process owns the session and the device is attached to
/// it, so the composer, approvals and the queue work exactly as for `remote`.
/// What else the attachment carries is per agent: the device reports
/// `shared_interrupt`, `shared_settings` and `shared_attachments`, each
/// defaulting to false. The Codex app-server daemon and pi's extension carry
/// all three; Grok Build's leader carries the interrupt and the settings but
/// takes no images (A28); the Claude channel takes no images, and carries the
/// interrupt (A42) and the settings only as far as the device can type them
/// into the pseudo-terminal it owns — `shared_settings_keys` says which (A40).
public enum Attach {
    /// True when the device is attached to a terminal-owned session.
    public static func isShared(_ session: Session) -> Bool { session.control == .shared }

    /// True when only the terminal can drive the session.
    public static func isTerminalOnly(_ session: Session) -> Bool { session.control == .terminal }

    /// A10 §4.4: Stop needs the `interrupt` capability *and* a device that
    /// reports `shared_interrupt`. The Codex daemon calls it; an attached Claude
    /// terminal is typed an Escape (A42), and a device with no shim reports
    /// neither.
    public static func canInterruptShared(_ agent: AgentInfo?) -> Bool {
        guard let agent else { return false }
        return agent.sharedInterrupt && agent.supports(.interrupt)
    }

    /// A11/A40 §4.2: one setting's picker on a shared session. The device must
    /// forward `session.set` to the CLI at all, and the setting must be one it
    /// forwards: `shared_settings_keys` names the subset, and its absence means
    /// all four. Claude's pseudo-terminal takes a typed `/model` and `/effort`
    /// but has nothing to type for the permission mode, so that one stays the
    /// terminal's.
    public static func canSetShared(_ agent: AgentInfo?, _ key: SharedSetting) -> Bool {
        agent?.shares(key) ?? false
    }

    /// A11 §4.2: whether attachments reach the CLI through the attachment. When
    /// they cannot, the attachment button is not rendered at all.
    public static func canAttachShared(_ agent: AgentInfo?) -> Bool { agent?.sharedAttachments ?? false }

    /// The one-line hint under "Controlled by the terminal", for a `terminal`
    /// session whose agent could have been attached. `attach_ready` false means
    /// the device is not set up yet; true means this CLI was started without it.
    public static func attachHint(_ agent: AgentInfo?) -> String? {
        guard let agent, let attach = agent.attach else { return nil }
        if agent.attachReady { return S.chat.attachHintRestart }
        // A26: pi's attachment is an extension the device installs into pi itself.
        // A28: Grok Build joins a leader only when the person's own config says
        // so, so the hint asks for the setting and for the CLI to be started again.
        switch attach.rawValue {
        case "daemon": return S.chat.attachHintDaemon
        case "extension": return S.chat.attachHintExtension
        case "leader": return S.chat.attachHintLeader
        default: return S.chat.attachHintChannel
        }
    }

    /// The chip on a user bubble that says where the message got to. A19 leaves
    /// one case: a message the CLI read as mid-turn data, which the device will
    /// send again. A message the device is still holding is a queue entry, not
    /// a bubble.
    public static func deliveryLabel(_ delivery: String?) -> String? {
        delivery == "absorbed" ? S.chat.deliveryAbsorbed : nil
    }
}
