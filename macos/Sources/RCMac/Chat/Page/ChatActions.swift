import Foundation
import Observation
import RCCore

/// The requests `ChatPage.tsx` issues from the transcript, the header and the
/// resume notice, each reporting its failure in the page's banner in the web's
/// words (`errorText`, `refusalText`), and each clearing the banner as it
/// starts. What a reply carries of the session is handed to the conversation
/// at once, as the web upserts it.
@MainActor
@Observable
final class ChatActions {
    /// The banner's sentence for a request of the page's own.
    private(set) var error: String?
    private(set) var stopping = false
    @ObservationIgnored private let chat: ChatStore
    @ObservationIgnored private let channel: any GatewayChannel

    init(chat: ChatStore, channel: any GatewayChannel) {
        self.chat = chat
        self.channel = channel
    }

    /// What the banner above the composer says: the page's own failure, or a
    /// failure the conversation holds that the composer left for the page —
    /// a Remove, a setting, a takeover, an answer typed in the field.
    var banner: String? { error ?? ChatErrorWords.web(chat.errorMessage) }

    func dismiss() {
        error = nil
        chat.clearError()
    }

    /// A42: Stop on a terminal the device types into is an Escape, which the
    /// device refuses to type while the CLI has a prompt up; that `conflict`
    /// is the device's own sentence.
    func stop() async {
        stopping = true
        error = nil
        do {
            try await channel.request(.stop(sessionID: chat.sessionID))
        } catch {
            self.error = ErrorText.refusal(error, fallback: S.errors.stopFailed)
        }
        stopping = false
    }

    func approve(requestID: String, optionID: String) async {
        error = nil
        do {
            try await channel.request(.approve(sessionID: chat.sessionID, requestID: requestID, optionID: optionID))
        } catch {
            self.error = ErrorText.text(error, fallback: S.errors.approveFailed)
        }
    }

    /// The card's own Submit. A refusal travels back so the card keeps what
    /// was filled in (A20).
    func answer(requestID: String, answers: [String: QuestionAnswer]) async -> Bool {
        error = nil
        do {
            try await channel.request(.answer(sessionID: chat.sessionID, requestID: requestID, answers: answers))
            return true
        } catch {
            self.error = ErrorText.text(error, fallback: S.errors.answerFailed)
            return false
        }
    }

    /// "Open full output": the conversation fetches the block and swaps it in.
    func openFull(blockID: String) async {
        error = nil
        let before = chat.errorMessage
        await chat.loadFullBlock(blockID)
        guard let now = chat.errorMessage, now != before else { return }
        chat.clearError()
        error = S.errors.expandFailed
    }

    /// A35: Change reports its own failure inside the popover.
    func setResume(at milliseconds: Int64) async -> Bool {
        error = nil
        do {
            let date = Date(timeIntervalSince1970: Double(milliseconds) / 1000)
            let result = try await channel.request(.resumeSet(sessionID: chat.sessionID, at: date),
                                                   as: SessionResult.self)
            chat.receive(.sessionUpdated(result.session))
            return true
        } catch {
            return false
        }
    }

    /// A35: Cancel at once, with no confirmation.
    func cancelResume() async {
        error = nil
        do {
            let result = try await channel.request(.resumeCancel(sessionID: chat.sessionID), as: SessionResult.self)
            chat.receive(.sessionUpdated(result.session))
        } catch {
            self.error = ErrorText.text(error, fallback: S.errors.resumeCancelFailed)
        }
    }

    /// The status line's Take over.
    func takeover() async {
        error = nil
        do {
            let result = try await channel.request(.takeover(sessionID: chat.sessionID), as: SessionResult.self)
            chat.receive(.sessionUpdated(result.session))
        } catch {
            self.error = ErrorText.text(error, fallback: S.errors.takeoverFailed)
        }
    }

    /// The unconfirmed bar's Retry, under the send's own request id. A
    /// refusal is the page's to report, as the web's catch does.
    func retry(_ pending: PendingSend) async {
        error = nil
        let before = chat.errorMessage
        guard await chat.retry(pending) == .refused, let now = chat.errorMessage, now != before else { return }
        chat.clearError()
        error = ChatErrorWords.web(now) ?? S.composer.sendFailed
    }
}

/// The sentences RCCore writes for its own failures, in the web's words and
/// the interface language; a device's or a gateway's own sentence is shown as
/// it arrived.
enum ChatErrorWords {
    static func web(_ message: String?) -> String? {
        guard let message, !message.isEmpty else { return nil }
        switch message {
        case "That message has already been sent.": return S.composer.alreadySent
        case "The gateway did not answer in time.": return S.errors.timeout
        case "That device or session no longer exists.": return S.errors.notFound
        case "That device is offline": return S.errors.deviceOffline
        case "Not connected to the gateway.", "The gateway sent a response this app could not read.":
            return S.errors.generic
        default: return message
        }
    }
}
