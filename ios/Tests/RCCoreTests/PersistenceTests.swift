import Testing
import Foundation
@testable import RCCore

@Suite("Persistence")
struct PersistenceTests {
    private func temporaryDirectory() -> URL {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "rc-tests-\(UUID().uuidString)", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test("The cache is keyed by gateway and account")
    func cacheIsolation() async throws {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let cache = LocalCache(directory: directory)
        let session = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "T", cwd: "/tmp")
        await cache.save(CachedWorkspace(sessions: [session]),
                         origin: "https://a.example.com", username: "admin")
        #expect(await cache.load(origin: "https://a.example.com", username: "admin")?.sessions.count == 1)
        #expect(await cache.load(origin: "https://a.example.com", username: "other") == nil)
        #expect(await cache.load(origin: "https://b.example.com", username: "admin") == nil)
    }

    @Test("The cache schema version is independent of the wire protocol")
    func cacheVersioning() {
        #expect(LocalCache.schemaVersion == 1)
        #expect(RemoteProtocol.version == 1)
        // Bumping one must never be forced by the other; this test exists so a
        // future change has to state its intent in both places.
    }

    @Test("Transcripts are trimmed to the most recent sessions and events")
    func trimming() {
        let event = SessionEvent(seq: 1, ts: 1, kind: "notice",
                                 body: .notice(NoticePayload(level: .info, text: "x")))
        var transcripts: [String: [SessionEvent]] = [:]
        for index in 0..<30 { transcripts["s\(index)"] = Array(repeating: event, count: 500) }
        let trimmed = LocalCache.trim(transcripts, keeping: (0..<30).map { "s\($0)" })
        #expect(trimmed.count == LocalCache.sessionLimit)
        #expect(trimmed["s0"]?.count == LocalCache.eventLimit)
    }

    @Test("Drafts never cross accounts or sessions")
    func draftScoping() async {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = DraftStore(directory: directory)
        await store.setDraft("half a thought", account: "a|admin", key: "d/s")
        let reloaded = DraftStore(directory: directory)
        #expect(await reloaded.draft(account: "a|admin", key: "d/s") == "half a thought")
        #expect(await reloaded.draft(account: "b|admin", key: "d/s") == "")
        #expect(await reloaded.draft(account: "a|admin", key: "d/other") == "")
    }
}
