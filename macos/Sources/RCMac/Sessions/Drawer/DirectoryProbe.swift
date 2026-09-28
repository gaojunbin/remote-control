import Foundation
import Observation
import RCCore

/// `useDirectoryProbe`: whether the working directory the reader typed exists
/// on the device, and its git status, asked 400 ms after the typing stops.
@MainActor
@Observable
final class DirectoryProbe {
    enum Status: Sendable, Equatable {
        case idle
        case checking
        case exists
        case missing
    }

    /// The answer, and the device and path it answers for.
    private var answer: (key: String, status: Status, git: GitStatus?) = ("", .idle, nil)

    private static let debounce: Duration = .milliseconds(400)

    static func key(deviceID: String?, path: String) -> String {
        "\(deviceID ?? "") \(path.trimmingCharacters(in: .whitespacesAndNewlines))"
    }

    /// Nothing to ask about is idle; an answer for an older path reads as checking.
    func status(deviceID: String?, path: String) -> (status: Status, git: GitStatus?) {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard deviceID != nil, !trimmed.isEmpty else { return (.idle, nil) }
        guard answer.key == Self.key(deviceID: deviceID, path: path) else { return (.checking, nil) }
        return (answer.status, answer.git)
    }

    /// One probe, run for every change of the device or the path; a newer one
    /// cancels it, which is what the pause before asking is for.
    func run(deviceID: String?, path: String, channel: (any GatewayChannel)?) async {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let deviceID, !trimmed.isEmpty, let channel else { return }
        let key = Self.key(deviceID: deviceID, path: path)
        try? await Task.sleep(for: Self.debounce)
        guard !Task.isCancelled else { return }
        do {
            _ = try await channel.request(.dirs(deviceID: deviceID, path: trimmed), as: DirectoryListing.self)
            let git = try? await channel.request(.git(deviceID: deviceID, path: trimmed), as: GitStatus.self)
            guard !Task.isCancelled else { return }
            answer = (key, .exists, git)
        } catch {
            guard !Task.isCancelled else { return }
            answer = (key, .missing, nil)
        }
    }
}
