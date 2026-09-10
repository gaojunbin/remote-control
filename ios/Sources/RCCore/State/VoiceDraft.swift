import Foundation

/// Identifies the composer a dictation belongs to.
///
/// Everything that could make a draft land in the wrong place is part of the
/// identity: a different gateway, account, device or session resets dictation
/// rather than pasting the transcript somewhere the user did not expect.
public struct VoiceDraftTarget: Equatable, Sendable, Hashable {
    public let account: String
    public let deviceID: String
    public let sessionID: String?

    public init(account: String, deviceID: String, sessionID: String?) {
        self.account = account
        self.deviceID = deviceID
        self.sessionID = sessionID
    }

    public func matches(_ other: VoiceDraftTarget) -> Bool { self == other }

    /// Append a transcript to the draft the user already had.
    ///
    /// This is a pure join. Replacing rather than accumulating the dictated
    /// segment is `InlineVoiceDraftSession`'s job: it keeps the original draft
    /// and re-derives from it on every partial.
    public func inserting(_ transcript: String, into draft: String, currentTarget: VoiceDraftTarget) -> String? {
        guard matches(currentTarget) else { return nil }
        let text = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        return draft.isEmpty ? text : draft + (draft.last?.isWhitespace == true ? "" : "\n") + text
    }
}

public enum VoiceInputPhase: String, Sendable {
    case idle, requestingPermission, listening, finishing, review, failed
    public var capturesAudio: Bool { self == .listening }
    public var isBusy: Bool { self == .requestingPermission || self == .listening || self == .finishing }
}
