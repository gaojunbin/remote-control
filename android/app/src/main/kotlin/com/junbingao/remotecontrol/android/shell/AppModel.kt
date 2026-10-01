package com.junbingao.remotecontrol.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.navigation.Navigator
import com.junbingao.remotecontrol.android.scanner.CodeScanning
import com.junbingao.remotecontrol.android.scanner.StaticCodeScanner
import com.junbingao.remotecontrol.android.scanner.SystemCodeScanner
import com.junbingao.remotecontrol.android.screens.alerts.PushController
import com.junbingao.remotecontrol.android.screens.alerts.TurnNotifier
import com.junbingao.remotecontrol.android.screens.devices.DeviceRoute
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.persistence.DraftStore
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateResult
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.ResumePayload
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.PairingFlow
import com.junbingao.remotecontrol.core.state.PreferenceSync
import com.junbingao.remotecontrol.core.state.PreferencesStore
import com.junbingao.remotecontrol.core.state.QueuedEdit
import com.junbingao.remotecontrol.core.state.SessionStore
import com.junbingao.remotecontrol.core.state.SettingsStore
import com.junbingao.remotecontrol.core.state.TurnAlerts
import com.junbingao.remotecontrol.core.state.VoiceBackend
import com.junbingao.remotecontrol.core.state.inEffect
import com.junbingao.remotecontrol.core.state.request
import com.junbingao.remotecontrol.core.transport.SessionLink
import java.net.URI
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The object every screen reads: the iPhone's `AppModel`. It owns the stores, the open
 * conversation and the navigation the app can be driven into from a notification or a link.
 *
 * Built once per process ([AppEnvironment.model]) with what the launch asked for, as the iPhone
 * builds its one in the `App` and never in a view. What a screen reads is snapshot state, so a
 * change redraws it; everything runs on the main thread, and the work it starts runs in [tasks].
 *
 * The iPhone also drops the gateway's push while the app reads the same stream
 * (`syncRemoteBanners`); Android has no push channel yet (`docs/DESIGN.md` § "The Android app"),
 * so there is nothing here to drop.
 */
class AppModel(
    private val tasks: CoroutineScope,
    val connection: ConnectionStore,
    val settings: SettingsStore,
    val sessions: SessionStore,
    val push: PushController,
    /** Raises the app's own notification when a turn ends. */
    val turns: TurnNotifier,
    private val drafts: DraftStore,
    /**
     * What the launch asked for. The model applies the arguments that are its own; a piece that
     * reads one of its own — the scripted dictation's `--voice-preview`, `--voice-level=`,
     * `--voice-transcript=` — reads it here, as the iPhone's read `ProcessInfo` for them.
     */
    val options: LaunchOptions,
    /** Where the demo gateway's work runs: one piece at a time, as its actor does; a test passes its own clock's. */
    private val demoIsolation: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    enum class Tab { sessions, devices, settings }

    /**
     * Amendment A35: the account's preferences, which live on the gateway and not on this phone.
     * Seeded from `hello` and kept in step by the socket.
     */
    val preferences = PreferencesStore()

    /**
     * Amendment A41: the Settings screen's own preferences are the account's too. Screens still read
     * [settings]; this keeps it equal to what the gateway holds, in both directions.
     */
    val preferenceSync = PreferenceSync(settings, tasks)

    /** The open tab, a stack per tab, and the tab bar's visibility. */
    val navigation = ShellNavigation()

    var tab: Tab
        get() = navigation.tab
        set(value) {
            navigation.tab = value
        }

    /**
     * The Sessions stack: session keys, not sessions. A `Session` changes on every status, meta and
     * todo event; a destination keyed by the value would rebuild the chat screen many times a turn.
     */
    var path: List<String>
        get() = sessionsStack.routes.drop(1).filterIsInstance<String>()
        set(value) {
            sessionsStack.setPath(value)
        }

    /**
     * Amendment A38: the Devices stack, which has two destinations — the machine's page and a shell
     * on it — so the tap and the menu can lead to different places.
     */
    var devicePath: List<DeviceRoute>
        get() = navigation.navigator(Tab.devices).routes.drop(1).filterIsInstance<DeviceRoute>()
        set(value) {
            navigation.navigator(Tab.devices).setPath(value)
        }

    var chat: ChatStore? by mutableStateOf(null)
        private set
    var isLocked: Boolean by mutableStateOf(false)
    var toast: String? by mutableStateOf(null)

    /**
     * Whether the app is in the foreground. The conversation holds the screen awake only while it
     * is, and a turn is announced only while the stream is being read.
     */
    var isSceneActive: Boolean by mutableStateOf(false)
        private set

    /**
     * Whether there is an account to come back to, from launch until the Keystore has answered. The
     * root draws the page colour and nothing else while it holds: a sign-in form that flashes for
     * the length of a restore on every launch reads as a broken app.
     */
    var isResuming: Boolean by mutableStateOf(false)
        private set

    /**
     * Amendment A22: why a device refused the last update it was asked for. A refusal means nothing
     * started, so no `device.updated` will ever carry it and the app is the only place it can live.
     */
    var deviceUpdateErrors: Map<String, String> by mutableStateOf(emptyMap())
        private set

    private val isUITesting = options.uiTesting

    /**
     * Amendment A41: whether the demo's other device changes a preference while Settings is open.
     * Every run but a UI test's, and the one UI test that is about it.
     */
    private val showsPreferenceChange = !isUITesting || options.demoPreferenceChange

    /**
     * Amendment A31: the oldest app build the demo gateway claims to work with. This build, so the
     * demo runs — unless `--demo-update-required` asked for a higher one.
     */
    private val demoMinimumAppVersion = if (options.demoUpdateRequired) DemoFixtures.laterAppVersion else AppBuild.version

    /** Amendment A43: whether the demo's live session opens with messages waiting behind a turn that runs on (`--demo-queue`). */
    private val demoHoldsQueue = options.demoQueue

    /**
     * Amendment A43: an edit of a queued message left open when its conversation closed, by session
     * key — in memory for the life of the app, as the web app keeps its drafts, and emptied on
     * sign-out.
     */
    private val queuedEdits = HashMap<String, QueuedEdit?>()
    private var pendingLink: SessionLink? = null

    /** Whether the landing rule has already run for this sign-in. It decides from the first device list and never again. */
    private var hasChosenLandingTab = false

    /** The demo, when the launch asked for one. [restoreOrPrompt] waits on it rather than racing it. */
    private var launch: Job? = null

    private val sessionsStack: Navigator get() = navigation.navigator(Tab.sessions)

    init {
        // Every word the app draws follows the account's language, as the iPhone's root hands its
        // `\.locale` down; the core's own words follow the same preference through its `L10n`.
        L10n.follow { settings.language.rawValue }
        if (options.resetState) {
            settings.reset()
            sessions.forgetListState()
        }
        // After the reset, so a test can launch straight into the language it is about to read:
        // `--reset-state --language=zh-Hans`. Pinned, so signing in as an account that stored
        // another language does not take the run out from under the test.
        options.language?.let { InterfaceLanguage(rawValue = it) }?.let(settings::pinLanguage)
        val entersDemo = options.demo
        // The demo is an account like any other: the app is coming back to something, so the first
        // frame belongs to the main screens.
        isResuming = entersDemo || settings.lastOrigin.isNotEmpty()
        connection.onSessionTransition = { previous, current -> announceTurn(previous, current) }
        // Amendment A35: the account's preferences and the resume banners both ride on frames no
        // single screen owns, so the model reads them for the life of the app.
        connection.addFrameHandler("preferences") { frame -> receive(frame) }
        // Drafts are the one piece of state an actor holds, so the reset of them is the first thing
        // the launch does, ahead of the demo that would otherwise open a session with a previous
        // run's words in it.
        val clearsDrafts = options.resetState
        if (entersDemo || clearsDrafts) {
            launch = tasks.launch {
                if (clearsDrafts) drafts.clearAll()
                if (entersDemo) enterDemo()
            }
        }
    }

    val isDemo: Boolean get() = connection.isDemo
    val isSignedIn: Boolean get() = connection.isSignedIn

    /**
     * Start what a person asked for — a sign-out, opening a conversation — in the app's own scope,
     * so leaving the screen that asked does not cancel it halfway, as the iPhone's unstructured
     * `Task { await model.… }` outlives its view.
     */
    fun perform(work: suspend AppModel.() -> Unit): Job = tasks.launch { work() }

    /** Amendment A44: which backend dictation uses on this gateway — the one answer the composer and Settings draw the dictation language from. */
    val voiceBackendInEffect: VoiceBackend get() = VoiceBackend.inEffect(settings = settings, connection = connection)

    /** The offline demo never constructs a transport. */
    suspend fun enterDemo() {
        // A UI test looks at the moment between a tap and the device's echo, so the scripted device
        // takes its time over it rather than being raced.
        val gateway = DemoGateway(
            echoDelay = if (isUITesting) 3.seconds else DemoGateway.defaultEchoDelay,
            resumeDelay = if (isUITesting) null else DemoGateway.defaultResumeDelay,
            minimumAppVersion = demoMinimumAppVersion,
            // Amendment A29: "Polishing…" is a state a UI test looks at rather than races.
            polishDelay = if (isUITesting) 3.seconds else DemoGateway.defaultPolishDelay,
            // Amendment A33: and so is "Checking…" on a device's page.
            agentsDelay = if (isUITesting) 3.seconds else DemoGateway.defaultAgentsDelay,
            // Amendment A41: the account's other device moves a preference while Settings is open.
            // Only the UI test about it asks for the change, and it is given time to reach the row
            // before the switch moves.
            changesPreferencesElsewhere = showsPreferenceChange,
            elsewhereDelay = if (isUITesting) 10.seconds else DemoGateway.defaultElsewhereDelay,
            holdsQueue = demoHoldsQueue,
            isolation = demoIsolation,
        )
        connection.enterDemo(api = gateway, channel = gateway)
        attachPush()
    }

    /** Launch. Nothing is drawn over the launch background until this returns one way or the other, so the form appears only where it is the answer. */
    suspend fun restoreOrPrompt() {
        launch?.join()
        if (connection.isSignedIn || settings.lastOrigin.isEmpty()) {
            isResuming = false
            attachPush()
            return
        }
        connection.restore(origin = settings.lastOrigin, username = settings.lastUsername)
        isResuming = false
        attachPush()
        applyPendingLink()
    }

    /** Frames the app itself reads: the account's preferences, and the `resume` events a notification is raised for. */
    private fun receive(frame: AppFrame) {
        preferences.receive(frame)
        preferenceSync.receive(frame)
        if (frame !is AppFrame.SessionEvent) return
        val payload = frame.event.resume ?: return
        announceResume(payload, event = frame.event, sessionID = frame.sessionID, deviceID = frame.deviceID)
    }

    /** Reconcile notifications as soon as the app has an account, so nothing waits for a visit to Settings. */
    fun attachPush() {
        preferences.attach(connection.api)
        preferenceSync.attach(connection.api)
        push.attach(api = if (connection.isDemo) null else connection.api, enabled = settings.notificationsEnabled)
    }

    suspend fun signIn(origin: String, username: String, password: String) {
        connection.signIn(origin = origin, username = username, password = password)
        adoptAccount()
    }

    /** Creating an account signs it in, so it ends exactly where a sign-in does. */
    suspend fun register(origin: String, username: String, password: String) {
        connection.register(origin = origin, username = username, password = password)
        adoptAccount()
    }

    /** The app's own settings belong to whoever just signed in, so they are re-read before any screen draws (`docs/DESIGN.md` § "Accounts"). */
    private fun adoptAccount() {
        val endpoint = connection.endpoint
        if (connection.isSignedIn && endpoint != null) settings.remember(origin = endpoint.origin, username = connection.username)
        attachPush()
        applyPendingLink()
    }

    suspend fun signOut() {
        push.detach()
        preferences.attach(null)
        preferenceSync.attach(null)
        closeChat()
        queuedEdits.clear()
        path = emptyList()
        hasChosenLandingTab = false
        // The account's drafts go with its cached transcripts and its token. Before the connection
        // is torn down, not after: signing out empties the user, so `account` would name nobody.
        drafts.clear(account = connection.account)
        connection.signOut()
    }

    /**
     * A `hello` snapshot is the whole list of what this account has, so a draft key it does not name
     * belongs to a session that has been deleted on the device. Not for the demo: its scripted device
     * resumes and archives sessions as the run goes on, and nothing it writes is meant to outlive it.
     */
    suspend fun adoptSnapshot() {
        if (!connection.hasSnapshot || connection.isDemo) return
        drafts.retain(connection.sessions.map { it.id }.toSet(), account = connection.account)
    }

    /**
     * `docs/DESIGN.md` § "Three tabs, one order, one landing rule": Sessions when the account has at
     * least one device, Devices when it has none. Nothing is decided until the first device list
     * has arrived, and nothing is decided twice.
     */
    fun decideLandingTab() {
        if (hasChosenLandingTab || !connection.hasSnapshot) return
        hasChosenLandingTab = true
        tab = landingTab(hasDevices = connection.devices.isNotEmpty())
    }

    fun lockIfNeeded() {
        if (!settings.appLockEnabled || !connection.isSignedIn || connection.isDemo) return
        isLocked = true
    }

    // Conversations

    /**
     * Open a session: cached transcript first, then subscribe. [inPlace] is what a notification or
     * a link asks for: the conversation already open is replaced rather than stacked on.
     */
    suspend fun open(session: Session, inPlace: Boolean = false) {
        closeChat()
        val channel = connection.channel ?: return
        val store = ChatStore(session = session, channel = channel, tasks = tasks)
        store.agent = agent(session)
        // The preference stays in one place. The transcript reads it, so changing it in Settings
        // redraws an open conversation at once.
        store.detailSource = { settings.timelineDetail }
        store.draft = drafts.draft(account = connection.account, key = session.id)
        store.resumeEdit(queuedEdits[session.id])
        chat = store
        connection.addFrameHandler("chat") { frame -> store.receive(frame) }
        route(session.id, inPlace)
        val cached = connection.cachedTranscript(sessionID = session.sessionID, deviceID = session.deviceID)
        store.open(cached)
    }

    /**
     * Where the navigation stack goes. `docs/DESIGN.md` § "Status vocabulary" → a notification opens
     * its session in place: the stack holds one conversation, so Back from a session a notification
     * opened returns to the list rather than to the session it replaced.
     */
    private fun route(key: String, inPlace: Boolean) {
        if (path.lastOrNull() == key) return
        if (inPlace) path = listOf(key) else sessionsStack.push(key)
    }

    suspend fun closeChat() {
        val store = chat ?: return
        connection.removeFrameHandler("chat")
        queuedEdits[store.key] = store.queuedEdit
        drafts.setDraft(store.draft, account = connection.account, key = store.key)
        connection.persist(transcript = store.timeline.entries.mapNotNull { it.sourceEvent },
                           sessionID = store.sessionID, deviceID = store.deviceID)
        store.close()
        chat = null
    }

    /**
     * Close one named conversation and no other. A conversation leaving the screen is told to close
     * after its replacement may already be installed; the key keeps that late call from closing the
     * store it never owned.
     */
    suspend fun closeChat(key: String) {
        if (chat?.key != key) return
        closeChat()
    }

    suspend fun saveDraft() {
        val store = chat ?: return
        drafts.setDraft(store.draft, account = connection.account, key = store.key)
    }

    /** Called when the app leaves the foreground: Android may reclaim the process without another chance to write anything. */
    suspend fun persistForBackground() {
        val store = chat
        if (store == null) {
            connection.persistInventory()
            return
        }
        drafts.setDraft(store.draft, account = connection.account, key = store.key)
        connection.persist(transcript = store.timeline.entries.mapNotNull { it.sourceEvent },
                           sessionID = store.sessionID, deviceID = store.deviceID)
    }

    // Links and notifications

    /** A deep link or a notification tap. Authentication is re-validated before anything is shown, so a stale notification cannot open someone's session. */
    fun handle(link: SessionLink) {
        if (!connection.isSignedIn || !connection.hasSnapshot) {
            pendingLink = link
            return
        }
        val session = connection.session(deviceID = link.deviceID, sessionID = link.sessionID)
        if (session == null) {
            toast = L10n.string("That session is no longer on this gateway.")
            return
        }
        // A link is a destination, so it settles the landing rule too: the first device list must
        // not move the tab out from under it.
        hasChosenLandingTab = true
        tab = Tab.sessions
        tasks.launch { open(session, inPlace = true) }
    }

    fun handle(url: URI) {
        handle(SessionLink(url = url) ?: return)
    }

    fun applyPendingLink() {
        val link = pendingLink ?: return
        if (!connection.hasSnapshot) return
        pendingLink = null
        handle(link)
    }

    /** The foreground, which both the awake screen and the app's own notifications are conditioned on. */
    // The JVM name of [isSceneActive]'s own setter is this one's, so this one is renamed for the JVM alone.
    @JvmName("applySceneActive")
    fun setSceneActive(active: Boolean) {
        isSceneActive = active
    }

    /**
     * Amendment A35: a session pausing, resuming or being dropped is news of exactly the kind a
     * finished turn is, so it raises the same notification under the same gates. `rescheduled` and
     * `cancelled` say nothing.
     */
    private fun announceResume(payload: ResumePayload, event: SessionEvent, sessionID: String, deviceID: String?) {
        val kind = TurnAlerts.kind(resume = payload.status) ?: return
        val session = connection.sessions.firstOrNull { it.sessionID == sessionID && (deviceID == null || it.deviceID == deviceID) } ?: return
        turns.announce(
            kind = kind,
            session = session,
            deviceName = device(session)?.name ?: session.deviceID,
            identifier = "resume/${session.id}/${event.seq}",
            onScreen = isOnScreen(session),
            enabled = settings.notificationsEnabled,
            authorization = push.authorization,
        )
    }

    private fun announceTurn(previous: Session, current: Session) {
        turns.announce(
            previous = previous,
            current = current,
            deviceName = device(current)?.name ?: current.deviceID,
            onScreen = isOnScreen(current),
            enabled = settings.notificationsEnabled,
            authorization = push.authorization,
        )
    }

    /** `docs/DESIGN.md` § "The Android app": a notification is skipped while its conversation is on screen. */
    private fun isOnScreen(session: Session): Boolean = isSceneActive && chat?.key == session.id

    // Convenience for the screens

    /** Resolve a path key back to a session: the live one when the gateway still lists it, otherwise the copy the open chat is holding. */
    fun session(key: String): Session? =
        connection.sessions.firstOrNull { it.id == key } ?: chat?.takeIf { it.key == key }?.session

    fun device(session: Session): Device? = connection.device(session.deviceID)

    fun agent(session: Session): AgentInfo? = connection.device(session.deviceID)?.agent(session.agent)

    fun pairingFlow(): PairingFlow? = connection.api?.let(::PairingFlow)

    fun deviceUpdateError(deviceID: String): String? = deviceUpdateErrors[deviceID]

    /**
     * Amendment A22: ask a device to fetch the build the gateway serves. The accepted case says
     * nothing here — `device.updated` carries the state the row draws from then on.
     */
    suspend fun updateDevice(device: Device) {
        val channel = connection.channel ?: return
        val build = connection.config.servedBuild ?: return
        deviceUpdateErrors = deviceUpdateErrors - device.deviceID
        try {
            channel.request(GatewayRequest.updateDevice(deviceID = device.deviceID, build = build), DeviceUpdateResult.serializer())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            deviceUpdateErrors = deviceUpdateErrors + (device.deviceID to connection.message(failure))
        }
    }

    /**
     * The camera the pairing scanner runs on. The demo has none to reach, so it takes the stand-in
     * that hands over a printed payload (A23). Read in the screen that scans, because the camera's
     * permission is asked through it.
     */
    val codeScanner: CodeScanning
        @Composable get() = if (isDemo) remember { StaticCodeScanner(payload = DemoFixtures.claimURL) } else SystemCodeScanner.make()

    val accessibilityMode: Boolean get() = isUITesting

    companion object {
        /** The rule itself, with nothing around it. */
        fun landingTab(hasDevices: Boolean): Tab = if (hasDevices) Tab.sessions else Tab.devices
    }
}
