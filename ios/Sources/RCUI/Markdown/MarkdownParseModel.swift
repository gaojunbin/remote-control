import Foundation
import Combine
import RCCore

/// One parser per visible Markdown view. Continuous output refreshes at most
/// 10 times/second; all intervening deltas coalesce into the latest full source.
/// There is no trailing-only debounce and no task fan-out on every token.
@MainActor final class MarkdownParseModel: ObservableObject {
    /// Seed small history rows before SwiftUI measures them. Larger sources keep
    /// the asynchronous path; inspecting the size visits at most this many bytes
    /// plus one, including for a large bridged String.
    static let synchronousSeedByteLimit = 32 * 1024
    @Published private(set) var document = MarkdownDocument("")
    private var publishedSource: String?
    private var pendingSource: String?
    private var worker: Task<Void, Never>?
    private var parsing: Task<MarkdownDocument, Never>?
    private var generation = 0
    private var lastPublish: ContinuousClock.Instant?

    init(initialSource: String = "") {
        guard !Task.isCancelled,
              initialSource.utf8.prefix(Self.synchronousSeedByteLimit + 1).count <= Self.synchronousSeedByteLimit else { return }
        let seeded = MarkdownDocument(initialSource)
        // A parser interrupted by its caller may contain only a prefix. Do not
        // record that source as published, so onAppear can request a full parse.
        guard !Task.isCancelled else { return }
        document = seeded
        publishedSource = initialSource
    }

    func submit(_ source: String) {
        guard source != pendingSource, source != publishedSource || worker != nil else { return }
        pendingSource = source
        guard worker == nil else { return }
        let epoch = generation
        worker = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled, self.generation == epoch {
                if let previous = self.lastPublish {
                    let remaining = ContinuousClock.now.duration(to: previous.advanced(by: .milliseconds(100)))
                    if remaining > .zero {
                        do { try await Task.sleep(for: remaining) } catch { break }
                    }
                }
                guard !Task.isCancelled, self.generation == epoch, let source = self.pendingSource else { break }
                self.pendingSource = nil
                let parser = Task.detached(priority: .userInitiated) { MarkdownDocument(source) }
                self.parsing = parser
                let parsed = await parser.value
                guard !Task.isCancelled, self.generation == epoch else { break }
                self.parsing = nil
                self.document = parsed
                self.publishedSource = source
                self.lastPublish = .now
                if self.pendingSource == nil { break }
            }
            if self.generation == epoch { self.worker = nil; self.parsing = nil }
        }
    }
    func cancel() {
        generation += 1
        worker?.cancel(); parsing?.cancel()
        worker = nil; parsing = nil; pendingSource = nil
    }
}
