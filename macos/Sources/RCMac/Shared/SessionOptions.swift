import Foundation
import RCCore

/// `web/src/features/chat/sessionOptions.ts`: what `session.set` can carry from
/// the composer. A21 adds the speed tier.
///
/// Absent and null are different for the speed: `.some(nil)` is the standard
/// tier, which is a change, while `nil` — leaving it out — is not.
public struct SessionOptions: Sendable, Hashable {
    public var model: String?
    public var permissionMode: String?
    public var effort: String?
    public var speed: String??

    public init(model: String? = nil, permissionMode: String? = nil, effort: String? = nil,
                speed: String?? = nil) {
        self.model = model
        self.permissionMode = permissionMode
        self.effort = effort
        self.speed = speed
    }

    /// The session as it will be once the device accepts the patch.
    public func applied(to session: Session) -> Session {
        var next = session
        if let model { next.model = model }
        if let permissionMode { next.permissionMode = permissionMode }
        if let effort { next.effort = effort }
        if let speed { next.speed = speed }
        return next
    }
}
