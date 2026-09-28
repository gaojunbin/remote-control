import Foundation
import RCCore

/// A29: the reader's own polish choices, where the gateway can polish at all:
/// the switch on and a model chosen.
struct PolishChoice: Equatable, Sendable {
    let model: String
    let strength: PolishStrength
}

/// What the composer reads from the app around it — the props `ChatPage`
/// hands `<Composer>` that are not the conversation's own. The app's is the
/// model; a test's is a stand-in.
@MainActor
protocol ComposerHost: AnyObject {
    func agent(for session: Session) -> AgentInfo?
    func deviceOnline(_ deviceID: String) -> Bool
    /// The gateway transcribes (`stt.enabled`); the mic is hidden when not.
    var sttEnabled: Bool { get }
    var polishChoice: PolishChoice? { get }
    var drafts: ComposerDrafts { get }
    var speech: SpeechServices { get }
    /// `POST /api/polish`: the words said cleanly.
    func polish(_ request: PolishRequest) async throws -> String
}

/// The composer's host in the app: the app model, read live, so a change to a
/// device, the gateway's config or a setting reaches the composer at once.
@MainActor
final class AppComposerHost: ComposerHost {
    private weak var model: MacAppModel?
    let drafts: ComposerDrafts
    let speech: SpeechServices
    private let polishOverride: (@MainActor (PolishRequest) async throws -> String)?
    private let polishChoiceOverride: PolishChoice?

    init(model: MacAppModel, stage: ComposerStage?) {
        self.model = model
        drafts = ComposerDrafts.of(model)
        polishOverride = stage?.polish
        polishChoiceOverride = stage?.polishChoice
        if let scripted = stage?.speech {
            speech = scripted
        } else if model.isDemo {
            // The offline demo has no gateway to transcribe for it, so it
            // speaks the mock gateway's own script and never opens the
            // microphone, as the web against its mock does not need to.
            speech = ScriptedSpeech.services()
        } else {
            speech = Self.gatewaySpeech(model: model)
        }
    }

    func agent(for session: Session) -> AgentInfo? { model?.agent(for: session) }

    func deviceOnline(_ deviceID: String) -> Bool { model?.device(deviceID)?.online ?? false }

    var sttEnabled: Bool { model?.connection.stt.enabled ?? false }

    var polishChoice: PolishChoice? {
        if let polishChoiceOverride { return polishChoiceOverride }
        guard let model, model.connection.polish.enabled, model.settings.polishEnabled,
              !model.settings.polishModel.isEmpty else { return nil }
        return PolishChoice(model: model.settings.polishModel, strength: model.settings.polishStrength)
    }

    func polish(_ request: PolishRequest) async throws -> String {
        if let polishOverride { return try await polishOverride(request) }
        guard let api = model?.connection.api else { throw TransportError.unauthorized }
        return try await api.polish(request).text
    }

    /// The Mac's microphone, and the gateway's `WS /ws/stt` through RCCore's
    /// socket. The client is read when a dictation starts, so a gateway signed
    /// into later is the one that transcribes.
    private static func gatewaySpeech(model: MacAppModel) -> SpeechServices {
        SpeechServices(
            recorder: { handlers in MicRecorder(handlers: handlers) },
            socket: { [weak model] onEvent in
                GatewaySpeechStream(client: model?.connection.api as? GatewayHTTPClient, onEvent: onEvent)
            })
    }
}
