import Foundation
import RCCore
import UniformTypeIdentifiers

/// A file the composer holds for the next message (`AttachmentDraft` in
/// `attachments.ts`): what goes on the wire, and the size its chip prints.
struct ComposerAttachment: Identifiable, Hashable, Sendable {
    let id: UUID
    let name: String
    let mime: String
    let data: Data

    init(id: UUID = UUID(), name: String, mime: String, data: Data) {
        self.id = id
        self.name = name
        self.mime = mime
        self.data = data
    }

    var size: Int { data.count }

    var outbound: OutboundAttachment { OutboundAttachment(id: id, name: name, mime: mime, data: data) }
}

/// Where the files of one attach come from: a file the person chose, dropped
/// or pasted, or the picture on the pasteboard, which a browser hands a page as
/// a file named `image.png`.
enum AttachmentSource: Sendable {
    case file(URL)
    case data(name: String, mime: String, Data)
}

/// `readAttachments` in `web/src/features/chat/attachments.ts`: the files read,
/// and one sentence for each one that could not be. The budget is what was
/// left when the read began; the store enforces the cap again where the list
/// is written, because two reads can be in flight at once.
struct AttachmentRead: Sendable {
    var attachments: [ComposerAttachment] = []
    var errors: [String] = []

    static func read(_ sources: [AttachmentSource], alreadyAttached: Int) -> AttachmentRead {
        var result = AttachmentRead()
        var budget = AttachmentLimits.maxAttachments - alreadyAttached
        for source in sources {
            if budget <= 0 {
                result.errors.append(S.composer.attachTooMany(AttachmentLimits.maxAttachments))
                break
            }
            switch source {
            case .data(let name, let mime, let data):
                if result.admit(name: name, size: data.count) {
                    result.attachments.append(ComposerAttachment(name: name, mime: mime, data: data))
                    budget -= 1
                }
            case .file(let url):
                let name = url.lastPathComponent
                guard result.admit(name: name, size: Self.size(of: url)) else { continue }
                guard let data = Self.contents(of: url) else {
                    result.errors.append(S.composer.attachFailed(name))
                    continue
                }
                result.attachments.append(ComposerAttachment(name: name, mime: Self.mime(of: url), data: data))
                budget -= 1
            }
        }
        return result
    }

    /// A file past the contract's size is refused before it is read.
    private mutating func admit(name: String, size: Int?) -> Bool {
        guard let size, size > AttachmentLimits.maxAttachmentBytes else { return true }
        errors.append(S.composer.attachTooLarge(name, Format.bytes(AttachmentLimits.maxAttachmentBytes)))
        return false
    }

    private static func size(of url: URL) -> Int? {
        (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize
    }

    private static func contents(of url: URL) -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory),
              !isDirectory.boolValue else { return nil }
        return try? Data(contentsOf: url)
    }

    /// The type a browser gives the same file, from its extension.
    static func mime(of url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }
}
