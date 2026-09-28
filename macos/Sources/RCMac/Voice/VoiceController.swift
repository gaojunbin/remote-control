import Foundation
import Observation

/// `web/src/features/voice/useVoice.ts`: dictation into the composer's own field.
///
/// The mic button starts listening; listening ends only when the person clicks
/// Done, reaches for the field, or the microphone fails. There is no time
/// limit: one gateway socket carries at most 120 s of audio, so a long
/// dictation is cut into segments whose transcripts are joined in order, and
/// the replacement socket is taking audio before the outgoing one is told to
/// stop, so the seam drops nothing. Nothing is ever sent by the act of stopping.
@MainActor
@Observable
final class VoiceController {
    private(set) var state: VoiceState = .idle
    private(set) var level = 0.0
    private(set) var elapsedMs = 0.0
    private(set) var error: String?

    /// Whether the composer takes a dictation at all. A composer taken away
    /// mid-dictation — the device goes offline, the terminal takes the session
    /// back — ends the run here rather than streaming audio to the gateway for
    /// the life of the window; the words already recognised are in the field.
    var enabled = true {
        didSet { if !enabled, state != .idle, state != .error { cancel() } }
    }

    /// The transcript so far. `isFinal` marks the last call of a dictation,
    /// after which the text belongs to the field and this controller is idle.
    @ObservationIgnored var onTranscript: @MainActor (_ text: String, _ isFinal: Bool) -> Void = { _, _ in }

    @ObservationIgnored private let services: SpeechServices
    @ObservationIgnored private let timing: VoiceTiming
    @ObservationIgnored private var recorder: (any VoiceRecorder)?
    @ObservationIgnored private var sockets: [any SpeechStream] = []
    @ObservationIgnored private var segments = DictationSegments()
    /// The socket taking audio right now. The others are transcribing.
    @ObservationIgnored private var route: (any SpeechStream)?
    @ObservationIgnored private var levelNow = 0.0
    @ObservationIgnored private var startedAt = ContinuousClock.now
    @ObservationIgnored private var finishing = false
    @ObservationIgnored private var finalTimer: Task<Void, Never>?
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var loop: Task<Void, Never>?
    /// Bumped by every start and every end, so a stale loop or reply is ignored.
    @ObservationIgnored private var runID = 0

    init(services: SpeechServices, timing: VoiceTiming = .standard) {
        self.services = services
        self.timing = timing
    }

    func start() {
        guard enabled, state == .idle || state == .error else { return }
        teardown()
        segments = DictationSegments()
        error = nil
        level = 0
        elapsedMs = 0
        startedAt = .now
        move(.starting)
        let run = runID
        let recorder = services.recorder(RecorderHandlers(
            onFrame: { [weak self] frame in self?.route?.append(frame) },
            onLevel: { [weak self] value in
                self?.levelNow = value
                self?.level = value
            },
            onError: { [weak self] error in self?.fail(Self.text(for: error)) }))
        self.recorder = recorder
        Task { [weak self] in
            let started = await recorder.start()
            guard let self, run == self.runID, started else { return }
            guard await self.openSegment(run), run == self.runID else { return }
            self.startedAt = .now
            self.move(.listening)
            self.loop = Self.segmentLoop(of: self, run: run, timing: self.timing)
        }
    }

    /// Stop listening and keep the transcript. Sending stays a separate click.
    func done() {
        guard state == .listening else { return }
        finishing = true
        move(.finishing)
        level = 0
        let run = runID
        let recorder = self.recorder
        self.recorder = nil
        // The recorder hands on its tail as it stops, so the live socket is
        // told to transcribe only once that last audio has reached it.
        Task { [weak self] in
            await recorder?.stop()
            guard let self, run == self.runID else { return }
            let live = self.route
            self.route = nil
            if let live { live.stop() } else { self.publish(true) }
        }
        // A gateway that never answers must not leave the composer waiting:
        // keep what was recognised instead.
        let timeout = timing.finalTimeout
        finalTimer = Task { [weak self] in
            try? await Task.sleep(for: timeout)
            guard !Task.isCancelled, let self, run == self.runID else { return }
            self.publish(true)
        }
    }

    /// Drop the run without publishing: a keystroke takes the field back.
    func cancel() {
        reset()
        error = nil
        move(.idle)
    }

    func dismissError() {
        error = nil
        move(.idle)
    }

    /// The composer went away: the microphone and every socket go with it.
    func shutDown() {
        teardown()
        ticker?.cancel()
        ticker = nil
    }

    // MARK: - The run

    private func move(_ next: VoiceState) {
        state = next
        if next == .listening { startTicker() } else { ticker?.cancel(); ticker = nil }
    }

    /// Drop the microphone and every socket. Nothing is published from here.
    private func teardown() {
        runID += 1
        finishing = false
        finalTimer?.cancel()
        finalTimer = nil
        loop?.cancel()
        loop = nil
        if let recorder {
            self.recorder = nil
            Task { await recorder.stop() }
        }
        route = nil
        let closing = sockets
        sockets = []
        for socket in closing { socket.cancel() }
        levelNow = 0
    }

    private func reset() {
        teardown()
        segments = DictationSegments()
        level = 0
        elapsedMs = 0
    }

    /// A failed dictation keeps whatever was recognised: those words are
    /// already in the field, and losing them helps no one.
    private func fail(_ message: String) {
        onTranscript(segments.joined, true)
        reset()
        error = message
        move(.error)
    }

    private func publish(_ isFinal: Bool) {
        onTranscript(segments.joined, isFinal)
        if isFinal {
            reset()
            move(.idle)
        }
    }

    private func receive(_ event: SpeechEvent, index: Int, run: Int) {
        guard run == runID, segments.has(index) else { return }
        switch event {
        case .partial(let text):
            if segments.update(index, text: text) { settle() }
        case .final(let text):
            segments.update(index, text: text)
            segments.end(index)
            settle()
        case .failed(let message):
            segments.end(index)
            // A segment that already handed the microphone on keeps what it
            // transcribed; only the live one can end the dictation.
            if index == segments.active {
                fail(message.flatMap { $0.isEmpty ? nil : $0 } ?? S.voice.failed)
            } else {
                settle()
            }
        case .closed:
            // A socket closes after its final as a matter of course; only an
            // unannounced close still has a segment to settle.
            guard segments.isOpen(index) else { return }
            segments.end(index)
            settle()
        }
    }

    private func settle() { publish(finishing && segments.isSettled) }

    /// Connect a fresh socket, hand the audio over to it, and let the one it
    /// replaces transcribe what it already holds.
    private func openSegment(_ run: Int) async -> Bool {
        let index = segments.begin()
        let socket = services.socket { [weak self] event in self?.receive(event, index: index, run: run) }
        sockets.append(socket)
        do {
            try await socket.start()
        } catch {
            segments.end(index)
            // A dictation that ended while this socket was still connecting is
            // not a failure: the teardown is what refused the connect.
            guard run == runID else { return false }
            fail(S.voice.failed)
            return false
        }
        // Connecting is the one wait here, so the dictation can have ended
        // while it ran. A socket nobody is going to speak into is closed.
        guard run == runID, !finishing else {
            segments.end(index)
            socket.cancel()
            return false
        }
        let previous = route
        route = socket
        previous?.stop()
        return true
    }

    /// Roll over to a new socket for as long as the person keeps talking. The
    /// loop holds the controller only between its waits, so a controller
    /// nobody keeps any more ends it.
    private static func segmentLoop(of controller: VoiceController, run: Int, timing: VoiceTiming) -> Task<Void, Never> {
        Task { [weak controller] in
            while true {
                try? await Task.sleep(for: timing.segment)
                // Hold a cut until the speaker pauses, and take it anyway if
                // they do not.
                let forced = ContinuousClock.now + (timing.segmentLimit - timing.segment)
                while controller?.isCurrent(run) == true, (controller?.levelNow ?? 0) > timing.silenceLevel,
                      ContinuousClock.now < forced {
                    try? await Task.sleep(for: timing.poll)
                }
                guard let current = controller, current.isCurrent(run) else { return }
                guard await current.openSegment(run) else { return }
            }
        }
    }

    /// Whether this run is the one listening, and is not being finished.
    private func isCurrent(_ run: Int) -> Bool { run == runID && !finishing }

    /// The elapsed clock runs only while listening, and stops at Done.
    private func startTicker() {
        ticker?.cancel()
        let poll = timing.poll
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: poll)
                guard !Task.isCancelled, let self else { return }
                self.elapsedMs = (ContinuousClock.now - self.startedAt).milliseconds
            }
        }
    }

    private static func text(for error: RecorderError) -> String {
        switch error {
        case .denied: S.voice.denied
        case .unsupported: S.macComposer.voiceUnsupported
        case .failed: S.voice.failed
        }
    }
}

private extension Duration {
    /// The length in milliseconds, as the web's clocks count it.
    var milliseconds: Double {
        Double(components.seconds) * 1000 + Double(components.attoseconds) / 1e15
    }
}
