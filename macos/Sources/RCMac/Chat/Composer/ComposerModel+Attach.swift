import Foundation
import RCCore

extension ComposerModel {
    /// Whether files are taken at all: not where the attachment button is not
    /// drawn (A11), not on a composer that cannot send, and not while an edited
    /// message is on its way back (A43).
    var acceptsFiles: Bool { gates.showAttach && !gates.disabled && !returning }

    /// Two attach operations can run at once — a paste, then the file dialog
    /// before the paste has been read — and each reads the count it started
    /// with. The cap is enforced where the list is written, and what the second
    /// has to say is added to what the first said rather than replacing it.
    func attach(_ sources: [AttachmentSource]) {
        guard acceptsFiles, !sources.isEmpty else { return }
        let key = key
        let drafts = host.drafts
        let already = drafts.attachments(key).count
        Task {
            let read = await Task.detached { AttachmentRead.read(sources, alreadyAttached: already) }.value
            let dropped = drafts.add(read.attachments, to: key)
            let tooMany = S.composer.attachTooMany(AttachmentLimits.maxAttachments)
            let messages = dropped > 0 && !read.errors.contains(tooMany) ? read.errors + [tooMany] : read.errors
            guard !messages.isEmpty else { return }
            var seen = Set<String>()
            errors = (errors + messages).filter { seen.insert($0).inserted }
        }
    }

    func removeAttachment(at index: Int) {
        host.drafts.remove(at: index, from: key)
    }
}
