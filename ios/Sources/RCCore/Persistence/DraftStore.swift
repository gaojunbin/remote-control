import Foundation

/// Per-session composer drafts, scoped to the account that wrote them.
///
/// Drafts survive backgrounding and a dropped connection, and switching
/// accounts or gateways never shows another account's text.
public actor DraftStore {
    private struct Record: Codable {
        var schema: Int
        var drafts: [String: String]
    }

    private static let schemaVersion = 1
    private let directory: URL
    private var cache: [String: [String: String]] = [:]

    public init(directory: URL? = nil) {
        if let directory {
            self.directory = directory
        } else {
            let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                     appropriateFor: nil, create: true))
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
            self.directory = base.appending(path: "RemoteControl/Drafts", directoryHint: .isDirectory)
        }
    }

    public func draft(account: String, key: String) -> String {
        drafts(account: account)[key] ?? ""
    }

    public func setDraft(_ text: String, account: String, key: String) {
        var current = drafts(account: account)
        if text.isEmpty { current.removeValue(forKey: key) } else { current[key] = text }
        cache[account] = current
        persist(account: account, drafts: current)
    }

    public func clear(account: String) {
        cache[account] = [:]
        try? FileManager.default.removeItem(at: fileURL(account: account))
    }

    private func drafts(account: String) -> [String: String] {
        if let cached = cache[account] { return cached }
        guard let data = try? Data(contentsOf: fileURL(account: account)),
              let record = try? JSONDecoder().decode(Record.self, from: data),
              record.schema == Self.schemaVersion else {
            cache[account] = [:]
            return [:]
        }
        cache[account] = record.drafts
        return record.drafts
    }

    private func persist(account: String, drafts: [String: String]) {
        guard let data = try? JSONEncoder().encode(Record(schema: Self.schemaVersion, drafts: drafts)) else { return }
        ProtectedFile.write(data, to: fileURL(account: account), directory: directory)
    }

    private func fileURL(account: String) -> URL {
        directory.appending(path: "\(LocalCache.slug(account)).json")
    }
}
