package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Command
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.QueuedEdit
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.win.shared.Answering
import com.junbingao.remotecontrol.win.shared.SlashCommands
import com.junbingao.remotecontrol.win.strings.S
import com.junbingao.remotecontrol.win.voice.DictationPolishState
import com.junbingao.remotecontrol.win.voice.PrimarySlot
import com.junbingao.remotecontrol.win.voice.VoiceController
import com.junbingao.remotecontrol.win.voice.VoiceTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * The composer of one conversation: `Composer.tsx`'s own state, and every rule it reads, on top of
 * the session's `ChatStore`.
 *
 * The words, the queued edit (A43), the sends and the session's settings are the `ChatStore`'s; the
 * files are the drafts store's; what is here is the composer's alone — the line of errors under its
 * field, the slash panel's highlight, the polish run and the dictation — and it ends with the view,
 * so opening another conversation starts a fresh one, as the web's `key` remount does.
 *
 * What it starts runs in `host.tasks`; `reading` is where an attach reads its files, off the
 * composer's thread; `clock` is the dictation's elapsed time.
 */
class ComposerModel(
    val chat: ChatStore,
    val host: ComposerHost,
    timing: VoiceTiming = VoiceTiming.standard,
    internal val reading: CoroutineContext = Dispatchers.IO,
    clock: TimeSource = TimeSource.Monotonic,
) {
    val voice = VoiceController(services = host.speech, timing = timing, tasks = host.tasks, clock = clock)

    /** The composer's own line: why a send, an attach or a command failed. */
    var errors: List<String> by mutableStateOf(emptyList())

    /** A27: the row the keyboard is on, and whether Esc put the panel away until the draft changes again. */
    var highlight: Int by mutableIntStateOf(0)
    var panelDismissed: Boolean by mutableStateOf(false)

    /** A29: the polish of the words just dictated. */
    var polish: DictationPolishState by mutableStateOf(DictationPolishState.Idle)

    /** Bumped to hand the field the focus with the caret after the words. */
    var focusRequest: Int by mutableIntStateOf(0)
        private set

    /** Bumped by a dictated write: the field shows its last line. */
    var tailRequest: Int by mutableIntStateOf(0)
        private set

    /** The draft dictation started from, and the value it last wrote, so a keystroke that landed in between is told apart from its own write. */
    internal data class Dictation(val base: String, val applied: String)

    internal var dictation: Dictation? = null
    internal var polishRun = 0
    internal var polishTask: Job? = null
    internal var polishNoteTimer: Job? = null

    /** A43: a take-out is on its way, so a second tap starts nothing. */
    internal var takingOut = false

    init {
        voice.onTranscript = { text, isFinal -> receiveTranscript(text, isFinal) }
    }

    /** The field asks for the focus, with the caret after the words. */
    fun requestFocus() {
        focusRequest += 1
    }

    /** A dictated write asks to keep the newest line in view. */
    fun requestTail() {
        tailRequest += 1
    }

    /** The view is going: nothing it started is left running. */
    fun shutDown() {
        voice.shutDown()
        polishTask?.cancel()
        polishNoteTimer?.cancel()
    }

    // What the composer reads

    val key: String get() = chat.key
    val session: Session get() = chat.session
    val agent: AgentInfo? get() = host.agent(chat.session)
    val gates: ComposerGates get() = ComposerGates(session = chat.session, agent = agent, deviceOnline = host.deviceOnline(chat.deviceID))
    val text: String get() = chat.draft
    val attachments: List<ComposerAttachment> get() = host.drafts.attachments(key)
    val editing: QueuedEdit? get() = chat.queuedEdit

    /** A43: an edited message on its way back into the line. The field keeps its words until the gateway answers, and holds still while it does. */
    val returning: Boolean get() = chat.isReturningEdit

    /**
     * A20: while a question is pending the field is its free-text answer. A43: a queued message being
     * edited keeps the field, and the question waits.
     */
    val question: QuestionPayload? get() = if (gates.disabled) null else chat.pendingQuestion
    val answering: Boolean get() = question != null

    /** What Answer would submit, or null when the draft has nowhere to go. */
    val answer: Map<String, QuestionAnswer>?
        get() {
            val question = question ?: return null
            return Answering.composeAnswer(question.questions, draft = chat.draft(question), text = text)
        }

    // A27 — the terminal's `/` menu

    /** The commands this session offers now: none for an agent without the capability, where `/` is an ordinary character. */
    val commands: List<Command> get() = if (agent?.supports(AgentCapability.commands) == true) chat.commands else emptyList()

    /** The field is a command's only while it answers nothing and edits nothing. */
    val commandable: Boolean get() = !answering && !gates.disabled && editing == null

    val panelRows: List<Command>
        get() {
            if (!commandable) return emptyList()
            val query = SlashCommands.query(text) ?: return emptyList()
            return SlashCommands.filter(commands, query)
        }

    val panelOpen: Boolean get() = panelRows.isNotEmpty() && !panelDismissed
    val highlightIndex: Int get() = minOf(highlight, maxOf(0, panelRows.size - 1))
    val highlighted: Command? get() = panelRows.getOrNull(highlightIndex)
    val commandMatch: SlashCommands.Match? get() = if (commandable) SlashCommands.match(commands, draft = text) else null

    // The primary slot

    val voiceBusy: Boolean get() = voice.state.isBusy

    /**
     * What the one primary slot holds. Everything that draws or gates it — the row, the Enter key,
     * the menu beside Send — reads this alone. A put-back still out is the app's move, like a polish
     * still out (A43).
     */
    val slot: PrimarySlot
        get() {
            val slot = PrimarySlot.of(voice = voice.state, polish = polish.progress)
            return if (returning && slot == PrimarySlot.send) PrimarySlot.working else slot
        }

    /** A43: an edited message goes back into the line, so it steers nothing. */
    val steers: Boolean get() = gates.canSteer && editing == null

    val primaryLabel: String
        get() {
            if (answering) return S.composer.answer
            return if (gates.running && !steers) S.composer.queue else S.composer.send
        }

    val primaryDisabled: Boolean
        get() {
            if (answering) return answer == null
            return gates.disabled || (text.trimmed.isEmpty() && attachments.isEmpty())
        }

    /** The menu's only item is a send, so it goes with Send itself. */
    val showsSendMenu: Boolean get() = gates.running && gates.canInterrupt && !answering && slot == PrimarySlot.send
    val voiceEnabled: Boolean get() = host.sttEnabled && !gates.disabled

    /**
     * The field says only who holds the session; taking it over belongs to the status line and the
     * bar above the field, which have the button.
     */
    val placeholder: String
        get() {
            val gates = gates
            if (gates.terminalControlled) return S.composer.placeholderTerminal
            if (!host.deviceOnline(chat.deviceID)) return S.composer.placeholderOffline
            if (answering) return S.composer.placeholderAnswer
            if (gates.running) return if (steers) S.composer.placeholderSteer else S.composer.placeholderQueued
            return S.composer.placeholder
        }
}
