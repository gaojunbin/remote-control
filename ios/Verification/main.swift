import Foundation
import RCCore

/// The core regression suite. It depends on RCCore only, so it builds and runs
/// with plain Command Line Tools: `cd ios && swift run RCVerify`.
let results = [
    ProtocolChecks.run(),
    await TimelineChecks.run(),
    TransportChecks.run(),
    await SocketChecks.run(),
    await STTChecks.run(),
    await PersistenceChecks.run(),
    MarkdownChecks.run(),
    await StoreChecks.run()
]

var total = 0
var failures: [String] = []
for result in results {
    total += result.passed
    failures.append(contentsOf: result.failures)
}

let summary = results.map { "\($0.passed) \($0.name)" }.joined(separator: ", ")
if failures.isEmpty {
    print("PASS: \(total) checks — \(summary)")
} else {
    print("FAIL: \(failures.count) of \(total + failures.count) checks — \(summary)")
    for failure in failures { print("  · \(failure)") }
    exit(1)
}
