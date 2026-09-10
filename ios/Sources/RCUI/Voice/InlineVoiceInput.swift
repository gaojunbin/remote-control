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

/// The microphone button beside the composer.
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

/// Replaces the composer while dictation runs: a level meter, a timer, the live
/// transcript, and two explicit ways out. Dictation never submits by itself.
struct VoiceCapturePanel: View {
    let session: InlineVoiceDraftSession
    let usesGateway: Bool
    @Binding var draft: String
    let cancel: () -> Void
    let stopAndSend: () -> Void
    @State private var started = Date()
    @State private var elapsed = "0:00"

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            HStack(spacing: Theme.Space.small) {
                VoiceLevelMeter(level: session.voice.inputLevel,
                                listening: session.voice.phase == .listening)
                    .frame(width: 44, height: 20)
                Text(elapsed)
                    .font(Theme.mono)
                    .foregroundStyle(Theme.inkSecondary)
                    .monospacedDigit()
                TextField("", text: $draft, axis: .vertical)
                    .lineLimit(1...3)
                    .font(.body)
                    .foregroundStyle(Theme.ink)
                    .accessibilityIdentifier("voice.transcript")
            }
            HStack(spacing: Theme.Space.small) {
                Text(statusText)
                    .font(.caption)
                    .foregroundStyle(Theme.inkSecondary)
                    .accessibilityIdentifier("voice.status")
                Spacer(minLength: Theme.Space.small)
                Button("Cancel", action: cancel)
                    .buttonStyle(ChipButtonStyle())
                    .accessibilityIdentifier("voice.cancel")
                Button {
                    stopAndSend()
                } label: {
                    Label("Stop & send", systemImage: "stop.fill").font(.footnote)
                }
                .buttonStyle(PrimaryButtonStyle(fullWidth: false))
                .accessibilityIdentifier("voice.stop")
            }
        }
        .card()
        .onReceive(tick) { now in
            let seconds = Int(now.timeIntervalSince(started))
            elapsed = String(format: "%d:%02d", seconds / 60, seconds % 60)
        }
        .onAppear { started = Date() }
    }

    private var statusText: String {
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
    /// Keeps the draft in step with the live transcript, and resets dictation
    /// whenever the composer it belongs to changes.
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
                VoiceEdgeGlow(active: session.voice.phase == .listening,
                              level: session.voice.inputLevel, reduceMotion: reduceMotion)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
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
