import Foundation

extension String {
    /// The string without its surrounding whitespace. Text that reaches the app
    /// from a field, a transcript or a partial recognition result is padded
    /// differently by whoever produced it, and the padding never means anything.
    public var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
