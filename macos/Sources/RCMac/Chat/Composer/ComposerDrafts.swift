import Foundation
import Observation

/// The files of every session's draft, keyed by `<device>/<session>` — the
/// half of `web/src/stores/drafts.ts` RCCore does not hold. The words are the
/// `ChatStore`'s `draft`; the files are here, so opening another conversation
/// shows its own and a return finds them again (`docs/DESIGN.md` § "The
/// composer" → **A draft belongs to its session**).
///
/// A43: while a queued message is being edited, the files the field held wait
/// aside and come back when the edit ends, the way RCCore's `QueuedEdit` keeps
/// the words aside. In memory for the app's life, and emptied on sign-out.
@MainActor
@Observable
final class ComposerDrafts {
    private var files: [String: [ComposerAttachment]] = [:]
    private var aside: [String: [ComposerAttachment]] = [:]

    func attachments(_ key: String) -> [ComposerAttachment] { files[key] ?? [] }

    /// Append files, never past the contract's cap. Two attach operations can
    /// be in flight at once — a paste and the file dialog — and each counted
    /// its budget before it began, so the cap is applied here, where the list
    /// is written. Returns how many had to be dropped.
    @discardableResult
    func add(_ new: [ComposerAttachment], to key: String) -> Int {
        guard !new.isEmpty else { return 0 }
        let current = attachments(key)
        let dropped = max(0, current.count + new.count - AttachmentLimits.maxAttachments)
        files[key] = Array((current + new).prefix(AttachmentLimits.maxAttachments))
        return dropped
    }

    func remove(at index: Int, from key: String) {
        var current = attachments(key)
        guard current.indices.contains(index) else { return }
        current.remove(at: index)
        files[key] = current.isEmpty ? nil : current
    }

    /// Hand back the files a refused send took, unless newer ones are there.
    func restore(_ old: [ComposerAttachment], to key: String) {
        guard attachments(key).isEmpty, !old.isEmpty else { return }
        files[key] = old
    }

    func clear(_ key: String) { files[key] = nil }

    /// A43: the field takes a queued message, and its files wait aside.
    func setAside(_ key: String) {
        aside[key] = attachments(key)
        files[key] = nil
    }

    /// A43: the words are back in the line, and the files come back with the
    /// draft that waited aside.
    func endEdit(_ key: String) {
        let back = aside.removeValue(forKey: key) ?? []
        files[key] = back.isEmpty ? nil : back
    }

    func reset() {
        files = [:]
        aside = [:]
    }
}

extension ComposerDrafts {
    private struct Entry {
        weak var model: MacAppModel?
        let drafts: ComposerDrafts
    }

    /// One store per app model: the app has one; a preview render makes one of
    /// its own for each picture, and a store outlives none of them.
    private static var stores: [ObjectIdentifier: Entry] = [:]

    static func of(_ model: MacAppModel) -> ComposerDrafts {
        let id = ObjectIdentifier(model)
        if let entry = stores[id], entry.model === model { return entry.drafts }
        stores = stores.filter { $0.value.model != nil }
        let drafts = ComposerDrafts()
        stores[id] = Entry(model: model, drafts: drafts)
        return drafts
    }
}
