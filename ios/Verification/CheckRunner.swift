import Foundation
import RCCore

/// A tiny assertion runner so the whole core suite runs with nothing but
/// Command Line Tools installed, no Xcode and no test host.
final class CheckRunner {
    private(set) var passed = 0
    private(set) var failures: [String] = []
    private let group: String

    init(group: String) { self.group = group }

    func expect(_ condition: Bool, _ label: @autoclosure () -> String) {
        if condition { passed += 1 } else { failures.append("\(group): \(label())") }
    }

    func equal<T: Equatable>(_ lhs: T, _ rhs: T, _ label: @autoclosure () -> String) {
        if lhs == rhs { passed += 1 } else { failures.append("\(group): \(label()) — got \(lhs), expected \(rhs)") }
    }

    func noThrow(_ label: @autoclosure () -> String, _ body: () throws -> Void) {
        do { try body(); passed += 1 } catch { failures.append("\(group): \(label()) threw \(error)") }
    }

    func throwsError(_ label: @autoclosure () -> String, _ body: () throws -> Void) {
        do { try body(); failures.append("\(group): \(label()) should have thrown") } catch { passed += 1 }
    }
}

struct CheckResult {
    let name: String
    let passed: Int
    let failures: [String]
}

extension CheckRunner {
    func result() -> CheckResult { CheckResult(name: group, passed: passed, failures: failures) }
}

/// The canonical fixtures live in `protocol/`, next to the schema every other
/// component validates against. This target reads them from the repository
/// rather than keeping a second copy that could drift.
enum FixtureSource {
    static let root = URL(filePath: #filePath)
        .deletingLastPathComponent()   // ios/Verification
        .deletingLastPathComponent()   // ios
        .deletingLastPathComponent()   // repository root
        .appending(path: "protocol", directoryHint: .isDirectory)

    static var fixtures: URL { root.appending(path: "fixtures", directoryHint: .isDirectory) }
    static var invalid: URL { root.appending(path: "fixtures_invalid", directoryHint: .isDirectory) }

    /// Every JSON file under a directory, recursively, in a stable order.
    static func files(in directory: URL) -> [URL] {
        guard let walker = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil) else {
            return []
        }
        return walker.compactMap { $0 as? URL }
            .filter { $0.pathExtension == "json" }
            .sorted { $0.path < $1.path }
    }

    /// The path relative to `protocol/fixtures`, used as a check label.
    static func label(_ url: URL) -> String {
        url.path.replacingOccurrences(of: fixtures.path + "/", with: "")
    }

    static func json(_ relativePath: String) -> JSONValue? {
        let url = fixtures.appending(path: relativePath)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(JSONValue.self, from: data)
    }
}
