package com.junbingao.remotecontrol.win.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.persistence.DraftStore
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.state.ConnectionPhase
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.PreferenceSync
import com.junbingao.remotecontrol.core.state.PreferencesStore
import com.junbingao.remotecontrol.core.state.SessionStore
import com.junbingao.remotecontrol.core.state.SettingsStore
import com.junbingao.remotecontrol.win.platform.InertToasts
import com.junbingao.remotecontrol.win.platform.Toasts
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The object every screen reads, as `LocalAppModel.current` — the Mac's `MacAppModel`. It owns the
 * core's stores — the connection, the list state, the settings and the account's preferences, the
 * drafts — the router, and what else the web's global stores hold that the core models; a
 * conversation, a terminal and an accounts list belong to the page that shows them.
 *
 * Built once, by the app (or the renderer, once per scenario), and never again: its initialiser
 * applies the launch arguments, and `--reset-state` applied twice would throw away the account the
 * first run had just signed in. The work it starts runs in `tasks`, the window's main thread in the
 * app and the renderer's own in a render, where the stores' snapshot state is written.
 */
class WinAppModel(val options: LaunchOptions, val tasks: CoroutineScope) : ShellState {
    internal val persistence = Persistence(ephemeral = options.ephemeral)
    internal val recorder = SignInRecorder()
    val connection: ConnectionStore = ConnectionFactory.make(options, persistence, recorder, tasks)

    /** Filters, search and the folded groups of the session lists. */
    val sessions = SessionStore(defaults = persistence.defaults)
    val settings = SettingsStore(defaults = persistence.defaults)

    /** Amendment A35: the account's resume switch, seeded from `hello`. */
    val preferences = PreferencesStore()

    /** Amendment A41: keeps `settings` equal to the account's preferences, both ways. */
    val preferenceSync = PreferenceSync(settings = settings, tasks = tasks)

    /** Composer drafts, per account and session, on disk. */
    val drafts: DraftStore = persistence.drafts
    override val router = Router()

    /**
     * Whether there is an account to come back to, from launch until the vault has answered. The
     * root draws the canvas and nothing else while it holds, as the web draws `.boot` while its
     * session check runs.
     */
    var isResuming: Boolean by mutableStateOf(options.demo || settings.lastOrigin.isNotEmpty())
        private set

    /**
     * Amendment A22: why a device refused the last update it was asked for. A refusal means
     * nothing started, so no `device.updated` will ever carry it and the app is the only place it
     * can live (the web's `updateErrors`).
     */
    var deviceUpdateErrors: Map<String, String> by mutableStateOf(emptyMap())
        internal set

    /** Whether the main window is on screen and focused, which is when a conversation shown in it counts as read. */
    var isWindowActive: Boolean by mutableStateOf(false)
        internal set

    internal val signOutHandlers = mutableListOf<suspend () -> Unit>()
    internal var isSigningOut = false
    internal var wasSignedIn = false
    private val transitionHandlers = mutableListOf<(Session, Session) -> Unit>()

    /** Brings the main window back; the app installs it, a renderer has none. */
    var showWindow: () -> Unit = {}

    /**
     * Where the app's notifications go: Windows' toasts from the notification area in the app
     * (`TrayToasts`), kept in the process anywhere else (`InertToasts`). A click on one brings the
     * window back on its conversation.
     */
    var toasts: Toasts = InertToasts()
        set(value) {
            field = value
            openConversations(value)
        }

    /** The demo, when the launch arguments asked for one. `restoreOrPrompt` waits on it rather than racing it. */
    private var launch: Job? = null
    private var hasLaunched = false

    init {
        if (options.resetState) {
            settings.reset()
            sessions.forgetListState()
        }
        // After the reset, so a run can start straight in the language it reads:
        // `--reset-state --language=zh-Hans`.
        options.language?.let(settings::pinLanguage)
        router.canSeeUsers = { connection.isAdmin }
        wireConnection()
        openConversations(toasts)
        followLanguage()
        followSignedIn()
        if (options.demo || options.resetState) {
            launch = tasks.launch {
                if (options.resetState) drafts.clearAll()
                if (options.demo) enterDemo()
            }
        }
        Features.install(on = this)
    }

    val isSignedIn: Boolean get() = connection.isSignedIn
    val isDemo: Boolean get() = connection.isDemo

    /** The account drafts and caches are filed under. */
    val account: String get() = connection.account

    /**
     * The gateway as the topbar and Settings print it: its public origin when `/api/config` has
     * said, the address signed in to until then.
     */
    override val origin: String
        get() = connection.config.publicOrigin.ifEmpty { connection.endpoint?.origin ?: settings.lastOrigin }

    override val username: String get() = connection.username

    /** The web's `status === 'open'`: the socket is up, whether or not its `hello` has landed yet. */
    override val connectionIsOpen: Boolean
        get() = connection.phase == ConnectionPhase.Syncing || connection.phase == ConnectionPhase.Connected

    override val hasSnapshot: Boolean get() = connection.hasSnapshot
    override val hasDevices: Boolean get() = connection.devices.isNotEmpty()

    // Extension points

    /**
     * Called on every sign-out, in the order registered, before the connection goes: a
     * conversation can still write its draft and its transcript under the account that is leaving.
     * Everything a feature keeps of an account's is emptied here (`web/src/stores/signOut.ts`).
     */
    fun onSignOut(handler: suspend () -> Unit) {
        signOutHandlers += handler
    }

    /**
     * Called with both versions whenever a session the app already knew is replaced by a newer one
     * — the moments a turn ends, a question arrives, a limit pauses it.
     */
    fun onSessionTransition(handler: (Session, Session) -> Unit) {
        transitionHandlers += handler
    }

    // Wiring

    private fun wireConnection() {
        connection.onSessionTransition = { previous, current ->
            for (handler in transitionHandlers) handler(previous, current)
        }
        // Amendment A35/A41: the account's preferences ride on frames no single screen owns, so
        // the model reads them for the life of the app.
        connection.addFrameHandler("preferences") { frame ->
            preferences.receive(frame)
            preferenceSync.receive(frame)
            forgetUpdateError(frame)
        }
    }

    /**
     * Every string the app draws follows the signed-in account's language (A41 makes it the
     * account's): the source `S` reads is moved each time it moves. Nobody is signed in on the
     * login page, which the web draws in English whatever the last account chose, because its
     * settings are kept under a key of their own that no control writes — unless a launch argument
     * fixed the language for the run.
     */
    private fun followLanguage() {
        InterfaceLanguageSource.current = interfaceLanguage()
        tasks.launch {
            snapshotFlow { interfaceLanguage() }.collect { InterfaceLanguageSource.current = it }
        }
    }

    private fun interfaceLanguage(): InterfaceLanguage =
        if (connection.isSignedIn) settings.language else options.language ?: InterfaceLanguage.en

    private fun openConversations(toasts: Toasts) {
        toasts.onOpen = { target ->
            showWindow()
            router.go(Route.Chat(deviceId = target.deviceId, sessionId = target.sessionId))
        }
    }

    /** The gateway's own `update_state` speaks for a device again once it sends that device. */
    private fun forgetUpdateError(frame: AppFrame) {
        if (frame is AppFrame.DeviceUpdated) deviceUpdateErrors = deviceUpdateErrors - frame.device.deviceID
    }

    /** The demo gateway, entered without a form. It never constructs a transport. */
    internal suspend fun enterDemo() {
        val minimum = if (options.demoUpdateRequired) DemoFixtures.laterAppVersion else AppBuild.version
        val gateway = DemoGateway(minimumAppVersion = minimum)
        connection.enterDemo(api = gateway, channel = gateway)
        attachAccount()
    }

    /**
     * Bind the account's preference stores to the connection's client.
     *
     * `stt_language` is the language the iPhone's own recogniser listens for (A44); the web never
     * writes it, and Windows has no recogniser the app uses. So the app listens for none: an empty
     * dictation language, which `PreferenceSync` never writes up — it writes only a language a
     * recogniser listens for.
     */
    internal fun attachAccount() {
        settings.voiceLanguage = ""
        preferences.attach(api = connection.api)
        preferenceSync.attach(api = connection.api)
    }

    /** Launch: the demo, a stored token, or the form — and nothing drawn over the canvas until one of them has answered. */
    suspend fun restoreOrPrompt() {
        // Once per launch: reopening the window after closing it is no launch.
        if (hasLaunched) return
        hasLaunched = true
        launch?.join()
        try {
            if (connection.isSignedIn || settings.lastOrigin.isEmpty()) {
                if (connection.isSignedIn) router.signedIn()
                return
            }
            if (connection.restore(origin = settings.lastOrigin, username = settings.lastUsername)) {
                attachAccount()
                router.signedIn()
            }
        } finally {
            isResuming = false
        }
    }

    /** The quit of an ephemeral run takes what it wrote with it. */
    fun discardEphemeralState() = persistence.discard()
}

/** The app model of the window a screen is drawn in; the root provides it. */
val LocalAppModel = staticCompositionLocalOf<WinAppModel> { error("No WinAppModel: the root provides it") }
