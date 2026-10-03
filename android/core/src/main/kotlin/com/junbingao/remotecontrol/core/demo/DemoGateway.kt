package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.protocol.AccountRules
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.GitStatus
import com.junbingao.remotecontrol.core.protocol.HelloFrame
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.PreferencePatch
import com.junbingao.remotecontrol.core.protocol.Preferences
import com.junbingao.remotecontrol.core.protocol.PreferencesResponse
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.protocol.UserState
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.state.GatewayAPI
import com.junbingao.remotecontrol.core.state.GatewayChannel
import com.junbingao.remotecontrol.core.state.UnseenMark
import com.junbingao.remotecontrol.core.transport.APNSRegistration
import com.junbingao.remotecontrol.core.transport.ConnectionState
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import com.junbingao.remotecontrol.core.transport.HealthResponse
import com.junbingao.remotecontrol.core.transport.LoginResponse
import com.junbingao.remotecontrol.core.transport.PairingClaim
import com.junbingao.remotecontrol.core.transport.PairingGrant
import com.junbingao.remotecontrol.core.transport.PolishModelsResponse
import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.PolishResponse
import com.junbingao.remotecontrol.core.transport.SessionInfoResponse
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.core.transport.UserListResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An in-memory gateway used by `--demo`, by previews and by the UI test.
 *
 * It never constructs a transport, so the demo cannot reach the network even by accident, and
 * every frame it emits is a real protocol frame decoded by the same code path as a live gateway.
 *
 * RCCore's actor, kept as one: its state is read and written on [isolation], which runs one piece
 * of the gateway's work at a time and lets another in wherever that work suspends. A test passes a
 * dispatcher of its own scheduler, so every scripted delay runs on virtual time. The requests the
 * gateway answers live beside this file, by subject: `DemoGatewaySessions.kt`,
 * `DemoGatewayTurns.kt`, `DemoGatewayCommands.kt`, `DemoGatewayScripts.kt` and
 * `DemoGatewayDevices.kt`.
 */
class DemoGateway(
    /**
     * How long this device takes to report a message it was sent. A real one is a round trip away
     * and the app is meant to look the same either way, so the demo keeps that moment rather than
     * hiding it (amendment A12).
     */
    internal val echoDelay: Duration = defaultEchoDelay,
    /** Amendment A15: when the archived demo session is resumed, or null to leave it in the Archive for the whole run. */
    internal val resumeDelay: Duration? = defaultResumeDelay,
    private var registrationOpen: Boolean = false,
    /**
     * Amendment A31: the oldest app build this demo gateway claims to work with. It is this build
     * by default, so the demo runs; a demo asked for a higher one is how the blocking "Update
     * required" screen is driven.
     */
    private val minimumAppVersion: String = AppBuild.version,
    /** Amendment A29: how long this gateway's stand-in model takes to answer. */
    private val polishDelay: Duration = defaultPolishDelay,
    /**
     * Amendment A33: how long this device takes to read the vendors' rate limits. A real one is a
     * network call per account away, so the demo keeps the moment the page spends saying
     * "Checking…".
     */
    internal val agentsDelay: Duration = defaultAgentsDelay,
    /**
     * Amendment A41: whether the account's other device changes a preference a few seconds after
     * Settings opens. That is the amendment made visible, so the demo does it; a UI test that is
     * reading the Voice group for something else asks for nothing to move under it.
     */
    private val changesPreferencesElsewhere: Boolean = true,
    /** How long it waits first. */
    private val elsewhereDelay: Duration = defaultElsewhereDelay,
    /**
     * Amendment A43: whether the live session opens with messages waiting behind a turn that runs
     * on, so the queue can be edited and emptied at leisure rather than draining the moment its
     * turn ends (`--demo-queue`).
     */
    internal val holdsQueue: Boolean = false,
    /** Where the gateway's work runs: one piece at a time, as an actor's does. */
    private val isolation: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : GatewayChannel, GatewayAPI {
    private val stream = Channel<GatewayEvent>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_LATEST)
    override val events: Flow<GatewayEvent> = stream.receiveAsFlow()
    override val endpoint: GatewayEndpoint =
        attempt { GatewayEndpoint("https://demo.remote-control.invalid") } ?: GatewayEndpoint.placeholder

    // The state below is the actor's: read and written only on [isolation], by this class and by
    // the gateway's other files, which is the one reason it is `internal` rather than private.

    internal val continuation: SendChannel<GatewayEvent> = stream

    /** Where the scripted moments run, each on its own job, as RCCore runs each on its own task. */
    internal val scope = CoroutineScope(SupervisorJob() + isolation)
    internal var devices: List<Device> = DemoFixtures.devices
    internal var sessionList: List<Session> = DemoFixtures.sessions

    /** Amendment A37: the directories this demo browses, which a folder made from the picker is added to. */
    internal val directories: DemoDirectoryTree = DemoDirectoryTree.demo

    /**
     * Amendment A24: the gateway's accounts, and which of them this app is. `--demo` never signs
     * in, so it starts as the operator; the sign-in form replaces it with whichever account it was
     * given.
     */
    private var accounts: List<UserRecord> = DemoFixtures.users
    private var signedIn: UserRecord = DemoFixtures.users[0]

    /**
     * Amendment A35: the account's preferences, which this gateway keeps the way a real one does —
     * one value for every app and device of the account. It starts with the resume switch alone,
     * so the demo is also the day A41 arrives: the Settings fields are absent, and the app offers
     * its own.
     */
    private var preferences = Preferences(resumeAfterLimit = true)
    internal val transcripts = mutableMapOf<String, MutableList<SessionEvent>>()
    internal val cursors = mutableMapOf<String, Int>()

    /** What each session's device is holding behind its turn, in the order it will be delivered (A43). */
    internal val queues = mutableMapOf<String, DemoQueue>()
    internal var scripted: Job? = null
    private var pairing: Job? = null

    /** The injection script for the attached session runs on its own job, so it neither cancels nor is cancelled by the live turn. */
    internal var injecting: Job? = null

    /**
     * Amendment A12: the delayed echo of a message the app sent, which is what gives the demo the
     * same "sending" moment a real device does.
     */
    internal var echoing: Job? = null

    /** Amendment A14: the step at which the agent reads a message steered into a running turn, which is when the block for it is emitted. */
    internal var steering: Job? = null

    /**
     * Amendment A15: the moment the archived demo session is resumed from its terminal, which is
     * when the device clears `archived` and publishes it.
     */
    internal var reviving: Job? = null

    /** Amendment A17: the moment the terminal switches model on the attached session, which the device reads from the transcript and publishes. */
    internal var retuning: Job? = null

    /**
     * Amendment A20: the moment the attached Claude asks its question, and the moment the person
     * at the terminal answers it in their own dialog.
     */
    internal var asking: Job? = null
    internal var answering: Job? = null

    /** Amendment A22: the moment a device that took an update on comes back, running the build the gateway serves. */
    internal var updating: Job? = null

    /** Amendment A27: the turn a slash command started, which runs beside the scripted reply rather than cancelling it. */
    internal var commanding: Job? = null

    /**
     * Amendment A38: the shells this demo is running, by terminal id, with the machine each
     * belongs to and the `seq` its output is up to.
     */
    internal val terminals = mutableMapOf<String, DemoTerminal>()

    /** The first prompt of each shell, sent once the `open` reply has landed so the screen has a terminal id to match it against. */
    internal var greeting: Job? = null

    /** Amendment A41: the moment the account's other device changes one of the Settings preferences, scheduled when Settings opens and run once. */
    private var elsewhere: Job? = null

    companion object {
        /**
         * The default is what a quick local device feels like. A UI test asks for a longer one so
         * the state a real send passes through can be looked at rather than raced.
         */
        val defaultEchoDelay: Duration = 400.milliseconds

        /**
         * Amendment A15: how long the archived session sits in the Archive before its terminal
         * resumes it. A UI test asks for none, so the list it measures holds still.
         */
        val defaultResumeDelay: Duration = 5.seconds

        /** Amendment A29: how long the demo's polish provider takes to answer. Long enough that "Polishing…" is a state you can read. */
        val defaultPolishDelay: Duration = 600.milliseconds

        /**
         * Amendment A33: how long the demo takes to answer `device.agents` with fresh windows.
         * Long enough to see the meters arrive, short enough that opening a device is not a wait.
         */
        val defaultAgentsDelay: Duration = 700.milliseconds

        /**
         * Amendment A41: how long after Settings opens the account's other device changes a
         * preference. Long enough to have read the row first, short enough to be watched rather
         * than waited for. A UI test asks for longer: the row it is about is below the fold, and a
         * switch that has already moved by the time the list has been scrolled to it proves
         * nothing.
         */
        val defaultElsewhereDelay: Duration = 3.seconds

        /** The device's own words for a terminal it will not type over: a turn is running, a dialog is open, or somebody is typing there. */
        internal const val terminalIsBusy = "the terminal is busy; try again in a moment"

        private val wellFormedUsername = Regex("^[a-z0-9][a-z0-9._-]{2,31}$")

        private fun implicitBlockID(body: SessionEventBody): String? = when (body) {
            is SessionEventBody.UserMessage, is SessionEventBody.AssistantText, is SessionEventBody.Thinking,
            is SessionEventBody.ToolCall, is SessionEventBody.Approval, is SessionEventBody.Question,
            is SessionEventBody.Error -> "demo-${uuidString().take(8)}"
            else -> null
        }

        private fun kind(of: SessionEventBody): String = when (of) {
            is SessionEventBody.UserMessage -> SessionEvent.userMessageKind
            is SessionEventBody.AssistantText -> SessionEvent.assistantTextKind
            is SessionEventBody.Thinking -> SessionEvent.thinkingKind
            is SessionEventBody.ToolCall -> SessionEvent.toolCallKind
            is SessionEventBody.Todos -> SessionEvent.todosKind
            is SessionEventBody.Approval -> SessionEvent.approvalKind
            is SessionEventBody.Question -> SessionEvent.questionKind
            is SessionEventBody.TurnStarted -> SessionEvent.turnStartedKind
            is SessionEventBody.TurnCompleted -> SessionEvent.turnCompletedKind
            is SessionEventBody.Status -> SessionEvent.statusKind
            is SessionEventBody.Meta -> SessionEvent.metaKind
            is SessionEventBody.Queue -> SessionEvent.queueKind
            is SessionEventBody.Notice -> SessionEvent.noticeKind
            is SessionEventBody.Error -> SessionEvent.errorKind
            is SessionEventBody.Resume -> SessionEvent.resumeKind
            is SessionEventBody.Unknown -> of.kind
        }
    }

    init {
        for (session in DemoFixtures.sessions) {
            val history = DemoFixtures.history(sessionID = session.sessionID)
            transcripts[session.sessionID] = history.toMutableList()
            cursors[session.sessionID] = history.lastOrNull()?.seq ?: 0
        }
        if (holdsQueue && sessionList.any { it.sessionID == DemoFixtures.liveSessionID }) {
            val queue = DemoQueue(DemoFixtures.heldMessages())
            queues[DemoFixtures.liveSessionID] = queue
            sessionList = sessionList.map {
                if (it.sessionID == DemoFixtures.liveSessionID) it.copy(queued = queue.pending.size) else it
            }
        }
    }

    // GatewayChannel

    override suspend fun connect(): Unit = withContext(isolation) {
        continuation.trySend(GatewayEvent.State(ConnectionState.connecting))
        sessionList = sessionList.map { it.copy(lastSeq = cursors[it.sessionID] ?: 0) }
        val hello = HelloFrame(protocolVersion = RemoteProtocol.version, gatewayVersion = "0.1.0-demo",
                               user = signedIn.identity, devices = devices,
                               sessions = sessionList, stt = configuration.stt,
                               polish = configuration.polish, apps = configuration.apps,
                               preferences = preferences, serverTime = DemoFixtures.now)
        continuation.trySend(GatewayEvent.State(ConnectionState.connected))
        continuation.trySend(GatewayEvent.Frame(AppFrame.Hello(hello)))
        reviving?.cancel()
        reviving = scope.launch { resumeArchivedSession() }
    }

    /** Always runs to the end, whoever asks and whatever becomes of them meanwhile. */
    override suspend fun disconnect(): Unit = withContext(NonCancellable + isolation) {
        scripted?.cancel()
        scripted = null
        pairing?.cancel()
        pairing = null
        injecting?.cancel()
        injecting = null
        reviving?.cancel()
        reviving = null
        retuning?.cancel()
        retuning = null
        asking?.cancel()
        asking = null
        answering?.cancel()
        answering = null
        updating?.cancel()
        updating = null
        commanding?.cancel()
        commanding = null
        greeting?.cancel()
        greeting = null
        elsewhere?.cancel()
        elsewhere = null
        continuation.trySend(GatewayEvent.State(ConnectionState.disconnected))
    }

    override suspend fun request(request: GatewayRequest): JsonElement = withContext(isolation) {
        when (request.type) {
            "session.subscribe" -> subscribe(request)
            "session.seen" -> markSeen(request)
            "session.history" -> history(request)
            "session.send" -> send(request)
            "session.approve" -> resolveApproval(request)
            "session.answer" -> resolveQuestion(request)
            "session.stop" -> stop(request)
            "session.set" -> applySet(request)
            "session.takeover" -> takeover(request)
            "session.archive" -> archive(request)
            "session.create" -> create(request)
            "session.block" -> fullBlock(request)
            "session.commands" -> listCommands(request)
            "session.command" -> runCommand(request)
            "session.queue_remove" -> removeQueued(request)
            "session.resume_set" -> setResume(request)
            "session.resume_cancel" -> cancelResume(request)
            "device.dirs" -> JSONValue.encode(directories.listing(of = request.body["path"]?.stringValue))
            "device.mkdir" -> JSONValue.encode(makeDirectory(request))
            "device.git" -> JSONValue.encode(GitStatus(isRepo = true, branch = "main", dirty = false, ahead = 0, behind = 0))
            "device.agents" -> agents(request)
            "device.update" -> updateDevice(request)
            "terminal.open" -> openTerminal(request)
            "terminal.input" -> writeTerminal(request)
            "terminal.resize" -> resizeTerminal(request)
            "terminal.attach" -> attachTerminal(request)
            "terminal.close" -> closeTerminal(request)
            else -> JSONValue.emptyObject
        }
    }

    // GatewayAPI

    /** What this demo gateway says about itself, built from the minimum app version it was asked for. */
    private val configuration: GatewayConfig get() = DemoFixtures.config(minimumAppVersion = minimumAppVersion)

    override suspend fun health(): HealthResponse = withContext(isolation) {
        HealthResponse(version = "0.1.0-demo", protocolVersion = RemoteProtocol.version,
                       registrationOpen = registrationOpen,
                       apps = DemoFixtures.apps(minimumAppVersion = minimumAppVersion))
    }

    /**
     * A demo holds no secrets, so any password of the length the gateway demands is accepted.
     * What it does model is the three answers the form has to tell apart: an unknown account, a
     * disabled one, and a sign-in.
     */
    override suspend fun login(username: String, password: String): LoginResponse = withContext(isolation) {
        val name = username.lowercase()
        val account = accounts.firstOrNull { it.username == name }
        if (!AccountRules.isPasswordLongEnough(password) || account == null) throw TransportError.Unauthorized
        if (!account.isActive) throw TransportError.Http(status = 403, code = "forbidden")
        signedIn = account
        signIn(account)
    }

    override suspend fun register(username: String, password: String): LoginResponse = withContext(isolation) {
        val name = username.lowercase()
        if (!registrationOpen) throw TransportError.Http(status = 403, code = "forbidden")
        if (!isWellFormed(name) || !AccountRules.isPasswordLongEnough(password)) {
            throw TransportError.Http(status = 400, code = "bad_request")
        }
        if (accounts.any { it.username == name }) throw TransportError.Http(status = 409, code = "conflict")
        val account = UserRecord(username = name, role = UserRole.member, state = UserState.active,
                                 createdAt = DemoFixtures.now, lastLoginAt = DemoFixtures.now, devices = 0)
        accounts = accounts + account
        signedIn = account
        signIn(account)
    }

    override suspend fun changePassword(current: String, new: String): Unit = withContext(isolation) {
        if (signedIn.isOperator) throw TransportError.Http(status = 403, code = "forbidden")
        if (!AccountRules.isPasswordLongEnough(current)) throw TransportError.Unauthorized
        if (!AccountRules.isPasswordLongEnough(new)) throw TransportError.Http(status = 400, code = "bad_request")
    }

    private fun signIn(account: UserRecord): LoginResponse =
        LoginResponse(token = "demo", exp = DemoFixtures.now + 86_400_000, user = account.identity)

    private fun isWellFormed(username: String): Boolean = wellFormedUsername.containsMatchIn(username)

    override suspend fun session(): SessionInfoResponse = withContext(isolation) {
        SessionInfoResponse(user = signedIn.identity, exp = DemoFixtures.now + 86_400_000)
    }

    override suspend fun logout() {}

    override suspend fun config(): GatewayConfig = withContext(isolation) { configuration }

    /**
     * Amendment A35: the account's preferences, and a write that reaches every other app of the
     * account as `preferences.updated` — which is how the switch on one screen moves the switch on
     * another.
     */
    override suspend fun preferences(): PreferencesResponse = withContext(isolation) {
        PreferencesResponse(preferences = preferences)
    }

    /**
     * Amendment A41: the fields the write names are set and the rest are left, exactly as a real
     * gateway does it, and the whole object goes out at once.
     */
    override suspend fun patchPreferences(changes: PreferencePatch): PreferencesResponse = withContext(isolation) {
        preferences = preferences.applying(changes)
        continuation.trySend(GatewayEvent.Frame(AppFrame.PreferencesUpdated(preferences)))
        // Turning it off cancels every pending resume, on every device.
        if (!preferences.resumeAfterLimit) cancelEveryResume()
        PreferencesResponse(preferences = preferences)
    }

    /**
     * Amendment A29: two models, and a stand-in that cleans the words rather than reaching a
     * provider. The delay is what makes "Polishing…" visible.
     *
     * Amendment A41: this is also the one thing the demo can read as "Settings is on screen" — the
     * Voice group asks for the models the moment it appears — so it is where the account's other
     * device is scheduled to change a preference under the reader's eyes.
     */
    override suspend fun polishModels(): PolishModelsResponse = withContext(isolation) {
        scheduleChangeFromAnotherDevice()
        DemoFixtures.polishModels
    }

    /**
     * Amendment A41: the preferences are the account's, so a change made somewhere else arrives
     * here as a frame and moves the control in place. A few seconds after Settings opens, the
     * account's other device turns dictation polish on; nothing on this screen was touched.
     */
    private fun scheduleChangeFromAnotherDevice() {
        if (!changesPreferencesElsewhere || elsewhere != null) return
        elsewhere = scope.launch {
            pause(elsewhereDelay)
            if (isCancelled()) return@launch
            turnPolishOnFromAnotherDevice()
        }
    }

    private fun turnPolishOnFromAnotherDevice() {
        preferences = preferences.applying(PreferencePatch(polishEnabled = true))
        continuation.trySend(GatewayEvent.Frame(AppFrame.PreferencesUpdated(preferences)))
    }

    override suspend fun polish(request: PolishRequest): PolishResponse = withContext(isolation) {
        pause(polishDelay)
        PolishResponse(text = DemoFixtures.polished(request.text))
    }

    override suspend fun devices(): List<Device> = withContext(isolation) { devices }

    override suspend fun renameDevice(deviceID: String, name: String): Device = withContext(isolation) {
        if (devices.none { it.deviceID == deviceID }) {
            throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "No such device")
        }
        update(deviceID = deviceID) { it.copy(name = name) }
        device(deviceID)
    }

    override suspend fun revokeDevice(deviceID: String): Unit = withContext(isolation) {
        devices = devices.filter { it.deviceID != deviceID }
        sessionList = sessionList.filter { it.deviceID != deviceID }
        continuation.trySend(GatewayEvent.Frame(AppFrame.DeviceRemoved(deviceID = deviceID)))
    }

    override suspend fun beginPairing(): PairingGrant = withContext(isolation) {
        val grant = DemoFixtures.pairingGrant
        pairing?.cancel()
        pairing = scope.launch { runPairingScript(code = grant.code) }
        grant
    }

    override suspend fun cancelPairing(code: String): Unit = withContext(isolation) {
        pairing?.cancel()
        pairing = null
    }

    override suspend fun claimPairingRequest(token: String): PairingClaim = withContext(isolation) {
        if (token != DemoFixtures.claimToken) {
            throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "No such pairing request")
        }
        val claim = DemoFixtures.pairingClaim
        pairing?.cancel()
        pairing = scope.launch { runPairingScript(code = claim.code) }
        claim
    }

    override suspend fun sessions(deviceID: String?, archived: Boolean?): List<Session> = withContext(isolation) {
        sessionList.filter { deviceID == null || it.deviceID == deviceID }
    }

    // Accounts (protocol 3.9)

    override suspend fun users(): UserListResponse = withContext(isolation) {
        requireAdmin()
        UserListResponse(users = accounts, registrationOpen = registrationOpen)
    }

    override suspend fun createUser(username: String, password: String, role: UserRole): UserRecord =
        withContext(isolation) {
            requireAdmin()
            val name = username.lowercase()
            if (!isWellFormed(name) || !AccountRules.isPasswordLongEnough(password)) {
                throw TransportError.Http(status = 400, code = "bad_request")
            }
            if (accounts.any { it.username == name }) throw TransportError.Http(status = 409, code = "conflict")
            val record = UserRecord(username = name, role = role, state = UserState.active,
                                    createdAt = DemoFixtures.now, lastLoginAt = null, devices = 0)
            accounts = accounts + record
            record
        }

    override suspend fun patchUser(username: String, state: UserState?, role: UserRole?, password: String?): UserRecord =
        withContext(isolation) {
            requireAdmin()
            val existing = account(username)
            // The operator cannot be disabled, demoted or re-passworded: its password is the
            // gateway's own and it is the account that runs it.
            if (existing.isOperator) throw TransportError.Http(status = 409, code = "conflict")
            if (password != null && !AccountRules.isPasswordLongEnough(password)) {
                throw TransportError.Http(status = 400, code = "bad_request")
            }
            val record = UserRecord(username = existing.username, role = role ?: existing.role,
                                    state = state ?: existing.state, createdAt = existing.createdAt,
                                    lastLoginAt = existing.lastLoginAt, devices = existing.devices)
            accounts = accounts.map { if (it.username == record.username) record else it }
            record
        }

    override suspend fun deleteUser(username: String): Unit = withContext(isolation) {
        requireAdmin()
        val existing = account(username)
        if (existing.isOperator) throw TransportError.Http(status = 409, code = "conflict")
        accounts = accounts.filter { it.username != existing.username }
    }

    override suspend fun setRegistration(open: Boolean): Boolean = withContext(isolation) {
        requireAdmin()
        registrationOpen = open
        registrationOpen
    }

    private fun requireAdmin() {
        if (!signedIn.role.isAdmin) throw TransportError.Http(status = 403, code = "forbidden")
    }

    private fun account(username: String): UserRecord =
        accounts.firstOrNull { it.username == username.lowercase() }
            ?: throw TransportError.Http(status = 404, code = "not_found")

    override suspend fun registerPush(registration: APNSRegistration) {}

    override suspend fun unregisterPush(token: String) {}

    override suspend fun restoreToken(username: String): Boolean = true

    override suspend fun bearerToken(): String? = "demo"

    override suspend fun forgetToken(username: String) {}

    // Request handling

    internal fun requireSessionID(request: GatewayRequest): String =
        request.body["session_id"]?.stringValue
            ?: throw GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "session_id is required")

    internal fun session(id: String): Session =
        sessionList.firstOrNull { it.sessionID == id }
            ?: throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "No such session")

    internal fun device(id: String): Device =
        devices.firstOrNull { it.deviceID == id }
            ?: throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "No such device")

    /**
     * What the device says the agent running this session can do. Amendments A10 and A11 answer
     * every "may the app do this while attached?" from here rather than from the agent id.
     */
    internal fun agent(session: Session): AgentInfo? =
        devices.firstOrNull { it.deviceID == session.deviceID }?.agent(session.agent)

    // Emission

    internal fun emit(sessionID: String, blockID: String? = null, body: SessionEventBody) {
        val seq = (cursors[sessionID] ?: 0) + 1
        cursors[sessionID] = seq
        val event = SessionEvent(seq = seq, ts = DemoFixtures.now, kind = kind(of = body),
                                 blockID = blockID ?: implicitBlockID(body), body = body)
        transcripts.getOrPut(sessionID) { mutableListOf() }.add(event)
        val deviceID = sessionList.firstOrNull { it.sessionID == sessionID }?.deviceID
        continuation.trySend(GatewayEvent.Frame(AppFrame.SessionEvent(sessionID = sessionID, deviceID = deviceID,
                                                                      event = event)))
    }

    @JvmName("updateDevice")
    internal fun update(deviceID: String, mutate: (Device) -> Device) {
        val index = devices.indexOfFirst { it.deviceID == deviceID }
        if (index < 0) return
        devices = devices.toMutableList().also { it[index] = mutate(it[index]) }
        continuation.trySend(GatewayEvent.Frame(AppFrame.DeviceUpdated(devices[index])))
    }

    /**
     * Every change the device makes to a session passes here, so the mark of amendment A47 is kept
     * where the gateway keeps it: on the move from working to waiting, and off when it works again or
     * is archived.
     */
    @JvmName("updateSession")
    internal fun update(sessionID: String, mutate: (Session) -> Session) {
        val index = sessionList.indexOfFirst { it.sessionID == sessionID }
        if (index < 0) return
        sessionList = sessionList.toMutableList().also {
            val previous = it[index]
            val current = mutate(previous)
            it[index] = current.copy(unseen = UnseenMark.next(previous = previous, current = current),
                                     updatedAt = DemoFixtures.now)
        }
        continuation.trySend(GatewayEvent.Frame(AppFrame.SessionUpdated(sessionList[index])))
    }
}

/** RCCore's `UUID().uuidString`: upper-case hex in the usual groups. */
internal fun uuidString(): String = UUID.randomUUID().toString().uppercase()

/**
 * RCCore's `try? await Task.sleep(for:)`: a pause that cancellation cuts short rather than ends.
 * What follows it runs either way, exactly as it does in RCCore, and checks [isCancelled] where
 * RCCore checks `Task.isCancelled`.
 */
internal suspend fun pause(duration: Duration) {
    try {
        delay(duration)
    } catch (_: CancellationException) {
        // Swallowed as RCCore's `try?` swallows it; the job stays cancelled.
    }
}

/** RCCore's `Task.isCancelled`. */
internal suspend fun isCancelled(): Boolean = !currentCoroutineContext().isActive
