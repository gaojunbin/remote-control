import Testing
import Foundation
@testable import RCCore

@Suite("Transport")
struct TransportTests {
    @Test("An https origin canonicalises", arguments: [
        ("HTTPS://RC.Example.com:443/", "https://rc.example.com"),
        ("rc.example.com", "https://rc.example.com"),
        ("https://rc.example.com:8443", "https://rc.example.com:8443")
    ])
    func canonicalOrigins(input: String, expected: String) throws {
        #expect(try GatewayEndpoint(input).origin == expected)
    }

    @Test("Plain http is accepted only for a development host", arguments: [
        "http://127.0.0.1:8787", "http://localhost:8787", "http://192.168.1.20:8787",
        "http://10.0.0.5:8787", "http://172.20.0.4:8787", "http://mac-studio.local:8787"
    ])
    func developmentOrigins(input: String) throws {
        let endpoint = try GatewayEndpoint(input)
        #expect(!endpoint.isSecure)
        #expect(endpoint.socketURL(path: "/ws/app").scheme == "ws")
    }

    @Test("A public host over plain http is refused", arguments: [
        "http://rc.example.com", "http://8.8.8.8", "http://172.32.0.1"
    ])
    func publicHttpRefused(input: String) {
        #expect(throws: TransportError.self) { _ = try GatewayEndpoint(input) }
    }

    @Test("Credentials, paths and queries are refused in an origin", arguments: [
        "https://user:pass@rc.example.com", "https://rc.example.com/path",
        "https://rc.example.com?token=abc", "ftp://rc.example.com", ""
    ])
    func malformedOrigins(input: String) {
        #expect(throws: TransportError.self) { _ = try GatewayEndpoint(input) }
    }

    @Test("A session link round-trips and rejects a web URL")
    func sessionLinks() throws {
        let link = SessionLink(deviceID: "d", sessionID: "s")
        let url = try #require(link.url)
        #expect(url.absoluteString == "remotecontrol://session?device=d&id=s")
        #expect(SessionLink(url: url) == link)
        #expect(SessionLink(url: URL(string: "https://example.com/session?device=d&id=s")!) == nil)
    }

    @Test("A push payload from a newer protocol is refused")
    func pushVersionGate() {
        #expect(throws: (any Error).self) {
            _ = try PushRoute(userInfo: Data(#"{"rc":{"v":2,"device_id":"d","session_id":"s"}}"#.utf8))
        }
    }

    @Test("A secret round-trips through the in-memory store")
    func secretStore() async throws {
        let store = MemorySecretStore()
        await store.write(Data("token".utf8), key: "k")
        #expect(await store.read(key: "k") == Data("token".utf8))
        await store.remove(key: "k")
        #expect(await store.read(key: "k") == nil)
    }
}
