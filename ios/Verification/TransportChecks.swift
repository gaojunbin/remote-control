import Foundation
import RCCore

enum TransportChecks {
    static func run() -> CheckResult {
        let checks = CheckRunner(group: "transport")
        canonicalisation(checks)
        development(checks)
        rejections(checks)
        socketURLs(checks)
        return checks.result()
    }

    private static func canonicalisation(_ checks: CheckRunner) {
        checks.noThrow("an https origin canonicalises") {
            let endpoint = try GatewayEndpoint("HTTPS://RC.Example.com:443/")
            guard endpoint.origin == "https://rc.example.com", endpoint.isSecure else {
                throw TransportError.invalidEndpoint
            }
        }
        checks.noThrow("a bare host defaults to https") {
            let endpoint = try GatewayEndpoint("rc.example.com")
            guard endpoint.origin == "https://rc.example.com" else { throw TransportError.invalidEndpoint }
        }
        checks.noThrow("a non-default port is kept") {
            let endpoint = try GatewayEndpoint("https://rc.example.com:8443")
            guard endpoint.origin == "https://rc.example.com:8443" else { throw TransportError.invalidEndpoint }
        }
    }

    /// Plain http is allowed only where a developer's own gateway can live.
    private static func development(_ checks: CheckRunner) {
        for host in ["http://127.0.0.1:8787", "http://localhost:8787", "http://192.168.1.20:8787",
                     "http://10.0.0.5:8787", "http://172.20.0.4:8787", "http://mac-studio.local:8787"] {
            checks.noThrow("\(host) is accepted for development") {
                let endpoint = try GatewayEndpoint(host)
                guard !endpoint.isSecure else { throw TransportError.invalidEndpoint }
            }
        }
        for host in ["http://rc.example.com", "http://8.8.8.8", "http://172.32.0.1"] {
            checks.throwsError("\(host) is refused over plain http") { _ = try GatewayEndpoint(host) }
        }
        checks.expect(GatewayEndpoint.isDevelopmentHost("127.0.0.1"), "loopback is a development host")
        checks.expect(!GatewayEndpoint.isDevelopmentHost("172.15.0.1"), "172.15 is not private")
        checks.expect(GatewayEndpoint.isDevelopmentHost("172.31.255.255"), "172.31 is private")
    }

    private static func rejections(_ checks: CheckRunner) {
        for bad in ["https://user:pass@rc.example.com", "https://rc.example.com/path",
                    "https://rc.example.com?token=abc", "https://rc.example.com#frag",
                    "ftp://rc.example.com", "", "   ", "https://"] {
            checks.throwsError("\(bad.isEmpty ? "<empty>" : bad) is refused") { _ = try GatewayEndpoint(bad) }
        }
    }

    private static func socketURLs(_ checks: CheckRunner) {
        checks.noThrow("an https origin upgrades to wss") {
            let endpoint = try GatewayEndpoint("https://rc.example.com")
            guard endpoint.socketURL(path: "/ws/app").absoluteString == "wss://rc.example.com/ws/app",
                  endpoint.socketURL(path: "/ws/stt").absoluteString == "wss://rc.example.com/ws/stt" else {
                throw TransportError.invalidEndpoint
            }
        }
        checks.noThrow("a development origin upgrades to ws") {
            let endpoint = try GatewayEndpoint("http://127.0.0.1:8787")
            guard endpoint.socketURL(path: "/ws/app").absoluteString == "ws://127.0.0.1:8787/ws/app",
                  endpoint.apiURL("/api/health").absoluteString == "http://127.0.0.1:8787/api/health" else {
                throw TransportError.invalidEndpoint
            }
        }
    }
}
