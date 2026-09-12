import SwiftUI
import Combine
import RCCore

/// Chooses the dictation backend from settings and what the gateway offers.
///
/// The scripted platform is only reachable behind an explicit launch argument,
/// so a shipping build can never substitute fake speech for the microphone.
@MainActor
public enum SpeechBackend {
    public static func make(settings: SettingsStore, connection: ConnectionStore,
                            arguments: [String] = ProcessInfo.processInfo.arguments)
        -> (platform: any SpeechInputPlatform, isScripted: Bool) {
        #if DEBUG
        if arguments.contains("--voice-preview") { return (ScriptedSpeechInput(), true) }
        #endif
        if settings.voiceBackend == .gateway, connection.stt.enabled,
           let client = connection.api as? GatewayHTTPClient {
            return (GatewaySpeechRecognizer(client: client, language: settings.voiceLanguage), false)
        }
        return (SystemSpeechRecognizer(localeIdentifier: settings.speechLocaleIdentifier), false)
    }
}

/// The microphone button in the composer's control row.
struct VoiceButton: View {
    let session: InlineVoiceDraftSession
    let start: () -> Void

    var body: some View {
        Button(action: start) {
            Image(systemName: "mic")
                .frame(width: Theme.Touch.minimum, height: Theme.Touch.minimum)
        }
        .buttonStyle(.plain)
        .foregroundStyle(Theme.ink)
        .disabled(session.voice.phase.isBusy)
        .accessibilityLabel("Dictate a message")
        .accessibilityIdentifier("composer.voice")
    }
}

/// What the control row holds while dictation runs: how loud it is, how long it
/// has been listening, and the one way out.
///
/// There is no "stop and send". Done keeps the transcript in the message field
/// and Send stays the separate, explicit tap it is for anything typed. There is
/// no Cancel either: a dictation nobody wants is Done and then edited or
/// cleared like any other draft, and Done stands where Send stands, at Send's
/// size, because while listening it is the one primary action in the row.
struct VoiceListeningControls: View {
    let session: InlineVoiceDraftSession
    let done: () -> Void
    @State private var started = Date()
    @State private var elapsed = "0:00"

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        HStack(spacing: Theme.Space.small) {
            VoiceLevelMeter(level: session.voice.inputLevel,
                            listening: session.voice.phase == .listening)
                .frame(width: 44, height: 20)
            Text(elapsed)
                .font(Theme.mono)
                .foregroundStyle(Theme.inkSecondary)
                .monospacedDigit()
                .accessibilityLabel("Listening for \(elapsed)")
                .accessibilityIdentifier("voice.elapsed")
            Spacer(minLength: Theme.Space.small)
            Button("Done", action: done)
                .buttonStyle(PrimaryButtonStyle(fullWidth: false))
                .disabled(session.voice.phase != .listening)
                .accessibilityIdentifier("voice.done")
        }
        .frame(minHeight: Theme.Touch.primary)
        .onReceive(tick) { now in
            let seconds = Int(now.timeIntervalSince(started))
            elapsed = String(format: "%d:%02d", seconds / 60, seconds % 60)
        }
        .onAppear { started = Date() }
    }
}

/// The quiet line above the message field while dictation runs, and the place a
/// failure says what went wrong.
struct VoiceStatusLine: View {
    let session: InlineVoiceDraftSession
    let usesGateway: Bool

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(session.voice.failure == nil ? Theme.inkSecondary : Theme.danger)
            .lineLimit(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("voice.status")
    }

    private var text: String {
        if let failure = session.voice.failure { return message(failure) }
        switch session.voice.phase {
        case .requestingPermission: return "Getting the microphone ready"
        case .finishing: return "Finishing the transcript"
        default:
            return usesGateway
                ? "Transcribing on your gateway · edit before sending"
                : "Transcribing live · edit before sending"
        }
    }

    /// A failure keeps whatever was recognised, so every message ends with what
    /// to do next rather than with the loss.
    private func message(_ failure: SpeechInputFailure) -> String {
        switch failure {
        case .speechPermission: "Allow speech recognition in Settings, or keep typing."
        case .microphonePermission: "Allow microphone access in Settings, or keep typing."
        case .unsupported: "No on-device model for this language. Switch to gateway transcription in Settings."
        case .unavailable: "Transcription is unavailable right now. Keep typing instead."
        case .recording: "The microphone is unavailable. Try again, or keep typing."
        case .recognition, .interrupted: "Dictation stopped. What was recognised is in your draft."
        }
    }
}

/// Five bars driven by the measured input level.
struct VoiceLevelMeter: View {
    let level: Double
    let listening: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<5) { index in
                let envelope = 0.45 + 0.55 * sin(Double(index) / 4 * .pi)
                Capsule()
                    .fill(Theme.ink)
                    .frame(width: 4, height: listening ? 3 + 15 * min(1, max(0, level)) * envelope : 3)
            }
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: level)
        .accessibilityHidden(true)
    }
}

extension View {
    /// Keeps the draft in step with the live transcript, draws the listening
    /// glow around the display, and resets dictation whenever the composer it
    /// belongs to changes.
    func inlineVoiceInput(session: InlineVoiceDraftSession, draft: Binding<String>,
                          target: VoiceDraftTarget) -> some View {
        modifier(InlineVoiceInputModifier(session: session, draft: draft, target: target))
    }
}

private struct InlineVoiceInputModifier: ViewModifier {
    let session: InlineVoiceDraftSession
    @Binding var draft: String
    let target: VoiceDraftTarget
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .overlay {
                VoiceGlowPresenter(active: session.voice.phase == .listening,
                                   level: session.voice.inputLevel,
                                   reduceMotion: reduceMotion)
            }
            .onChange(of: session.voice.transcript) { _, _ in synchronize() }
            .onChange(of: session.voice.phase) { _, _ in synchronize() }
            .onChange(of: target) { _, _ in session.reset() }
            .onAppear { session.voice.setSceneActive(scenePhase == .active) }
            .onChange(of: scenePhase) { _, phase in
                session.voice.setSceneActive(phase == .active, cancelAuthorization: phase == .background)
            }
            .onDisappear { session.reset() }
    }

    private func synchronize() {
        if let updated = session.updateDraft(currentDraft: draft, currentTarget: target) { draft = updated }
    }
}
