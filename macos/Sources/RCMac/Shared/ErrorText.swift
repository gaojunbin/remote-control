import Foundation
import RCCore

/// `web/src/lib/errors.ts`: a rejected gateway request, as a sentence the
/// reader can act on. The words are read when the failure happens, never
/// stored: a sentence captured earlier would keep the language it was built in.
public enum ErrorText {
    /// The web's sentence for a reply's `error.code`, where it has one.
    static func byCode(_ code: String) -> String? {
        switch code {
        case GatewayErrorCode.notFound.rawValue: S.errors.notFound
        case GatewayErrorCode.deviceOffline.rawValue: S.errors.deviceOffline
        case GatewayErrorCode.conflict.rawValue: S.errors.conflictTerminal
        case GatewayErrorCode.timeout.rawValue: S.errors.timeout
        case GatewayErrorCode.unsupported.rawValue: S.errors.unsupported
        case GatewayErrorCode.tooLarge.rawValue: S.errors.tooLarge
        default: nil
        }
    }

    /// The code decides the sentence. A locally minted failure — the socket is
    /// not open, the gateway never answered — carries no message of its own,
    /// so it falls through to the caller's fallback rather than reaching the
    /// screen as an empty line or as a transport's English.
    public static func text(_ error: any Error, fallback: String = S.errors.generic) -> String {
        if let reply = error as? GatewayErrorBody {
            if let sentence = byCode(reply.code.rawValue) { return sentence }
            // A reply with no message of its own decodes with its code as the
            // message, which is not a sentence.
            return reply.message.isEmpty || reply.message == reply.code.rawValue ? fallback : reply.message
        }
        switch error as? TransportError {
        case .requestTimedOut: return S.errors.timeout
        case .http(_, let code?): return byCode(code) ?? fallback
        default: return fallback
        }
    }

    /// A40: what `session.set` and `session.command` say when they are refused.
    /// A `conflict` from either is the device explaining why it could not reach
    /// the session — the terminal it types into is running a turn, or somebody
    /// is typing there — and only the device knows which. Its sentence is shown
    /// as it arrived; the canned one, about taking the session over, would be
    /// wrong. Every other code keeps the app's own words.
    public static func refusal(_ error: any Error, fallback: String) -> String {
        if let reply = error as? GatewayErrorBody, reply.code == .conflict,
           !reply.message.isEmpty, reply.message != reply.code.rawValue {
            return reply.message
        }
        return text(error, fallback: fallback)
    }

    /// A43: what a refused `session.queue_remove` says. `not_found` means the
    /// device delivered the message before the request reached it, for a Remove
    /// as for an edit, so the sentence says that and never the bare code.
    public static func queueRemove(_ error: any Error) -> String {
        if let reply = error as? GatewayErrorBody, reply.code == .notFound {
            return S.composer.alreadySent
        }
        return text(error, fallback: S.errors.queueRemoveFailed)
    }
}
