import Foundation
import Observation
import RCCore

/// The composer of one conversation: `Composer.tsx`'s own state, and every
/// rule it reads, on top of the session's `ChatStore`.
///
/// The words, the queued edit (A43), the sends and the session's settings are
/// the `ChatStore`'s; the files are the drafts store's; what is here is the
/// composer's alone — the line of errors under its field, the slash panel's
/// highlight, the polish run and the dictation — and it ends with the view, so
/// opening another conversation starts a fresh one, as the web's `key` remount
/// does.
@MainActor
@Observable
final class ComposerModel {
    let chat: ChatStore
    @ObservationIgnored let host: any ComposerHost
    let voice: VoiceController

    /// The composer's own line: why a send, an attach or a command failed.
    var errors: [String] = []
    /// A27: the row the keyboard is on, and whether Esc put the panel away
    /// until the draft changes again.
    var highlight = 0
    var panelDismissed = false
    /// A29: the polish of the words just dictated.
    var polish: DictationPolishState = .idle
    /// Bumped to hand the field the focus with the caret after the words.
    private(set) var focusRequest = 0
    /// Bumped by a dictated write: the field shows its last line.
    private(set) var tailRequest = 0

    /// The draft dictation started from, and the value it last wrote, so a
    /// keystroke that landed in between is told apart from its own write.
    @ObservationIgnored var dictation: (base: String, applied: String)?
    @ObservationIgnored var polishRun = 0
    @ObservationIgnored var polishTask: Task<Void, Never>?
    @ObservationIgnored var polishNoteTimer: Task<Void, Never>?
    /// A43: a take-out is on its way, so a second tap starts nothing.
    @ObservationIgnored var takingOut = false

    init(chat: ChatStore, host: any ComposerHost, timing: VoiceTiming = .standard) {
        self.chat = chat
        self.host = host
        voice = VoiceController(services: host.speech, timing: timing)
        voice.onTranscript = { [weak self] text, isFinal in self?.receiveTranscript(text, isFinal: isFinal) }
    }

    /// The field asks for the focus, with the caret after the words.
    func requestFocus() { focusRequest += 1 }

    /// A dictated write asks to keep the newest line in view.
    func requestTail() { tailRequest += 1 }

    /// The view is going: nothing it started is left running.
    func shutDown() {
        voice.shutDown()
        polishTask?.cancel()
        polishNoteTimer?.cancel()
    }

    // MARK: - What the composer reads

    var key: String { chat.key }
    var session: Session { chat.session }
    var agent: AgentInfo? { host.agent(for: chat.session) }
    var gates: ComposerGates {
        ComposerGates(session: chat.session, agent: agent, deviceOnline: host.deviceOnline(chat.deviceID))
    }
    var text: String { chat.draft }
    var attachments: [ComposerAttachment] { host.drafts.attachments(key) }
    var editing: QueuedEdit? { chat.queuedEdit }
    /// A43: an edited message on its way back into the line. The field keeps
    /// its words until the gateway answers, and holds still while it does.
    var returning: Bool { chat.isReturningEdit }

    /// A20: while a question is pending the field is its free-text answer. A43:
    /// a queued message being edited keeps the field, and the question waits.
    var question: QuestionPayload? { gates.disabled ? nil : chat.pendingQuestion }
    var answering: Bool { question != nil }
    /// What Answer would submit, or nil when the draft has nowhere to go.
    var answer: [String: QuestionAnswer]? {
        guard let question else { return nil }
        return Answering.composeAnswer(question.questions, draft: chat.draft(for: question), text: text)
    }

    // MARK: A27 — the terminal's `/` menu

    /// The commands this session offers now: none for an agent without the
    /// capability, where `/` is an ordinary character.
    var commands: [Command] { agent?.supports(.commands) == true ? chat.commands : [] }
    /// The field is a command's only while it answers nothing and edits nothing.
    var commandable: Bool { !answering && !gates.disabled && editing == nil }
    var panelRows: [Command] {
        guard commandable, let query = SlashCommands.query(text) else { return [] }
        return SlashCommands.filter(commands, query: query)
    }
    var panelOpen: Bool { !panelRows.isEmpty && !panelDismissed }
    var highlightIndex: Int { min(highlight, max(0, panelRows.count - 1)) }
    var highlighted: Command? { panelRows.isEmpty ? nil : panelRows[highlightIndex] }
    var commandMatch: SlashCommands.Match? { commandable ? SlashCommands.match(commands, draft: text) : nil }

    // MARK: The primary slot

    var voiceBusy: Bool { voice.state.isBusy }
    /// What the one primary slot holds. Everything that draws or gates it —
    /// the row, the Enter key, the menu beside Send — reads this alone. A
    /// put-back still out is the app's move, like a polish still out (A43).
    var slot: PrimarySlot {
        let slot = PrimarySlot.of(voice: voice.state, polish: polish.progress)
        return returning && slot == .send ? .working : slot
    }
    /// A43: an edited message goes back into the line, so it steers nothing.
    var steers: Bool { gates.canSteer && editing == nil }
    var primaryLabel: String {
        if answering { return S.composer.answer }
        return gates.running && !steers ? S.composer.queue : S.composer.send
    }
    var primaryDisabled: Bool {
        if answering { return answer == nil }
        return gates.disabled || (text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && attachments.isEmpty)
    }
    /// The menu's only item is a send, so it goes with Send itself.
    var showsSendMenu: Bool { gates.running && gates.canInterrupt && !answering && slot == .send }
    var voiceEnabled: Bool { host.sttEnabled && !gates.disabled }

    /// The field says only who holds the session; taking it over belongs to
    /// the status line and the bar above the field, which have the button.
    var placeholder: String {
        let gates = gates
        if gates.terminalControlled { return S.composer.placeholderTerminal }
        if !host.deviceOnline(chat.deviceID) { return S.composer.placeholderOffline }
        if answering { return S.composer.placeholderAnswer }
        if gates.running { return steers ? S.composer.placeholderSteer : S.composer.placeholderQueued }
        return S.composer.placeholder
    }
}
