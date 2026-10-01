package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.persistence.CachedWorkspace
import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.persistence.MemorySecretStore
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.AppsInfo
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.PolishInfo
import com.junbingao.remotecontrol.core.protocol.STTConfig
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.core.transport.ConnectionState
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import com.junbingao.remotecontrol.core.transport.GatewayHTTPClient
import com.junbingao.remotecontrol.core.transport.GatewaySocket
import com.junbingao.remotecontrol.core.transport.LoginResponse
import com.junbingao.remotecontrol.core.transport.SocketCloseReason
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Gateway identity, authentication, and the live inventory of devices and sessions.
 *
 * One coroutine per connection scope reads the socket stream, and cancelling it is what ends that
 * scope's stream of frames. The HTTP routes that confirm a scope behind the screens are not on that
 * stream, so they carry the scope they were issued in and are checked against it before anything
 * they say is applied — as is every other assignment here that follows a suspension.
 *
 * The work it starts runs in [tasks], the scope the owner passes in. The app hands in how to reach a
 * gateway: [makeAPI] builds the HTTP client for an origin, with the app's own secret store, and
 * [makeChannel] the socket over it.
 */
class ConnectionStore(
    private val tasks: CoroutineScope,
    /** Amendment A45: whose entry of `apps` this build is measured against. */
    private val installedApp: InstalledApp = InstalledApp.ios,
    private val cache: LocalCache,
    private val makeAPI: (GatewayEndpoint) -> GatewayAPI,
    private val makeChannel: (GatewayAPI) -> GatewayChannel = { api ->
        GatewaySocket(client = api as? GatewayHTTPClient ?: GatewayHTTPClient(api.endpoint, secrets = MemorySecretStore()))
    },
) {
    var phase: ConnectionPhase by mutableStateOf(ConnectionPhase.SignedOut)
        private set
    var endpoint: GatewayEndpoint? by mutableStateOf(null)
        private set

    /**
     * The account this connection signed in as, and its role (protocol 4.10). Everything the socket
     * ever reports belongs to it and to nobody else.
     */
    var user: UserIdentity by mutableStateOf(UserIdentity(username = ""))
        private set
    var gatewayVersion: String by mutableStateOf("")
        private set
    var config: GatewayConfig by mutableStateOf(GatewayConfig.empty)
        private set
    var stt: STTConfig by mutableStateOf(STTConfig.disabled)
        private set

    /**
     * Amendment A29: whether this gateway has a polish model at all. The switch, the model and the
     * strength are the user's own settings; this is the only thing the gateway has a say in.
     */
    var polish: PolishInfo by mutableStateOf(PolishInfo.disabled)
        private set

    /**
     * Amendment A31: this build is older than the gateway will talk to, with the minimum it asks for
     * and where a newer build is. Nothing else in the app is reachable while it is set, and only
     * signing out clears it — which is how a person reaches another gateway.
     */
    var updateRequired: AppUpdateRequirement? by mutableStateOf(null)
        private set
    var devices: List<Device> by mutableStateOf(emptyList())
        private set
    var sessions: List<Session> by mutableStateOf(emptyList())
        private set
    var errorMessage: String? by mutableStateOf(null)
        private set
    var isDemo: Boolean by mutableStateOf(false)
        private set

    /** True once the first `hello` has been applied for the current scope. */
    var hasSnapshot: Boolean by mutableStateOf(false)
        private set

    var api: GatewayAPI? = null
        private set
    var channel: GatewayChannel? = null
        private set

    private var pump: Job? = null

    /**
     * Which signed-in connection this is: one gateway and one account on it. Everything that
     * suspends captures the scope it was issued in and drops its answer once the store has moved on —
     * to another account, to another gateway, or to no connection at all. Without it a slow
     * `/api/session` or `/api/config` from a gateway the person has left rewrites the live one.
     */
    private var scope = 0

    /** The two calls that confirm a scope after the screens have already been drawn for it. Held so leaving the scope cancels them. */
    private var confirmation: Job? = null
    private var configuration: Job? = null
    private val frameHandlers = LinkedHashMap<String, (AppFrame) -> Unit>()

    /**
     * Called with both versions whenever a session the app already knew is replaced by a newer one. A
     * session arriving for the first time — the `hello` list, or one the device just created — is not
     * a transition and never reaches this. Nothing in the store reads it; the app announces finished
     * turns from it (`docs/DESIGN.md` § "Being told when a turn ends").
     */
    var onSessionTransition: ((Session, Session) -> Unit)? = null

    // Connection scope

    /**
     * Leave the current scope: what is still in flight for it belongs to nothing, and the work that
     * would have applied it is cancelled. Returns the scope the caller is entering.
     */
    private fun beginScope(): Int {
        scope += 1
        confirmation?.cancel()
        confirmation = null
        configuration?.cancel()
        configuration = null
        return scope
    }

    /** Whether an answer issued in `value` may still be applied. */
    private fun isCurrent(value: Int): Boolean = value == scope

    // Derived views

    val isSignedIn: Boolean get() = phase != ConnectionPhase.SignedOut

    val username: String get() = user.username

    /** Whether the accounts screen of 3.9 is this person's to see (A24). */
    val isAdmin: Boolean get() = user.role.isAdmin

    val account: String get() = "${endpoint?.origin ?: "demo"}|$username"

    fun device(id: String): Device? = devices.firstOrNull { it.deviceID == id }

    fun session(deviceID: String, sessionID: String): Session? =
        sessions.firstOrNull { it.deviceID == deviceID && it.sessionID == sessionID }

    val onlineDevices: List<Device> get() = devices.filter { it.online }

    /** "2 devices · 1 waiting" for the sessions footer. */
    val inventorySummary: String
        get() {
            val waiting = sessions.count { it.state.isBlockedOnUser }
            val count = devices.size
            val devices = L10n.string(if (count == 1) "%lld device" else "%lld devices", count)
            return if (waiting == 0) devices else L10n.string("%@ · %lld waiting", devices, waiting)
        }

    // Authentication

    /**
     * Whether this gateway is taking registrations, asked before anyone has an account (A24). The
     * sign-in form is the only caller: "Create an account" is offered where the gateway says it can
     * be, and nowhere else.
     */
    suspend fun registrationOpen(origin: String): Boolean {
        val scope = this.scope
        val endpoint = attempt { GatewayEndpoint(origin) } ?: return false
        val health = attempt { makeAPI(endpoint).health() } ?: return false
        // Amendment A31: this route needs no credential, so it is where an app the gateway is too
        // new for finds out — before it has typed a password. Only while the form is still on the
        // gateway it asked about: a slow answer must not block an app that has since signed in
        // somewhere else.
        if (!isCurrent(scope)) return health.registrationOpen
        note(apps = health.apps)
        return health.registrationOpen
    }

    /**
     * Amendment A31: the first source to say this build is too old wins. `GET /api/health`,
     * `GET /api/config` and `hello` all carry `apps` and arrive in no fixed order; nothing after the
     * first refusal can lower the bar, and only signing out clears it.
     */
    private fun note(apps: AppsInfo?) {
        if (updateRequired != null) return
        updateRequired = AppUpdateRequirement.of(apps, app = installedApp) ?: return
    }

    suspend fun signIn(origin: String, username: String, password: String) {
        authenticate(origin = origin, describe = AccountError::signIn) { api ->
            api.login(username = username, password = password)
        }
    }

    /** `POST /api/register` (A24). Creating an account signs it in, so this is a sign-in with one different route and one different set of refusals. */
    suspend fun register(origin: String, username: String, password: String) {
        authenticate(origin = origin, describe = AccountError::register) { api ->
            api.register(username = username, password = password)
        }
    }

    private suspend fun authenticate(origin: String, describe: (Throwable) -> String,
                                     call: suspend (GatewayAPI) -> LoginResponse) {
        val scope = this.scope
        errorMessage = null
        try {
            val endpoint = GatewayEndpoint(origin)
            val api = makeAPI(endpoint)
            val response = call(api)
            if (isCancelled() || !isCurrent(scope)) return
            adopt(api = api, endpoint = endpoint, user = response.user)
            start()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A refusal from a sign-in the person has already left behind must not take down the
            // connection they are on now.
            if (!isCurrent(scope)) return
            phase = ConnectionPhase.SignedOut
            errorMessage = describe(error)
        }
    }

    /**
     * Reconnect on launch when a stored token is still valid.
     *
     * The account is adopted the moment the secret store answers, so the app draws its own screens in
     * their connecting state rather than a sign-in form for the length of a round trip. The token is
     * checked behind them: only a refusal ends the session, because a gateway that cannot be reached
     * is a link problem and the socket is already reconnecting through it.
     */
    suspend fun restore(origin: String, username: String): Boolean {
        val endpoint = attempt { GatewayEndpoint(origin) } ?: return false
        val api = makeAPI(endpoint)
        if (!api.restoreToken(username = username)) return false
        // The role is not in the secret store. `/api/session` behind these screens carries it, and
        // until it lands the app draws a member's Settings — one row short rather than one row nobody
        // is allowed to open.
        adopt(api = api, endpoint = endpoint, user = UserIdentity(username = username))
        start()
        val scope = this.scope
        confirmation = tasks.launch { confirmStoredAccount(api = api, scope = scope) }
        return true
    }

    /**
     * The stored token against `/api/session`, behind the screens it already unlocked. A 401 is the
     * one answer that sends the user back to the form.
     *
     * The answer is applied only while the store is still on the scope that asked for it. A person
     * who signs out and in as somebody else while this is in flight would otherwise be told they are
     * the previous account, with the previous account's role, its cache file and its draft file.
     */
    private suspend fun confirmStoredAccount(api: GatewayAPI, scope: Int) {
        try {
            val info = api.session()
            if (isCancelled() || !isCurrent(scope) || info.user.username.isEmpty()) return
            user = info.user
        } catch (error: CancellationException) {
            throw error
        } catch (_: TransportError.Unauthorized) {
            if (isCancelled() || !isCurrent(scope)) return
            phase = ConnectionPhase.Expired
            endSession(message = L10n.string("Your session expired. Sign in again."))
        } catch (_: Exception) {
            // A gateway that did not answer has said nothing about the token.
        }
    }

    /** Enter the offline demo. It never constructs a network transport. */
    suspend fun enterDemo(api: GatewayAPI, channel: GatewayChannel) {
        val scope = beginScope()
        isDemo = true
        endpoint = api.endpoint
        // The sample gateway names its own account, and it is the operator's, so every screen an
        // admin has is reachable from the demo.
        val identity = attempt { api.session().user } ?: UserIdentity(username = "")
        if (!isCurrent(scope)) return
        user = identity
        this.api = api
        this.channel = channel
        start(channel = channel)
        // The demo answers `/api/config` from memory and reaches nothing, and the screens read the
        // served client build from it (A22).
        loadConfig(scope = scope)
    }

    suspend fun signOut() {
        beginScope()
        pump?.cancel()
        pump = null
        channel?.disconnect()
        val api = api
        if (api != null && !isDemo) {
            attempt { api.logout() }
            api.forgetToken(username = username)
            cache.clear(origin = api.endpoint.origin, username = username)
        }
        this.api = null
        channel = null
        devices = emptyList()
        sessions = emptyList()
        user = UserIdentity(username = "")
        hasSnapshot = false
        isDemo = false
        polish = PolishInfo.disabled
        // Amendment A31: signing out is the way off a gateway this build is too old for, so the
        // blocking screen goes with the connection.
        updateRequired = null
        phase = ConnectionPhase.SignedOut
    }

    private fun adopt(api: GatewayAPI, endpoint: GatewayEndpoint, user: UserIdentity) {
        beginScope()
        this.api = api
        this.endpoint = endpoint
        this.user = user
        isDemo = false
    }

    /** The admin's accounts screen, built on this connection's own credential. */
    fun usersStore(): UsersStore? {
        val api = api ?: return null
        if (!isAdmin) return null
        return UsersStore(api = api)
    }

    /** `POST /api/password`: the signed-in person changing their own. */
    suspend fun changePassword(current: String, new: String) {
        val api = api ?: throw TransportError.NotConnected
        api.changePassword(current = current, new = new)
    }

    // Connection scope

    suspend fun start() {
        val api = api ?: return
        pump?.cancel()
        pump = null
        channel?.disconnect()
        val channel = makeChannel(api)
        this.channel = channel
        start(channel = channel)
        val scope = this.scope
        configuration?.cancel()
        configuration = tasks.launch { loadConfig(scope = scope) }
    }

    private suspend fun start(channel: GatewayChannel) {
        pump?.cancel()
        phase = ConnectionPhase.Connecting
        paintFromCache(scope = scope)
        pump = tasks.launch {
            channel.connect()
            channel.events.collect { event -> receive(event) }
        }
    }

    /** Render the last known list immediately, then let `hello` correct it. */
    private suspend fun paintFromCache(scope: Int) {
        val origin = endpoint?.origin ?: return
        if (isDemo) return
        val workspace = cache.load(origin = origin, username = username) ?: return
        if (isCancelled() || !isCurrent(scope) || hasSnapshot) return
        devices = workspace.devices
        sessions = workspace.sessions
    }

    suspend fun cachedTranscript(sessionID: String, deviceID: String): List<SessionEvent> {
        val origin = endpoint?.origin ?: return emptyList()
        if (isDemo) return emptyList()
        val workspace = cache.load(origin = origin, username = username) ?: return emptyList()
        return workspace.transcripts["$deviceID/$sessionID"] ?: emptyList()
    }

    suspend fun persist(transcript: List<SessionEvent>, sessionID: String, deviceID: String) {
        val scope = this.scope
        val origin = endpoint?.origin ?: return
        if (isDemo) return
        val workspace = cache.load(origin = origin, username = username) ?: CachedWorkspace()
        if (!isCurrent(scope)) return
        val saved = workspace.copy(
            devices = devices,
            sessions = sessions,
            transcripts = workspace.transcripts + ("$deviceID/$sessionID" to transcript.takeLast(LocalCache.eventLimit)),
            savedAt = System.currentTimeMillis(),
        )
        cache.save(saved, origin = origin, username = username)
    }

    suspend fun persistInventory() {
        val scope = this.scope
        val origin = endpoint?.origin ?: return
        if (isDemo) return
        val workspace = cache.load(origin = origin, username = username) ?: CachedWorkspace()
        if (!isCurrent(scope)) return
        cache.save(workspace.copy(devices = devices, sessions = sessions, savedAt = System.currentTimeMillis()),
                   origin = origin, username = username)
    }

    /**
     * Everything `/api/config` decides, applied only while the store is still on the gateway that was
     * asked.
     *
     * Amendment A31's [updateRequired] is the reason this matters most: a slow answer from a gateway
     * the person has left would put the blocking "Update required" screen over a gateway that states
     * no minimum at all, and the only way out of it is to sign out again.
     */
    private suspend fun loadConfig(scope: Int) {
        val api = api ?: return
        val value = attempt { api.config() } ?: return
        if (isCancelled() || !isCurrent(scope)) return
        config = value
        // `hello` and `/api/config` describe the same gateway. The one that arrives later wins, and
        // this call always follows the hello it races.
        stt = value.stt
        polish = value.polish
        note(apps = value.apps)
    }

    // Frames

    /** Register a listener for a scope such as one open chat. The token is the caller's own key; unregistering is its responsibility. */
    fun addFrameHandler(token: String, handler: (AppFrame) -> Unit) {
        frameHandlers[token] = handler
    }

    fun removeFrameHandler(token: String) {
        frameHandlers.remove(token)
    }

    private suspend fun receive(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.State -> phase = when (event.state) {
                ConnectionState.connecting -> if (hasSnapshot) ConnectionPhase.Reconnecting else ConnectionPhase.Connecting
                ConnectionState.reconnecting -> ConnectionPhase.Reconnecting
                ConnectionState.connected -> ConnectionPhase.Syncing
                ConnectionState.unauthorized -> ConnectionPhase.Expired
                ConnectionState.disconnected, ConnectionState.idle ->
                    if (phase != ConnectionPhase.SignedOut) ConnectionPhase.Reconnecting else phase
            }
            is GatewayEvent.Failure -> {
                val error = event.error
                if (error is TransportError.ProtocolMismatch) phase = ConnectionPhase.Incompatible(gatewayVersion = error.version)
                errorMessage = error.errorDescription
            }
            is GatewayEvent.RequestUncertain -> Unit
            is GatewayEvent.Closed -> handle(close = event.reason)
            is GatewayEvent.Frame -> {
                apply(event.frame)
                // A handler may add or remove handlers; the ones registered when the frame arrived
                // are the ones it reaches.
                for (handler in frameHandlers.values.toList()) handler(event.frame)
            }
        }
    }

    /** Amendment A4: 4401 and 4403 end the session, 4001 stops the loop without signing out, and everything else never reaches here. */
    private suspend fun handle(close: SocketCloseReason) {
        when (close) {
            SocketCloseReason.unauthorized -> endSession(message = L10n.string("Your session expired. Sign in again."))
            SocketCloseReason.forbidden -> endSession(message = L10n.string(
                "This gateway refused the connection. Ask whoever runs it for access."))
            SocketCloseReason.replaced -> {
                phase = ConnectionPhase.Superseded
                errorMessage = L10n.string("Another app took over this connection.")
            }
            SocketCloseReason.transient -> Unit
        }
    }

    /** Return to the login screen, forgetting the token but keeping the cached lists so the next sign-in paints immediately. */
    private suspend fun endSession(message: String) {
        beginScope()
        pump?.cancel()
        pump = null
        channel?.disconnect()
        api?.forgetToken(username = username)
        channel = null
        hasSnapshot = false
        phase = ConnectionPhase.SignedOut
        errorMessage = message
    }

    /** Try again after a replaced connection. Nothing else auto-reconnects. */
    suspend fun reconnect() {
        if (phase != ConnectionPhase.Superseded || api == null) return
        start()
    }

    private fun apply(frame: AppFrame) {
        when (frame) {
            is AppFrame.Hello -> {
                val hello = frame.hello
                gatewayVersion = hello.gatewayVersion
                if (hello.user.username.isNotEmpty()) user = hello.user
                devices = hello.devices
                sessions = hello.sessions
                stt = hello.stt
                polish = hello.polish
                // Amendment A31: a gateway upgraded under a connected app is caught here, at the next
                // connection, rather than at the next launch.
                note(apps = hello.apps)
                hasSnapshot = true
                phase = ConnectionPhase.Connected
                errorMessage = null
                tasks.launch { persistInventory() }
            }
            is AppFrame.DeviceUpdated -> {
                val device = frame.device
                val index = devices.indexOfFirst { it.deviceID == device.deviceID }
                devices = if (index >= 0) devices.toMutableList().also { it[index] = device } else devices + device
            }
            is AppFrame.DeviceRemoved -> {
                devices = devices.filter { it.deviceID != frame.deviceID }
                sessions = sessions.filter { it.deviceID != frame.deviceID }
            }
            is AppFrame.SessionUpdated -> {
                val session = frame.session
                val index = sessions.indexOfFirst { it.id == session.id }
                if (index >= 0) {
                    val previous = sessions[index]
                    sessions = sessions.toMutableList().also { it[index] = session }
                    onSessionTransition?.invoke(previous, session)
                } else {
                    sessions = sessions + session
                }
            }
            is AppFrame.SessionRemoved -> {
                // The session id is the key; the device id only narrows it when the gateway sent one
                // (amendment A5).
                sessions = sessions.filterNot {
                    it.sessionID == frame.sessionID && (frame.deviceID == null || it.deviceID == frame.deviceID)
                }
            }
            else -> Unit
        }
    }

    /**
     * Close a session from the list: the device interrupts its turn, ends what it holds for the agent
     * and files the row, and the one reply carries all three (A39). A failure surfaces instead of
     * disappearing. Nothing here ever clears the flag — the device does that when the session comes
     * back to life (A15).
     */
    suspend fun close(session: Session) {
        val channel = channel ?: return
        val scope = this.scope
        try {
            val result = channel.request(GatewayRequest.archive(sessionID = session.sessionID, archived = true),
                                         SessionResult.serializer())
            if (!isCurrent(scope)) return
            apply(AppFrame.SessionUpdated(result.session))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!isCurrent(scope)) return
            errorMessage = message(error)
        }
    }

    fun message(error: Throwable): String = GatewayMessage.text(error)

    fun clearError() {
        errorMessage = null
    }

    companion object {
        /**
         * A store whose gateway is the offline demo, reached through the sign-in form rather than
         * around it. It is how the account screens — signing in with a username, registering, the
         * admin's Users screen — are driven without a gateway to reach. The app hands in the scope
         * and the cache it would give a connection to a real gateway.
         */
        fun offlineDemo(tasks: CoroutineScope, cache: LocalCache, installedApp: InstalledApp = InstalledApp.ios,
                        registrationOpen: Boolean = false): ConnectionStore {
            val gateway = DemoGateway(registrationOpen = registrationOpen)
            return ConnectionStore(tasks = tasks, installedApp = installedApp, cache = cache, makeAPI = { gateway },
                                   makeChannel = { gateway })
        }
    }
}
