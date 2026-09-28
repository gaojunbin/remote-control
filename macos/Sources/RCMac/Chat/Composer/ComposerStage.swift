import Foundation
import RCCore

/// A preview's way into the composer's states that take a click, a file or a
/// microphone (`\.previewStage`, `composer.<state>`), and nil in the app. A
/// stage never opens the microphone: dictation is the scripted one, with a
/// level and partials of its own, and a stage about polish answers, waits or
/// fails as the picture needs.
@MainActor
struct ComposerStage {
    /// A panel of the composer drawn open.
    enum Opening {
        case upNext, modelCard, modelList, permissions, sendMenu
    }

    let name: String

    init?(_ stage: String?) {
        guard let stage, stage.hasPrefix("composer.") else { return nil }
        name = String(stage.dropFirst("composer.".count))
    }

    var opening: Opening? {
        switch name {
        case "upnext": .upNext
        case "model": .modelCard
        case "model-list": .modelList
        case "permissions": .permissions
        case "send-menu": .sendMenu
        default: nil
        }
    }

    /// The script, sooner than the mock's two seconds, so a picture taken a
    /// second in has words in it; and a microphone that fails where the
    /// picture is about a failure.
    var speech: SpeechServices {
        let scripted = ScriptedSpeech.services(partialEvery: .milliseconds(250), finishes: name != "finishing")
        guard name == "voice-error" else { return scripted }
        return SpeechServices(recorder: { handlers in RefusedRecorder(handlers: handlers) }, socket: scripted.socket)
    }

    /// A29: the polish stages turn polish on for the picture, whatever the
    /// account chose.
    var polishChoice: PolishChoice? {
        name.hasPrefix("polish") ? PolishChoice(model: "gpt-4.1-mini", strength: .moderate) : nil
    }

    /// Where the picture needs the model to wait or to fail rather than answer.
    var polish: (@MainActor (PolishRequest) async throws -> String)? {
        switch name {
        case "polishing":
            return { _ in
                try await Task.sleep(for: .seconds(3600))
                throw CancellationError()
            }
        case "polish-failed":
            return { _ in throw TransportError.requestTimedOut }
        default:
            return nil
        }
    }

    /// What the stage does to the composer once it is on screen.
    func run(on composer: ComposerModel) async {
        switch name {
        // Typed, as a person types: the field has the focus and the caret.
        case "typed": typed("Rename the flaky test and run the suite again", into: composer)
        case "long": typed(Self.longDraft, into: composer)
        case "attachments": attachFiles(to: composer)
        case "too-many":
            attachFiles(to: composer)
            composer.attach((1...8).map { .data(name: "photo-\($0).jpg", mime: "image/jpeg",
                                                Data(count: 1500 * $0)) })
        case "commands": typed("/", into: composer)
        case "commands-query": typed("/re", into: composer)
        case "command-hint": typed("/review ", into: composer)
        case "answer": typed("Split it by tenant", into: composer)
        case "editing":
            if let first = composer.chat.timeline.queue.first(where: composer.canEdit) { composer.edit(first) }
        case "listening": composer.startVoice()
        case "finishing", "polishing", "polished", "polish-failed":
            composer.startVoice()
            try? await Task.sleep(for: .milliseconds(700))
            composer.voice.done()
        case "voice-error": composer.startVoice()
        default: break
        }
    }

    private func typed(_ words: String, into composer: ComposerModel) {
        composer.userTyped(words)
        composer.requestFocus()
    }

    private func attachFiles(to composer: ComposerModel) {
        typed("Here are the screenshots", into: composer)
        composer.host.drafts.add([
            ComposerAttachment(name: "screenshot-1.png", mime: "image/png", data: Data(count: 24_000)),
            ComposerAttachment(name: "notes.txt", mime: "text/plain", data: Data("hello notes".utf8))
        ], to: composer.key)
    }

    private static var longDraft: String {
        (1...14).map { "Line \($0) of a long draft that keeps going" }.joined(separator: "\n")
    }
}

/// A microphone the system refused, for the picture of what that says.
@MainActor
private final class RefusedRecorder: VoiceRecorder {
    private let handlers: RecorderHandlers

    init(handlers: RecorderHandlers) { self.handlers = handlers }

    func start() async -> Bool {
        handlers.onError(.denied)
        return false
    }

    func stop() async {}
}
