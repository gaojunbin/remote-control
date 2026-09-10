import Foundation
import RCCore

enum PersistenceChecks {
    static func run() async -> CheckResult {
        let checks = CheckRunner(group: "persistence")
        await cache(checks)
        await drafts(checks)
        await secrets(checks)
        trimming(checks)
        return checks.result()
    }

    private static func temporaryDirectory() -> URL {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "rc-verify-\(UUID().uuidString)", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private static func cache(_ checks: CheckRunner) async {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = LocalCache(directory: directory)
        let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T", cwd: "/tmp")
        let device = Device(deviceID: "d", name: "mac", platform: .macos, hostname: "h", arch: "arm64",
                            clientVersion: "0.1.0", online: true, lastSeen: 1, createdAt: 1)
        await cache.save(CachedWorkspace(devices: [device], sessions: [session]),
                         origin: "https://rc.example.com", username: "admin")

        let loaded = await cache.load(origin: "https://rc.example.com", username: "admin")
        checks.equal(loaded?.sessions.count, 1, "the cached session list round-trips")
        checks.equal(loaded?.devices.first?.name, "mac", "the cached device list round-trips")

        let other = await cache.load(origin: "https://rc.example.com", username: "someone-else")
        checks.expect(other == nil, "a different account never reads this account's cache")
        let otherOrigin = await cache.load(origin: "https://other.example.com", username: "admin")
        checks.expect(otherOrigin == nil, "a different gateway never reads this gateway's cache")

        // The cache version is independent of the wire protocol version: a
        // protocol bump must not silently discard every cached transcript.
        checks.equal(LocalCache.schemaVersion, 1, "the cache schema version is its own number")
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        let path = directory.appending(path: names.first { $0.hasSuffix(".json") } ?? "")
        if var json = try? JSONSerialization.jsonObject(with: Data(contentsOf: path)) as? [String: Any] {
            json["schema"] = LocalCache.schemaVersion + 1
            try? JSONSerialization.data(withJSONObject: json).write(to: path)
            let stale = await cache.load(origin: "https://rc.example.com", username: "admin")
            checks.expect(stale == nil, "a record from a newer cache schema is ignored")
        } else {
            checks.expect(false, "the cache record is readable JSON")
        }

        await cache.clear(origin: "https://rc.example.com", username: "admin")
        let cleared = await cache.load(origin: "https://rc.example.com", username: "admin")
        checks.expect(cleared == nil, "signing out clears the cache")
    }

    private static func drafts(_ checks: CheckRunner) async {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = DraftStore(directory: directory)
        await store.setDraft("half a thought", account: "https://rc|admin", key: "d/s")
        let reloaded = DraftStore(directory: directory)
        checks.equal(await reloaded.draft(account: "https://rc|admin", key: "d/s"), "half a thought",
                     "a draft survives a relaunch")
        checks.equal(await reloaded.draft(account: "https://rc|other", key: "d/s"), "",
                     "drafts never cross accounts")
        checks.equal(await reloaded.draft(account: "https://rc|admin", key: "d/other"), "",
                     "drafts never cross sessions")
        await reloaded.setDraft("", account: "https://rc|admin", key: "d/s")
        checks.equal(await reloaded.draft(account: "https://rc|admin", key: "d/s"), "",
                     "an emptied draft is removed")
    }

    private static func secrets(_ checks: CheckRunner) async {
        let store = MemorySecretStore()
        await store.write(Data("token".utf8), key: "k")
        checks.equal(await store.read(key: "k"), Data("token".utf8), "a secret round-trips")
        await store.remove(key: "k")
        let removed = await store.read(key: "k")
        checks.expect(removed == nil, "a removed secret is gone")
    }

    private static func trimming(_ checks: CheckRunner) {
        var transcripts: [String: [SessionEvent]] = [:]
        let event = SessionEvent(seq: 1, ts: 1, kind: "notice",
                                 body: .notice(NoticePayload(level: .info, text: "x")))
        for index in 0..<30 { transcripts["s\(index)"] = Array(repeating: event, count: 500) }
        let order = (0..<30).map { "s\($0)" }
        let trimmed = LocalCache.trim(transcripts, keeping: order)
        checks.equal(trimmed.count, LocalCache.sessionLimit, "only the most recent sessions are kept")
        checks.equal(trimmed["s0"]?.count, LocalCache.eventLimit, "each transcript is capped")
        checks.expect(trimmed["s29"] == nil, "the least recent session is evicted")
    }
}
