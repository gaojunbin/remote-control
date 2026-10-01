package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.BlockResult
import com.junbingao.remotecontrol.core.protocol.Command
import com.junbingao.remotecontrol.core.protocol.CommandsResult
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.HistoryResult
import com.junbingao.remotecontrol.core.protocol.MetaPayload
import com.junbingao.remotecontrol.core.protocol.OutboundAttachment
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.QueuedMessage
import com.junbingao.remotecontrol.core.protocol.ResumeBounds
import com.junbingao.remotecontrol.core.protocol.SendAcceptance
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.protocol.SendResult
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.protocol.SessionResume
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.core.protocol.SubscribeResult
import com.junbingao.remotecontrol.core.protocol.TodoCounts
import com.junbingao.remotecontrol.core.protocol.TodoStatus
import com.junbingao.remotecontrol.core.protocol.TurnMarker
import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.PolishStrength
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * One open conversation: its transcript, its composer state, and the requests it has in flight.
 *
 * Created when a chat opens and torn down when it closes; the subscription and the frame handler
 * live exactly as long as this object's scope. The work it starts runs in [tasks], the scope the
 * owner passes in. What the composer may do — the rules read from the session and the agent — is
 * in `ChatStoreRules.kt`, beside this file.
 */
class ChatStore(
    session: Session,
    private val channel: GatewayChannel,
    private val tasks: CoroutineScope,
    private val onSessionChange: (Session) -> Unit = {},
) {
    var session: Session by mutableStateOf(session)
        private set

    /** The transcript, which is observable itself: a screen reading any of it is redrawn when it moves. */
    val timeline = Timeline()

    var isLoadingHistory: Boolean by mutableStateOf(false)
        private set

    /** True while a resubscribe is in flight, and while a detected gap is being repaired, so neither is issued twice. */
    var isStale: Boolean by mutableStateOf(false)
        private set
    private var isSubscribing = false

    /** The resubscribe a `hello` or a detected gap asks for. Held so closing the conversation cancels it. */
    private var resubscription: Job? = null

    /**
     * Set by [close]. A conversation that has said `session.unsubscribe` must not subscribe again:
     * nothing draws what the gateway would stream, and it streams until the next reconnect.
     */
    private var isClosed = false
    var errorMessage: String? by mutableStateOf(null)
        private set
    var pendingSends: List<PendingSend> by mutableStateOf(emptyList())
        private set

    /**
     * Amendment A27: the slash commands this session offers, as the device last listed them. Empty
     * for an agent without the capability, and empty until the first answer arrives.
     */
    var commands: List<Command> by mutableStateOf(emptyList())
        private set
    private var commandsReadAt: Instant? = null
    private var isLoadingCommands = false
    var expandedBlockIDs: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * Rows that arrived while the user was reading further up. Only rows the current detail level
     * draws are counted: a burst of tool calls is nothing at all to someone who has chosen not to see
     * them.
     */
    var updatesWhileAway: Int by mutableStateOf(0)
        private set

    /**
     * Where the detail level comes from. The transcript does not own the preference — the app does —
     * so a change in Settings reaches an open conversation and its unread count at once, with no copy
     * to fall out of step. The default matches the preference's own default.
     */
    var detailSource: () -> TimelineDetail = { TimelineDetail.simple }

    /** What the device did with the most recent accepted message. */
    var lastAcceptance: SendAcceptance? by mutableStateOf(null)
        private set

    /**
     * Whether the reader is at the foot of the transcript. The view layer sets it from the scroll
     * position and from nothing else, so "follows the newest content" and "is at the bottom" are the
     * same thing.
     */
    var isFollowingTail: Boolean by observedValue(true) { if (it) updatesWhileAway = 0 }
    var draft: String by observedValue("") { forgetPolishOnEdit() }

    /** Amendment A43: the queued message the field is editing, or null. While it is set the field is that message and nothing else. */
    var queuedEdit: QueuedEdit? by mutableStateOf(null)
        private set

    /**
     * Amendment A43: the edit's words are on their way back into the line. The field holds still and
     * the button's place holds a spinner until the device answers, so nothing is sent twice.
     */
    var isReturningEdit: Boolean by mutableStateOf(false)
        private set

    /** A `queue_remove` for an edit is out, so a second tap starts nothing. */
    private var isTakingOut = false

    /** Amendment A29: what dictation polish is doing to the draft right now. */
    var polishPhase: PolishPhase by mutableStateOf(PolishPhase.Idle)
        private set

    /**
     * Where a dictation is polished. Set by the view layer, which is what knows the gateway; null
     * where no polish model is configured or the user has not turned the feature on, and then nothing
     * here runs.
     */
    var polishService: (suspend (PolishRequest) -> String)? = null
    private var polishTask: Job? = null

    /**
     * Set by the view layer, which is what knows about the socket and the device inventory. A send
     * that cannot succeed is refused with a reason rather than failing after the draft has been
     * cleared.
     *
     * "Can reach", not "is connected": a socket that is reconnecting still gets there, because the
     * transport holds the request until the hello lands. Returning to the foreground would otherwise
     * show a dead Send button for as long as a TLS handshake and a subscribe take.
     */
    var canReachGateway: Boolean by mutableStateOf(true)
    var deviceOnline: Boolean by mutableStateOf(true)

    /**
     * What the device said this agent can do, from the same inventory. It decides whether takeover is
     * offered and whether a `shared` session can be interrupted from here (amendment A10).
     */
    var agent: AgentInfo? by mutableStateOf(null)

    /**
     * Bumped whenever the device's own copy of the session replaces this one: a `session.updated`
     * frame, a `meta` event carrying settings, or the reply to a request. An optimistic change is
     * rolled back only while this has not moved.
     */
    private var sessionGeneration = 0

    val sessionID: String get() = session.sessionID
    val deviceID: String get() = session.deviceID
    val key: String get() = session.id

    // Derived state

    /** How much of this transcript is drawn. */
    val detail: TimelineDetail get() = detailSource()

    private class CachedRows(val version: Int, val detail: TimelineDetail, val rows: List<TimelineEntry>)

    private var cachedRows: CachedRows? = null

    /** How many times [rows] has had to filter the transcript, which is what the tests read to prove a redraw does not. */
    internal var rowsBuilt = 0
        private set

    /**
     * The rows the transcript draws, at the level the reader has chosen.
     *
     * A screen reads this on every render pass and the filter runs over the whole transcript, so the
     * answer is kept until the transcript or the level moves. `roots(at)` reads only the entries and
     * the unconfirmed sends, and every mutation of either bumps the timeline's version.
     */
    val rows: List<TimelineEntry>
        get() {
            val detail = detail
            val version = timeline.version
            cachedRows?.let { if (it.version == version && it.detail == detail) return it.rows }
            val rows = timeline.roots(at = detail)
            cachedRows = CachedRows(version = version, detail = detail, rows = rows)
            rowsBuilt += 1
            return rows
        }

    /** The header's todo chip. A checklist is the agent's working note rather than something written to the reader, so Simple leaves it out. */
    val showsTodos: Boolean get() = detail == TimelineDetail.detailed && (session.todos?.total ?: 0) > 0

    /**
     * A turn is in progress, whoever started it. Amendment A7: a terminal session reports `running`
     * while its turn runs and `readonly` only when it is idle, so this is true for terminal-driven
     * work too.
     */
    val isRunning: Boolean get() = session.state.isWorking

    /**
     * Whether this app may type. Amendment A7: read-only follows `control`, never `state` — a
     * terminal session is locked whether it is running or idle, and unlocking it means taking over.
     * Amendment A10: an attached session is never read-only, because the device can inject into it.
     */
    val isReadOnly: Boolean get() = session.isControlledByTerminal

    /** Amendment A10: a live CLI owns the session and the device is attached. */
    val isAttached: Boolean get() = session.isAttached

    /**
     * Amendment A20: the question waiting on the reader, if one is. The composer's button reads Answer
     * while this is set, and the draft in the message field is the free-text answer to the first
     * question on it that has nothing chosen or typed for it yet.
     *
     * Amendment A43: not while a queued message is being edited. The field is that message and
     * nothing else, so the question waits for the field rather than taking it; the card itself stays
     * live.
     */
    val pendingQuestion: QuestionPayload?
        get() {
            if (!allowsAnswers || queuedEdit != null) return null
            val question = timeline.pendingRequest?.question ?: return null
            return if (question.status.isActionable) question else null
        }

    /** What the pending card is holding. The card writes its choices here and the composer reads them, so the two submit the same answers. */
    var questionDraft: QuestionDraft by mutableStateOf(QuestionDraft(requestID = ""))
        private set

    /** The card's state for one question block, empty when the block is not the one the draft belongs to. */
    fun draft(question: QuestionPayload): QuestionDraft =
        if (questionDraft.requestID == question.requestID) questionDraft else QuestionDraft(requestID = question.requestID)

    fun choose(optionID: String, of: QuestionItem, question: QuestionPayload) {
        questionDraft = draft(question).toggle(optionID, of = of)
    }

    fun write(text: String, itemID: String, question: QuestionPayload) {
        questionDraft = draft(question).setText(text, itemID)
    }

    /**
     * Amendment A10: what a `terminal` session would need before this app could control it, or null
     * when the agent cannot be attached at all. The words belong to the app; this only says which
     * case applies.
     */
    enum class AttachHint {
        /** Claude: the `claude` shim is not installed on the device. */
        installShim,

        /** Codex: the standalone build the shared daemon runs from is not installed. */
        startDaemon,

        /** Amendment A26: pi's extension is not installed on the device. */
        installExtension,

        /** Amendment A28: Grok Build's configuration on the device does not put its terminals in the leader, so there is nothing to join. */
        enableLeader,

        /** The device is prepared, but this CLI was started without it. */
        restartSession,
    }

    val canSend: Boolean
        get() {
            if (sendBlockReason != null || isReturningEdit || draft.trimmed.isEmpty()) return false
            // Amendment A27: a command runs between turns, never inside one. The panel says so in
            // its footer; Send simply does not act.
            return !(draftCommand != null && isRunning)
        }

    // Slash commands (A27)

    /**
     * Whether this session's agent takes commands at all. An agent without the capability draws no
     * panel: `/` is an ordinary character there, and nothing on the screen explains the difference.
     */
    val offersCommands: Boolean get() = agent?.supports(AgentCapability.commands) == true

    /**
     * The draft read as a command, or null when it is ordinary text. A session the terminal holds
     * takes nothing from here, and a question outranks everything: while one is open the field is the
     * answer field (A20). An edited queued message is a message and nothing else (A43).
     */
    val commandDraft: SlashDraft?
        get() {
            if (!offersCommands || isReadOnly || queuedEdit != null || pendingQuestion != null) return null
            return SlashDraft.parse(draft)
        }

    /** The rows the panel draws. Empty means no panel at all: the draft is not a command draft, the name is already finished, or nothing matches. */
    val commandRows: List<Command>
        get() {
            val draft = commandDraft ?: return emptyList()
            if (draft.isComplete) return emptyList()
            return SlashDraft.filter(commands, query = draft.name)
        }

    /** Those rows grouped, with headers only where there is more than one group. */
    val commandSections: List<CommandSection> get() = CommandSection.build(commandRows)

    /** The command the draft would run, matched on its whole first word. A word that names nothing is a message, which is how a terminal reads it too. */
    val draftCommand: Command?
        get() {
            val draft = commandDraft ?: return null
            return SlashDraft.match(commands, name = draft.name)
        }

    /** What a complete command draft is still missing, for the line under the panel. Null when the draft names no command. */
    val commandHint: Command?
        get() {
            val draft = commandDraft ?: return null
            if (!draft.isComplete) return null
            return SlashDraft.match(commands, name = draft.name)
        }

    /** A turn is running, so every row is dimmed and the footer says why. */
    val commandsWaitForTurn: Boolean get() = isRunning

    /**
     * Taking a row from the panel. The trailing space is what closes the panel and shows where the
     * argument goes; a command that takes none is left ready to run on the next tap of Send.
     */
    fun take(command: Command) {
        draft = if (command.takesArgument) command.slash + " " else command.slash
    }

    /**
     * Ask the device what this session offers now. Called when the conversation opens; a failure
     * keeps the last list rather than raising a banner, because nobody asked for this request.
     */
    suspend fun loadCommands() {
        if (!offersCommands || isLoadingCommands) return
        isLoadingCommands = true
        try {
            val result = attempt { channel.request(GatewayRequest.commands(sessionID = sessionID), CommandsResult.serializer()) }
                ?: return
            if (isCancelled()) return
            commands = result.commands
            commandsReadAt = Instant.now()
        } finally {
            isLoadingCommands = false
        }
    }

    /**
     * The refresh `/` asks for: only when the last answer is stale or was empty, so the panel is on
     * screen the moment it is wanted rather than a round trip later.
     */
    suspend fun refreshCommands(now: Instant = Instant.now()) {
        if (!offersCommands) return
        val read = commandsReadAt
        if (commands.isNotEmpty() && read != null &&
            java.time.Duration.between(read, now).toKotlinDuration() < commandsStaleAfter) return
        loadCommands()
    }

    /**
     * Run what the draft names. Amendment A27: the request id is the block id the device echoes the
     * command under, so the row is in the transcript before the request leaves, exactly as a message
     * is (A12). The result is `{}` — what the command did arrives as ordinary events.
     */
    suspend fun runCommand() {
        val command = draftCommand ?: return
        val parsed = commandDraft ?: return
        if (isRunning) return
        val typed = draft
        val id = GatewayRequest.newRequestID()
        cancelPolish()
        draft = ""
        isFollowingTail = true
        timeline.addOptimistic(OptimisticMessage(id = id, text = command.line(argument = parsed.argument)))
        try {
            channel.request(GatewayRequest.command(id = id, sessionID = sessionID, name = command.name, argument = parsed.argument))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error == TransportError.DeliveryUncertain || error == TransportError.RequestTimedOut) {
                // The device may well have run it, so nothing is retracted and nothing is sent
                // again: the row says so for itself after a minute.
                errorMessage = (error as TransportError).errorDescription
                return
            }
            // A reply means the request was read and refused, so the row goes and the words come
            // back to the field the user was typing in.
            timeline.removeOptimistic(id)
            errorMessage = describe(error)
            if (draft.isEmpty()) draft = typed
        }
    }

    val unconfirmedSend: PendingSend? get() = pendingSends.firstOrNull { it.isUnconfirmed }

    val elapsedSinceTurnStart: Duration?
        get() {
            val turn = session.turn ?: return null
            return maxOf(0L, System.currentTimeMillis() - turn.startedAt).milliseconds
        }

    // Dictation polish (A29)

    /**
     * Pass the words a dictation just produced through the gateway's polish model, with the
     * conversation they were spoken into.
     *
     * The words are already in the field: this replaces the dictated span and nothing else, and only
     * while the field still holds exactly what the recogniser left there.
     */
    fun polish(span: DictationSpan, model: String, strength: PolishStrength, language: String) {
        val polishService = polishService ?: return
        if (model.isEmpty() || !DictationPolish.canPolish(span.dictated)) return
        polishTask?.cancel()
        polishPhase = PolishPhase.Polishing
        val request = DictationPolish.request(span = span, model = model, strength = strength, language = language,
                                              context = DictationPolish.context(timeline))
        polishTask = tasks.launch {
            val text = try {
                polishService(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCancelled()) return@launch
                polishPhase = PolishPhase.Failed
                return@launch
            }
            if (isCancelled()) return@launch
            applyPolished(span = span, text = text)
        }
    }

    private fun applyPolished(span: DictationSpan, text: String) {
        val polished = text.trimmed
        val next = DictationPolish.applyPolished(current = draft, span = span, polished = polished)
        if (next == null) {
            // The field has moved on — typed, sent, or dictated over — and what is in it is the
            // person's. There is nothing to say about that.
            polishPhase = PolishPhase.Idle
            return
        }
        // The phase is set first: writing the draft is what tells the note it is looking at the
        // model's words rather than at an edit.
        polishPhase = PolishPhase.Polished(span, polished)
        draft = next
    }

    /** Put the dictated words back. The note goes with them. */
    fun undoPolish() {
        val phase = polishPhase as? PolishPhase.Polished ?: return
        val dictated = DictationPolish.undoPolished(current = draft, span = phase.span, polished = phase.text)
        polishPhase = PolishPhase.Idle
        if (dictated != null) draft = dictated
    }

    /** A send, or anything else that ends this dictation's claim on the field. A late answer is dropped rather than pasted over what was sent. */
    fun cancelPolish() {
        polishTask?.cancel()
        polishTask = null
        polishPhase = PolishPhase.Idle
    }

    /** The one line about a failure, once it has been read. */
    fun clearPolishNote() {
        if (polishPhase == PolishPhase.Failed) polishPhase = PolishPhase.Idle
    }

    /**
     * "Polished · Undo" stands until the next edit or send. An edit is any draft that is no longer
     * what the model wrote; the words arriving from the model are not one.
     *
     * While the request is out the only thing that can write the draft is the person:
     * [applyPolished] sets the polished phase before it writes, [send] cancels first, and the
     * dictation that started the request stops touching the field the moment it sees a draft it did
     * not put there. So an edit here is the person typing over the wait, and their words win: the
     * request is dropped and Send comes back at once.
     */
    private fun forgetPolishOnEdit() {
        when (val phase = polishPhase) {
            PolishPhase.Idle -> return
            PolishPhase.Polishing -> cancelPolish()
            is PolishPhase.Polished -> if (draft != phase.span.polishedDraft(phase.text)) polishPhase = PolishPhase.Idle
            PolishPhase.Failed -> polishPhase = PolishPhase.Idle
        }
    }

    fun isExpanded(blockID: String): Boolean = blockID in expandedBlockIDs

    fun toggleExpanded(blockID: String) {
        expandedBlockIDs = if (blockID in expandedBlockIDs) expandedBlockIDs - blockID else expandedBlockIDs + blockID
    }

    // Lifecycle

    /** Paint cached history, subscribe from the cursor, and page history when the gateway buffer could not cover it. */
    suspend fun open(cached: List<SessionEvent> = emptyList()) {
        if (cached.isNotEmpty() && timeline.entries.isEmpty()) {
            timeline.prependHistory(cached, hasMore = true)
            // History does not advance the cursor, so adopt the newest cached seq explicitly. Without
            // it every warm open would resync and blank the transcript for a round trip.
            cached.maxOfOrNull { it.seq }?.let { timeline.adoptCursor(it) }
        }
        subscribe()
        // Amendment A27: fetched when the conversation opens, so the panel is there for the first `/`
        // rather than a round trip after it.
        loadCommands()
    }

    suspend fun close() {
        isClosed = true
        resubscription?.cancel()
        resubscription = null
        cancelPolish()
        attempt { channel.request(GatewayRequest.unsubscribe(sessionID = sessionID)) }
    }

    /** Section 7's reconnect order: subscribe from the cursor, and page history only when the gateway says the buffer could not cover it. */
    suspend fun subscribe() {
        if (isClosed || isSubscribing) return
        isSubscribing = true
        try {
            val since = if (timeline.lastSeq > 0) timeline.lastSeq else null
            val result = channel.request(GatewayRequest.subscribe(sessionID = sessionID, sinceSeq = since),
                                         SubscribeResult.serializer())
            if (isCancelled()) return
            update(session = result.session)
            if (result.resync || since == null) {
                timeline.reset()
                loadHistory()
            }
            for (event in result.events) ingest(event)
            // Amendment A6: the reply may carry the queue snapshot directly.
            result.queue?.let { timeline.applySubscribedQueue(it.pending) }
            timeline.clearGap()
            mirrorSnapshots()
            isStale = false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = describe(error)
        } finally {
            isSubscribing = false
        }
    }

    /** Keep the session summary in step with snapshots that arrived through a subscribe reply or a history page rather than a live frame. */
    private fun mirrorSnapshots() {
        val todos = if (timeline.todos.isNotEmpty() || session.todos != null) {
            TodoCounts(total = timeline.todos.size, done = timeline.todos.count { it.status == TodoStatus.completed })
        } else {
            session.todos
        }
        session = session.copy(todos = todos, queued = timeline.queue.size)
        onSessionChange(session)
    }

    /**
     * Live frames arrive through the connection store's fan-out.
     *
     * A `hello` means the socket came back and the gateway has forgotten this connection's
     * subscriptions, so the transcript resubscribes from its own cursor. Without this the screen keeps
     * updating from `session.updated` while the transcript silently stops receiving events.
     */
    fun receive(frame: AppFrame) {
        when (frame) {
            is AppFrame.Hello -> resubscribe()
            is AppFrame.SessionEvent -> if (frame.sessionID == sessionID) {
                ingest(frame.event)
                if (timeline.hasGap) repairGap()
            }
            is AppFrame.SessionUpdated -> if (frame.session.id == key) update(session = frame.session)
            else -> Unit
        }
    }

    /** An event never arrived. Refill from the gateway rather than render a transcript that is quietly missing a step. */
    private fun repairGap() {
        if (isStale) return
        isStale = true
        resubscribe()
    }

    /**
     * Subscribe again, out of band. The work is held so [close] can cancel it: a `hello` or a gap
     * noticed at the moment a conversation is closed would otherwise resubscribe it behind the screen
     * that has gone.
     */
    private fun resubscribe() {
        if (isClosed) return
        resubscription?.cancel()
        resubscription = tasks.launch { subscribe() }
    }

    private fun ingest(event: SessionEvent) {
        val before = timeline.entries.size
        if (!timeline.apply(event)) return
        when (val body = event.body) {
            is SessionEventBody.Status -> {
                session = session.copy(state = body.payload.state, stateDetail = body.payload.detail)
                onSessionChange(session)
            }
            is SessionEventBody.Meta -> applyMeta(body.payload)
            is SessionEventBody.TurnCompleted -> {
                session = session.copy(turn = null, usage = body.payload.usage ?: session.usage)
                onSessionChange(session)
            }
            is SessionEventBody.TurnStarted -> {
                session = session.copy(turn = TurnMarker(turnID = body.payload.turnID, startedAt = event.ts))
                onSessionChange(session)
            }
            is SessionEventBody.Todos -> {
                session = session.copy(todos = body.payload.counts)
                onSessionChange(session)
            }
            is SessionEventBody.Queue -> {
                session = session.copy(queued = body.payload.pending.size)
                onSessionChange(session)
            }
            else -> Unit
        }
        if (timeline.entries.size <= before || isFollowingTail) return
        if (timeline.entry(event)?.isDrawn(at = detail) != true) return
        updatesWhileAway += 1
    }

    private fun applyMeta(payload: MetaPayload) {
        val speed = payload.speed
        session = session.copy(
            title = payload.title ?: session.title,
            model = payload.model ?: session.model,
            permissionMode = payload.permissionMode ?: session.permissionMode,
            effort = payload.effort ?: session.effort,
            speed = if (speed != null) speed.id else session.speed,
            cwd = payload.cwd ?: session.cwd,
            git = payload.git ?: session.git,
            control = payload.control ?: session.control,
        )
        // What the device read off the agent itself replaces an optimistic value, so a refusal
        // landing afterwards must not put the older one back over it.
        if (payload.model != null || payload.permissionMode != null || payload.effort != null || speed != null) {
            sessionGeneration += 1
        }
        onSessionChange(session)
    }

    private fun update(session: Session) {
        this.session = session
        sessionGeneration += 1
        onSessionChange(session)
    }

    // History

    suspend fun loadHistory() {
        if (isLoadingHistory || !timeline.hasMoreHistory) return
        isLoadingHistory = true
        try {
            val result = channel.request(GatewayRequest.history(sessionID = sessionID, beforeSeq = timeline.oldestSeq),
                                         HistoryResult.serializer())
            if (isCancelled()) return
            timeline.prependHistory(result.events, hasMore = result.hasMore)
            mirrorSnapshots()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = describe(error)
        } finally {
            isLoadingHistory = false
        }
    }

    /** Fetch the untruncated version of one block for "Open full output". */
    suspend fun loadFullBlock(blockID: String) {
        try {
            val result = channel.request(GatewayRequest.block(sessionID = sessionID, blockID = blockID), BlockResult.serializer())
            if (isCancelled()) return
            timeline.replaceBlock(with = result.event)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = describe(error)
        }
    }

    // Requests

    /**
     * What became of a send, so the composer can put back what a refusal took (`docs/DESIGN.md` § "The
     * composer" → **A draft belongs to its session**): the words come back here, the attachments are
     * the view's.
     */
    enum class SendOutcome {
        /** Nothing was sent: the field held no words. */
        empty,

        /** The device took it (now, queued or steered). */
        accepted,

        /** The request may have landed; the row stays and offers Retry. */
        uncertain,

        /** The request was read and refused; nothing is on its way. */
        refused,
    }

    /** Send the draft. On an uncertain delivery the message stays visible with a Retry action that reuses this same request id. */
    suspend fun send(mode: SendMode = SendMode.auto, attachments: List<OutboundAttachment> = emptyList()): SendOutcome {
        val text = draft.trimmed
        if (text.isEmpty()) return SendOutcome.empty
        // Amendment A29: what goes is what is in the field — the words as dictated while a polish is
        // still out — and that answer is dropped.
        cancelPolish()
        val edit = queuedEdit
        if (edit == null) {
            draft = ""
            return deliver(id = GatewayRequest.newRequestID(), text = text, attachments = attachments, mode = mode)
        }
        // Amendment A43: the edited words go back into the line where they were — under the entry's
        // own `ts`, and queued even behind a steering agent, whose Send would otherwise steer.
        // Interrupt & send is the one way out of the line, and has no place in it to keep.
        val requeues = mode != SendMode.interrupt
        return putBack(edit) {
            deliver(id = GatewayRequest.newRequestID(), text = text, attachments = attachments,
                    mode = if (requeues) SendMode.queue else SendMode.interrupt, queueTs = if (requeues) edit.ts else null)
        }
    }

    suspend fun retry(pending: PendingSend): SendOutcome =
        deliver(id = pending.id, text = pending.text, attachments = pending.attachments, mode = pending.mode,
                queueTs = pending.queueTs)

    /**
     * Amendment A12: the request id is the block id the device will echo, so the message is in the
     * transcript before the request has left, and the device's own event replaces it in place.
     * Nothing here waits for a round trip that the user can feel.
     */
    private suspend fun deliver(id: String, text: String, attachments: List<OutboundAttachment>, mode: SendMode,
                                queueTs: Long? = null): SendOutcome {
        // Sending is a request to watch what happens next, so the transcript returns to the tail
        // before the message lands.
        isFollowingTail = true
        val record = PendingSend(id = id, text = text, attachments = attachments, mode = mode, queueTs = queueTs,
                                 status = PendingSend.Status.Sending)
        val index = pendingSends.indexOfFirst { it.id == id }
        pendingSends = if (index >= 0) pendingSends.toMutableList().also { it[index] = record } else pendingSends + record
        timeline.addOptimistic(OptimisticMessage(id = id, text = text, attachments = attachments.map { it.info }))
        return try {
            val request = GatewayRequest.send(id = id, sessionID = sessionID, text = text, attachments = attachments,
                                              mode = mode, queueTs = queueTs)
            val result = channel.request(request, SendResult.serializer())
            // A queued message is represented by the queue row above the composer until the device
            // dequeues it and emits the `user_message` under this same id; two rows would be one too
            // many.
            if (result.accepted == SendAcceptance.queued) timeline.removeOptimistic(id)
            // Amendment A14: a steered message is read by the agent at its next step, so the
            // device's block for it can be a whole turn away. The row holds the foot of the
            // transcript until then and never asks to be sent again: the device already has it.
            if (result.accepted == SendAcceptance.steered) timeline.markSteered(id)
            mark(id = id, status = PendingSend.Status.Accepted(result.accepted))
            SendOutcome.accepted
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            when {
                // The message may well have landed, so the row stays and Retry reuses this id rather
                // than sending the agent a second copy.
                error == TransportError.DeliveryUncertain || error == TransportError.RequestTimedOut -> {
                    mark(id = id, status = PendingSend.Status.Uncertain)
                    SendOutcome.uncertain
                }
                // A reply means the request was read and refused: it will never arrive, so the row
                // goes and the words come back to the draft.
                error is GatewayErrorBody -> {
                    reject(id = id, text = text, reason = error.message)
                    SendOutcome.refused
                }
                else -> {
                    reject(id = id, text = text, reason = describe(error))
                    SendOutcome.refused
                }
            }
        }
    }

    /**
     * A send that was definitely refused. The message leaves the transcript so nothing claims it is on
     * its way, and the text returns to the field the user was typing in — unless they have already
     * started typing the next one, which is theirs and not ours to overwrite.
     */
    private fun reject(id: String, text: String, reason: String) {
        timeline.removeOptimistic(id)
        // Nothing is left to retry or to hold bytes for, so the record goes too.
        pendingSends = pendingSends.filter { it.id != id }
        errorMessage = reason
        if (draft.isEmpty()) draft = text
    }

    /** An accepted message is owned by the transcript and the queue snapshot; only an in-flight, uncertain or failed one stays in [pendingSends]. */
    private fun mark(id: String, status: PendingSend.Status) {
        val index = pendingSends.indexOfFirst { it.id == id }
        if (index < 0) return
        if (status is PendingSend.Status.Accepted) {
            lastAcceptance = status.acceptance
            pendingSends = pendingSends.filterIndexed { at, _ -> at != index }
        } else {
            pendingSends = pendingSends.toMutableList().also { it[index] = it[index].copy(status = status) }
        }
    }

    /** Giving up on an unconfirmed send. The row goes with it: the user has been told it is not confirmed and has chosen not to send it again. */
    fun dismiss(pending: PendingSend) {
        pendingSends = pendingSends.filter { it.id != pending.id }
        timeline.removeOptimistic(pending.id)
    }

    suspend fun stop() {
        perform { channel.request(GatewayRequest.stop(sessionID = sessionID)) }
    }

    suspend fun approve(requestID: String, optionID: String, message: String? = null) {
        perform {
            channel.request(GatewayRequest.approve(sessionID = sessionID, requestID = requestID, optionID = optionID,
                                                   message = message))
        }
    }

    suspend fun answer(requestID: String, answers: Map<String, QuestionAnswer>) {
        perform { channel.request(GatewayRequest.answer(sessionID = sessionID, requestID = requestID, answers = answers)) }
    }

    /** The card's own Submit: everything chosen and typed on the card. */
    suspend fun submitAnswer(question: QuestionPayload) {
        val answers = draft(question).answers(question.questions)
        answer(requestID = question.requestID, answers = answers)
        questionDraft = QuestionDraft(requestID = question.requestID)
    }

    /**
     * Amendment A20: the composer's Answer. The message field is the free-text answer to the first
     * question on the card still waiting for one, and goes with whatever was chosen for the others.
     *
     * Nothing optimistic is drawn and nothing is queued: an answer is not a message, and the card
     * resolving is what says it arrived. A draft with nowhere to go — every question answered already,
     * or the one waiting takes options and no words — is left in the field untouched.
     */
    suspend fun answerDraft() {
        val question = pendingQuestion ?: return
        val answers = draft(question).answers(question.questions, composing = draft) ?: return
        val text = draft
        cancelPolish()
        draft = ""
        try {
            channel.request(GatewayRequest.answer(sessionID = sessionID, requestID = question.requestID, answers = answers))
            questionDraft = QuestionDraft(requestID = question.requestID)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = describe(error)
            if (draft.isEmpty()) draft = text
        }
    }

    /**
     * Amendment A40: the settings a `session.set` is waiting on, on a session this app does not drive
     * alone. Empty everywhere else, and empty again the moment the device answers. The card disables
     * itself while it holds anything, so the same setting is never in flight twice.
     */
    var pendingSettings: Set<SharedSetting> by mutableStateOf(emptySet())
        private set

    /** Whether the model card is waiting for a terminal to take a change. */
    val isSettingPending: Boolean get() = pendingSettings.isNotEmpty()

    /**
     * `docs/DESIGN.md` § "The model card": a change made from the card is drawn the moment it is made,
     * the device's reply confirms it, and a refusal puts the previous value back with the error —
     * unless a newer session replaced the optimistic one while the request was in flight, in which
     * case the older value must not be written over it.
     *
     * Amendment A40: not on a `shared` session. There the change is typed into somebody else's
     * terminal and the device answers only once the transcript confirms it, so nothing is drawn ahead
     * of that — the picker waits instead, the card follows the reply's `Session`, and a `conflict`
     * leaves the value exactly where it was. The title is never typed and stays the app's on every
     * session.
     */
    suspend fun set(model: String? = null, permissionMode: String? = null, effort: String? = null,
                    speed: SpeedChange? = null, title: String? = null) {
        val previous = session
        val waiting = if (isAttached) asked(model = model, permissionMode = permissionMode, effort = effort, speed = speed)
                      else emptySet()
        if (waiting.isEmpty()) {
            applyLocally(model = model, permissionMode = permissionMode, effort = effort, speed = speed, title = title)
        } else {
            pendingSettings = pendingSettings + waiting
            applyLocally(model = null, permissionMode = null, effort = null, speed = null, title = title)
        }
        val generation = sessionGeneration
        try {
            val result = channel.request(
                GatewayRequest.set(sessionID = sessionID, model = model, permissionMode = permissionMode, effort = effort,
                                   speed = speed, title = title),
                SessionResult.serializer())
            update(session = result.session)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = describe(error)
            if (waiting.isEmpty() && sessionGeneration == generation) update(session = previous)
        } finally {
            pendingSettings = pendingSettings - waiting
        }
    }

    /** Which of the four settings one call carries, so the pending state names the controls that are waiting rather than the whole card. */
    private fun asked(model: String?, permissionMode: String?, effort: String?, speed: SpeedChange?): Set<SharedSetting> {
        val asked = mutableSetOf<SharedSetting>()
        if (model != null) asked.add(SharedSetting.model)
        if (permissionMode != null) asked.add(SharedSetting.permissionMode)
        if (effort != null) asked.add(SharedSetting.effort)
        if (speed != null) asked.add(SharedSetting.speed)
        return asked
    }

    /**
     * The card's own copy of the change, drawn before the round trip. Only the fields the request
     * carries are touched, so one chip's change never rewrites what another one says.
     */
    private fun applyLocally(model: String?, permissionMode: String?, effort: String?, speed: SpeedChange?, title: String?) {
        session = session.copy(
            model = model ?: session.model,
            permissionMode = permissionMode ?: session.permissionMode,
            effort = effort ?: session.effort,
            speed = if (speed != null) speed.id else session.speed,
            title = title ?: session.title,
        )
        onSessionChange(session)
    }

    // Resuming after a usage limit (A35)

    /** The resume this session has pending, or null. The notice above the transcript is drawn from it and goes when it does. */
    val resume: SessionResume? get() = session.resume

    /**
     * Move the resume, or ask for one on a session that has none. The bounds are the device's
     * (protocol 6.3) and are checked here too, so a time it would refuse never leaves the picker.
     */
    suspend fun setResume(at: Instant, now: Instant = Instant.now()) {
        if (!ResumeBounds.allows(at, now = now)) {
            errorMessage = L10n.string("Pick a time between a minute from now and eight days away.")
            return
        }
        requestSession(GatewayRequest.resumeSet(sessionID = sessionID, at = at))
    }

    /** Take the resume away, at once and with no confirmation: nothing is lost but a timer, and Change on the timeline row is how it comes back. */
    suspend fun cancelResume() {
        requestSession(GatewayRequest.resumeCancel(sessionID = sessionID))
    }

    suspend fun takeover() {
        requestSession(GatewayRequest.takeover(sessionID = sessionID))
    }

    /** A request answered with the session, which replaces this one; a refusal is said once. */
    private suspend fun requestSession(request: GatewayRequest) {
        try {
            update(session = channel.request(request, SessionResult.serializer()).session)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = describe(error)
        }
    }

    /**
     * Remove takes a message out of the line for good, and out of the list before this returns: a
     * swipe's destructive action has already taken the row off the screen, and a list that went on
     * counting it until the reply would contradict its own animation. The device's snapshot follows.
     * Amendment A43: `not_found` means the device took the message first, which is said the way an
     * edit says it rather than in the device's own words. Any other failure means the device still
     * holds the message, and nothing would bring the row back until the queue next changed, so it goes
     * back where it stood.
     */
    fun removeQueued(queuedID: String): Job {
        val removal = timeline.dropQueued(queuedID)
        countQueue()
        timeline.removeOptimistic(queuedID)
        pendingSends = pendingSends.filter { it.id != queuedID }
        return tasks.launch {
            try {
                channel.request(GatewayRequest.queueRemove(sessionID = sessionID, queuedID = queuedID))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is GatewayErrorBody && error.code == GatewayErrorCode.notFound) {
                    errorMessage = L10n.string("That message has already been sent.")
                    return@launch
                }
                if (removal != null) {
                    timeline.restoreQueued(removal)
                    countQueue()
                }
                errorMessage = describe(error)
            }
        }
    }

    /** The chip counts what the list holds. */
    private fun countQueue() {
        session = session.copy(queued = timeline.queue.size)
        onSessionChange(session)
    }

    // Editing a queued message (A43)

    /**
     * Whether a row of the queue can be taken back into the field. Not one that carries files — they
     * are on the device, and no frame brings them back — and not while the composer cannot send, or
     * while another edit is open: the field holds one message at a time.
     */
    fun canEdit(entry: QueuedMessage): Boolean = !entry.carriesFiles && queuedEdit == null && sendBlockReason == null

    /**
     * Take a queued message out of the line and into the field. The device removes it first, so it
     * cannot deliver words that are still changing; only then is what the field held set aside and the
     * entry's words put in its place. `not_found` means the device took the message before the tap
     * arrived: nothing opens, and one line says so.
     */
    suspend fun beginEdit(entry: QueuedMessage) {
        if (!canEdit(entry) || isTakingOut) return
        isTakingOut = true
        try {
            channel.request(GatewayRequest.queueRemove(sessionID = sessionID, queuedID = entry.id))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = if (error is GatewayErrorBody && error.code == GatewayErrorCode.notFound) {
                L10n.string("That message has already been sent.")
            } else {
                describe(error)
            }
            return
        } finally {
            isTakingOut = false
        }
        cancelPolish()
        queuedEdit = QueuedEdit(entry = entry, aside = draft)
        draft = entry.text
    }

    /**
     * Cancel puts the original words back into the line the same way Send puts the edited ones, and
     * what the field held before comes back in place of the edit. A refusal changes nothing: still
     * editing, words and all.
     */
    suspend fun cancelEdit() {
        val edit = queuedEdit ?: return
        if (!canCancelEdit) return
        putBack(edit) {
            deliver(id = GatewayRequest.newRequestID(), text = edit.original, attachments = emptyList(),
                    mode = SendMode.queue, queueTs = edit.ts)
        }
    }

    /** Cancel is a send, so it waits for whatever holds Send back, and for the words already on their way. */
    val canCancelEdit: Boolean get() = queuedEdit != null && !isReturningEdit && sendBlockReason == null

    /**
     * The name of the one primary while a queued message is being edited, or null when none is. The
     * words go back into the line, so it reads Queue while a turn runs — even for a steering agent —
     * and Send while none does, because an idle session takes the message at once.
     */
    val editingSendLabel: String?
        get() {
            if (queuedEdit == null) return null
            return L10n.string(if (isRunning) "Queue" else "Send")
        }

    /** An edit this conversation had open when it was last closed, handed back by the app with the draft it saved. */
    fun resumeEdit(edit: QueuedEdit?) {
        queuedEdit = edit
    }

    /**
     * Words going back into the line, from Send or from Cancel. The field keeps them and takes no keys
     * while they are out, and the button's place holds a spinner, so nothing is sent twice. The edit
     * is over once they are on their way — accepted, or out with their fate unknown and a Retry that
     * keeps their place — and the words set aside take the field back. A refusal keeps the edit open,
     * the other draft still aside.
     */
    private suspend fun putBack(edit: QueuedEdit, send: suspend () -> SendOutcome): SendOutcome {
        isReturningEdit = true
        val outcome = try {
            send()
        } finally {
            isReturningEdit = false
        }
        if (queuedEdit == edit && (outcome == SendOutcome.accepted || outcome == SendOutcome.uncertain)) {
            queuedEdit = null
            draft = edit.aside
        }
        return outcome
    }

    private suspend fun perform(operation: suspend () -> Unit) {
        try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = describe(error)
        }
    }

    fun clearError() {
        errorMessage = null
    }

    private fun describe(error: Throwable): String = when (error) {
        is GatewayErrorBody -> error.message
        is TransportError -> error.errorDescription
        else -> error.localizedDescription
    }

    companion object {
        /** How long an answer stands before `/` asks for another one. */
        val commandsStaleAfter: Duration = 60.seconds
    }
}
