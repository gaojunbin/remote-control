import Foundation
import RCCore

extension ComposerModel {
    /// How long the one line about a failed polish stays before it goes.
    static let polishNoteDuration: Duration = .seconds(5)

    func startVoice() {
        dropPolish()
        dictation = (base: text, applied: text)
        voice.start()
    }

    /// Reaching for the field takes it back: dictation stops and the words it
    /// did recognise stay. The field's own programmatic focus — taking a
    /// command row, say — is not a reach and ends nothing.
    func takeFieldBack() {
        guard voiceBusy else { return }
        dictation = nil
        voice.cancel()
    }

    /// Dictation writes into this field and nothing else, and only while the
    /// field still holds what it last wrote: a keystroke in between is the
    /// person's, and theirs wins. Every transcript that changes the field
    /// leaves the end of the words in view, through the finishing spinner too.
    func receiveTranscript(_ transcript: String, isFinal: Bool) {
        guard var run = dictation, text == run.applied else { return }
        let next = DictationDraft.merge(run.base, transcript)
        if next != run.applied { requestTail() }
        run.applied = next
        dictation = isFinal ? nil : run
        setDraft(next)
        // A29: polishing starts on the last transcript of a dictation, once
        // the words are in the field and the field is the person's again.
        if isFinal { startPolish(PolishSpan(base: run.base, dictated: transcript)) }
    }

    /// A29: the words a dictation just left go to the gateway's model with the
    /// conversation the page already shows, and come back said cleanly. Only
    /// the dictated span is ever replaced, and only while the field holds it.
    func startPolish(_ span: PolishSpan) {
        guard let choice = host.polishChoice, DictationPolish.canPolish(span.dictated) else { return }
        polishRun += 1
        let run = polishRun
        polish = .polishing
        let request = DictationPolishing.request(span: span, model: choice.model, strength: choice.strength,
                                                 context: DictationPolish.context(chat.timeline))
        let host = host
        polishTask = Task { [weak self] in
            do {
                let answer = try await host.polish(request)
                guard let self, run == self.polishRun else { return }
                guard let next = DictationPolishing.applyPolished(current: self.text, span: span, polished: answer)
                else {
                    self.polish = .idle
                    return
                }
                self.setDraft(next)
                self.polish = .polished(span: span, text: answer.trimmingCharacters(in: .whitespacesAndNewlines))
            } catch {
                guard let self, run == self.polishRun else { return }
                self.polishFailed()
            }
        }
    }

    /// A send, an edit or a new dictation ends a polish run: an answer whose
    /// run is no longer current is dropped, because the words on screen are
    /// the person's, not a late model's.
    func dropPolish() {
        polishRun += 1
        polishTask?.cancel()
        polishTask = nil
        polishNoteTimer?.cancel()
        if polish != .idle { polish = .idle }
    }

    /// Put the dictated words back, and take the note away with them.
    func undoPolish() {
        guard case .polished(let span, let polished) = polish else { return }
        if let back = DictationPolishing.undoPolished(current: text, span: span, polished: polished) {
            setDraft(back)
        }
        dropPolish()
    }

    /// The one line about a failed polish says what happened and then goes
    /// away; the words it is about are in the field, where they always were.
    private func polishFailed() {
        polish = .failed
        polishNoteTimer?.cancel()
        polishNoteTimer = Task { [weak self] in
            try? await Task.sleep(for: Self.polishNoteDuration)
            guard !Task.isCancelled, let self, self.polish == .failed else { return }
            self.polish = .idle
        }
    }
}
