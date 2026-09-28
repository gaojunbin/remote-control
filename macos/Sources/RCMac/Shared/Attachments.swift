import Foundation
import RCCore

/// `web/src/features/chat/attachments.ts`: the attachment limits of
/// `session.send` (PROTOCOL §5), which are RCCore's `RequestLimits`, and the
/// one check the composer makes before sending.
public enum AttachmentLimits {
    public static let maxAttachments = RequestLimits.maxAttachments
    public static let maxAttachmentBytes = RequestLimits.maxAttachmentBytes
    public static let maxTextBytes = RequestLimits.maxTextBytes

    /// Measured in UTF-8 bytes, as the wire measures it.
    public static func textTooLong(_ text: String) -> Bool { text.utf8.count > maxTextBytes }
}
