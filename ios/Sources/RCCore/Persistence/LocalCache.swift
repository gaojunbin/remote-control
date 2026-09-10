import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// The cached snapshot for one (gateway origin, username) pair.
public struct CachedWorkspace: Codable, Sendable {
    public var devices: [Device]
    public var sessions: [Session]
    /// Final events for the sessions the user opened most recently.
    public var transcripts: [String: [SessionEvent]]
    public var savedAt: Int64

    public init(devices: [Device] = [], sessions: [Session] = [],
                transcripts: [String: [SessionEvent]] = [:], savedAt: Int64 = 0) {
        self.devices = devices
        self.sessions = sessions
        self.transcripts = transcripts
        self.savedAt = savedAt
    }
}

/// A small versioned JSON cache so the session list paints before the socket
/// connects.
///
/// The schema version is deliberately independent of the wire protocol: a
/// protocol bump must not silently throw away every cached transcript and
/// draft. Bump `schemaVersion` only when this file's own shape changes.
public actor LocalCache {
    public static let schemaVersion = 1
    /// Recent sessions kept on disk, and how much of each.
    public static let sessionLimit = 12
    public static let eventLimit = 200

    private struct Record: Codable {
        var schema: Int
        var workspace: CachedWorkspace
    }

    private let directory: URL
    private let fileManager = FileManager.default

    public init(directory: URL? = nil) {
        if let directory {
            self.directory = directory
        } else {
            let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                     appropriateFor: nil, create: true))
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
            self.directory = base.appending(path: "RemoteControl/Cache", directoryHint: .isDirectory)
        }
    }

    public func load(origin: String, username: String) -> CachedWorkspace? {
        guard let data = try? Data(contentsOf: fileURL(origin: origin, username: username)),
              let record = try? JSONDecoder().decode(Record.self, from: data),
              record.schema == Self.schemaVersion else { return nil }
        return record.workspace
    }

    public func save(_ workspace: CachedWorkspace, origin: String, username: String) {
        var trimmed = workspace
        trimmed.transcripts = Self.trim(workspace.transcripts,
                                        keeping: workspace.sessions.map(\.id))
        let record = Record(schema: Self.schemaVersion, workspace: trimmed)
        guard let data = try? JSONEncoder().encode(record) else { return }
        ProtectedFile.write(data, to: fileURL(origin: origin, username: username), directory: directory)
    }

    public func clear(origin: String, username: String) {
        try? fileManager.removeItem(at: fileURL(origin: origin, username: username))
    }

    /// Keep the newest events for at most `sessionLimit` sessions.
    public static func trim(_ transcripts: [String: [SessionEvent]], keeping order: [String]) -> [String: [SessionEvent]] {
        let ranked = order.filter { transcripts[$0] != nil }.prefix(sessionLimit)
        let keys = ranked.isEmpty ? Array(transcripts.keys.prefix(sessionLimit)) : Array(ranked)
        var result: [String: [SessionEvent]] = [:]
        for key in keys {
            guard let events = transcripts[key] else { continue }
            result[key] = Array(events.suffix(eventLimit))
        }
        return result
    }

    /// One file per account, so signing in as someone else never paints the
    /// previous account's transcripts.
    private func fileURL(origin: String, username: String) -> URL {
        directory.appending(path: "\(Self.slug(origin))_\(Self.slug(username)).json")
    }

    /// A readable prefix plus a digest, so two origins that differ only in
    /// punctuation or past the 60th character cannot share a file.
    public static func slug(_ value: String) -> String {
        let allowed = CharacterSet.alphanumerics
        let mapped = value.unicodeScalars.map { allowed.contains($0) ? Character($0) : "-" }
        return "\(String(mapped.prefix(60)))-\(digest(value))"
    }

    private static func digest(_ value: String) -> String {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash &*= 0x100000001b3
        }
        return String(hash, radix: 36)
    }
}

/// Writes app-private JSON that is unreadable before first unlock and stays out
/// of device backups. The cache holds prompts, tool output and diffs — exactly
/// the content the product keeps out of push payloads and diagnostics.
enum ProtectedFile {
    static func write(_ data: Data, to url: URL, directory: URL) {
        var directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var resourceValues = URLResourceValues()
        resourceValues.isExcludedFromBackup = true
        try? directory.setResourceValues(resourceValues)
        #if canImport(UIKit)
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try? data.write(to: url, options: .atomic)
        #endif
    }
}
