package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.persistence.LocalCache
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.transport.PolishStrength

/**
 * Preferences that outlive one connection. Everything here is a plain value in [UserDefaults]; the
 * bearer token lives in the secret store instead.
 *
 * `docs/DESIGN.md` § "Accounts": the app's own settings belong to the person signed in, not to the
 * app. Every preference below is stored under a key that carries the gateway origin and the
 * username, so two people who share one phone find their own language, dictation language and
 * notification choices. The gateway address is the exception and stays global: it is how the form
 * is prefilled and there is nobody to scope it to until someone has signed in. The username is kept
 * per gateway, so alternating between two of them prefills each with the account that was used
 * there.
 *
 * Amendment A41: six of them are the account's and not this phone's — the interface language, the
 * dictation language, polish with its model and its strength, and the timeline detail. They are
 * still read and written here, because every screen reads a preference from one place, but what is
 * here is a cache of the gateway's copy: [PreferenceSync] applies what arrives and writes up what
 * the person changes, and is told about a change through [onAccountPreferenceChange]. The rest —
 * notifications, the app lock, the transcription backend, the terminal font size — belongs to the
 * device and stays here alone.
 */
class SettingsStore(private val defaults: UserDefaults) {
    private object Key {
        const val gateway = "gateway."
        const val origin = "gateway.origin"
        const val username = "gateway.username"
        const val prefix = "preference."
        const val notifications = "preference.notifications"
        const val appLock = "preference.appLock"
        const val voiceBackend = "preference.voiceBackend"
        const val voiceLanguage = "preference.voiceLanguage"
        const val polishEnabled = "preference.polishEnabled"
        const val polishModel = "preference.polishModel"
        const val polishStrength = "preference.polishStrength"
        const val timelineDetail = "preference.timelineDetail"
        const val language = "preference.language"
        const val terminalFontSize = "preference.terminalFontSize"
    }

    /** Whose preferences are being read and written: `<origin>|<username>`, or nothing at all before anyone has signed in on this install. */
    var scope: String = ""
        private set

    /** A launch argument fixes the language for the whole run, so a test reads the app in the language it asked for whichever account signs in. */
    private var pinnedLanguage: InterfaceLanguage? = null

    /** Amendment A41: called whenever one of the six preferences the account owns is changed on this phone, so [PreferenceSync] can write it up. */
    var onAccountPreferenceChange: (() -> Unit)? = null

    /** True while the store is re-reading one account's stored values into itself. A change nobody made is not a change to write up. */
    private var isAdopting = false

    var lastOrigin: String by observedValue(defaults.string(forKey = Key.origin) ?: "") {
        defaults.set(it, forKey = Key.origin)
    }
    var notificationsEnabled: Boolean by observedValue(false) { write(it, Key.notifications) }
    var appLockEnabled: Boolean by observedValue(false) { write(it, Key.appLock) }
    var voiceBackend: VoiceBackend by observedValue(VoiceBackend.onDevice) { write(it.rawValue, Key.voiceBackend) }

    /**
     * The account's `stt_language`: the language this phone's own recogniser listens for (A44),
     * Chinese on a new install. A value it does not listen for — an `auto` written before A44 — is
     * kept as it arrived and read as Chinese through [dictationLanguage], so reading it writes
     * nothing.
     */
    var voiceLanguage: String by observedValue(DictationLanguage.standard) {
        write(it, Key.voiceLanguage)
        accountPreferenceChanged()
    }

    /**
     * Amendment A29: whether a finished dictation is passed through the gateway's polish model. Off
     * by default — nothing leaves the phone for a model until the person asks for it.
     */
    var polishEnabled: Boolean by observedValue(false) {
        write(it, Key.polishEnabled)
        accountPreferenceChanged()
    }

    /** Which of the provider's models does the polishing. Empty until one is chosen, which is when the feature can take effect. */
    var polishModel: String by observedValue("") {
        write(it, Key.polishModel)
        accountPreferenceChanged()
    }
    var polishStrength: PolishStrength by observedValue(PolishStrength.moderate) {
        write(it.rawValue, Key.polishStrength)
        accountPreferenceChanged()
    }

    /** How much of a transcript is drawn. Simple is the default: most of what an agent does is not addressed to the reader. */
    var timelineDetail: TimelineDetail by observedValue(TimelineDetail.simple) {
        write(it.rawValue, Key.timelineDetail)
        accountPreferenceChanged()
    }

    /**
     * Amendment A38: how large the terminal's type is, in points. A pinch on the terminal changes it
     * and the next terminal opens at the same size — a reading preference, kept on this phone like
     * the timeline detail.
     */
    var terminalFontSize: Double by observedValue(TerminalTypeSize.standard) { write(it, Key.terminalFontSize) }

    /**
     * Which language the app writes its own words in. Changing it moves the table every string built
     * outside a screen is looked up in, so the whole app follows the next time it draws, which is at
     * once.
     */
    var language: InterfaceLanguage by observedValue(InterfaceLanguage.en) {
        write(it.rawValue, Key.language)
        L10n.use(it)
        accountPreferenceChanged()
    }

    init {
        val origin = lastOrigin
        val username = defaults.string(forKey = "${Key.username}@$origin") ?: ""
        // The account the app is about to come back to owns the preferences it reads on launch, so
        // the first screen is already in their language.
        scope = scope(origin = origin, username = username)
        readScopedValues()
        L10n.use(language)
    }

    // Who the form is prefilled with

    /** The account that last signed in on one gateway, which is what the form offers when that gateway is typed. Empty where nobody has. */
    fun username(origin: String): String =
        if (origin.isEmpty()) "" else defaults.string(forKey = "${Key.username}@$origin") ?: ""

    /** The account on the gateway the app comes back to, which is the one the stored token is filed under. */
    val lastUsername: String get() = username(lastOrigin)

    // Scoping

    private fun key(name: String): String = if (scope.isEmpty()) name else "$name@$scope"

    private fun write(value: String, name: String) = defaults.set(value, forKey = key(name))

    private fun write(value: Boolean, name: String) = defaults.set(value, forKey = key(name))

    private fun write(value: Double, name: String) = defaults.set(value, forKey = key(name))

    /**
     * Amendment A41: one of the account's six was changed here. Re-reading a scope is not such a
     * change, and neither is the value [PreferenceSync] just applied — that one is reported and
     * found equal, which writes nothing.
     */
    private fun accountPreferenceChanged() {
        if (isAdopting) return
        onAccountPreferenceChange?.invoke()
    }

    /** Read every preference from the current scope, falling back to the value a fresh install has. A launch-pinned language wins over what was stored. */
    private fun readScopedValues() {
        isAdopting = true
        try {
            notificationsEnabled = defaults.bool(forKey = key(Key.notifications))
            appLockEnabled = defaults.bool(forKey = key(Key.appLock))
            voiceBackend = VoiceBackend(rawValue = defaults.string(forKey = key(Key.voiceBackend)) ?: "")
                ?: VoiceBackend.onDevice
            voiceLanguage = defaults.string(forKey = key(Key.voiceLanguage)) ?: DictationLanguage.standard
            polishEnabled = defaults.bool(forKey = key(Key.polishEnabled))
            polishModel = defaults.string(forKey = key(Key.polishModel)) ?: ""
            polishStrength = PolishStrength(rawValue = defaults.string(forKey = key(Key.polishStrength)) ?: "")
                ?: PolishStrength.moderate
            timelineDetail = TimelineDetail(rawValue = defaults.string(forKey = key(Key.timelineDetail)) ?: "")
                ?: TimelineDetail.simple
            // A stored zero is a fresh install, not a request for invisible type.
            val storedTypeSize = defaults.double(forKey = key(Key.terminalFontSize))
            terminalFontSize = if (storedTypeSize > 0) TerminalTypeSize.clamp(storedTypeSize) else TerminalTypeSize.standard
            // English whatever the phone is set to: the default is the product's own language and
            // not a guess from the system's preferred languages.
            language = pinnedLanguage
                ?: InterfaceLanguage(rawValue = defaults.string(forKey = key(Key.language)) ?: "")
                ?: InterfaceLanguage.en
        } finally {
            isAdopting = false
        }
    }

    /**
     * Point the preferences at one account. Called on every sign-in, including the restore on
     * launch, so signing in as someone else changes what the app remembers rather than inheriting
     * the last person's choices.
     */
    fun adopt(origin: String, username: String) {
        val next = scope(origin = origin, username = username)
        if (next == scope) return
        scope = next
        readScopedValues()
    }

    /** Fix the interface language for this run, whatever any account stored. */
    fun pinLanguage(value: InterfaceLanguage) {
        pinnedLanguage = value
        language = value
    }

    /** Start as a fresh install: every account's preferences, not only the current one's, so a run never inherits the shape an earlier run left. */
    fun reset() {
        for (name in defaults.keys) {
            if (name.startsWith(Key.prefix) || name.startsWith(Key.gateway)) defaults.removeObject(forKey = name)
        }
        lastOrigin = ""
        scope = ""
        readScopedValues()
    }

    /** Amendment A44: the language the phone's recogniser listens for, which is the stored one where it is one of the recogniser's and Chinese otherwise. */
    val dictationLanguage: String get() = DictationLanguage.effective(voiceLanguage)

    /** The locale handed to the platform recogniser for that language. */
    val speechLocaleIdentifier: String get() = DictationLanguage.localeIdentifier(dictationLanguage)

    /** Remember who signed in where, and read their preferences. */
    fun remember(origin: String, username: String) {
        lastOrigin = origin
        if (origin.isNotEmpty()) defaults.set(username, forKey = "${Key.username}@$origin")
        adopt(origin = origin, username = username)
    }

    /**
     * A diagnostic report built from an explicit allowlist.
     *
     * Never serialize a store and redact afterwards: this function names every field it emits, so
     * nothing new can leak by being added elsewhere. The report names the app that wrote it, which
     * this store does not know, so the caller says which it is.
     */
    fun diagnosticReport(app: InstalledApp, appVersion: String, platform: String, osVersion: String,
                         phase: ConnectionPhase, deviceCount: Int, sessionCount: Int, sttEnabled: Boolean,
                         isDemo: Boolean): String {
        val connection = when (phase) {
            ConnectionPhase.SignedOut -> "signed out"
            ConnectionPhase.Connecting -> "connecting"
            ConnectionPhase.Syncing -> "syncing"
            ConnectionPhase.Connected -> "connected"
            ConnectionPhase.Reconnecting -> "reconnecting"
            ConnectionPhase.Expired -> "session expired"
            ConnectionPhase.Forbidden -> "refused by the gateway"
            ConnectionPhase.Superseded -> "replaced by another connection"
            is ConnectionPhase.Incompatible -> "protocol mismatch"
        }
        return """
            Remote Control for ${app.platformName} — diagnostic snapshot
            App: $appVersion
            Platform: $platform
            OS: $osVersion
            Protocol: v${RemoteProtocol.version}
            Cache schema: v${LocalCache.schemaVersion}
            Mode: ${if (isDemo) "offline demo" else "gateway"}
            Connection: $connection
            Devices known: $deviceCount
            Sessions known: $sessionCount
            Gateway transcription: ${if (sttEnabled) "available" else "unavailable"}
            Voice backend: ${voiceBackend.rawValue}
            Dictation polish: ${if (polishEnabled) "on (${polishStrength.rawValue})" else "off"}
            Timeline detail: ${timelineDetail.rawValue}
            Interface language: ${language.rawValue}
            Notifications: ${if (notificationsEnabled) "on" else "off"}
            App lock: ${if (appLockEnabled) "on" else "off"}

            Excludes the gateway address, account, device and session identifiers,
            credentials, message text, file paths, attachments and raw error details.
        """.trimIndent()
    }

    private companion object {
        fun scope(origin: String, username: String): String =
            if (origin.isEmpty() && username.isEmpty()) "" else "$origin|$username"

        /** The platform an app is written for, as the report's first line names it. */
        val InstalledApp.platformName: String
            get() = when (this) {
                InstalledApp.ios -> "iOS"
                InstalledApp.macos -> "macOS"
                InstalledApp.android -> "Android"
                InstalledApp.windows -> "Windows"
            }
    }
}

/** The same answer as `VoiceBackend.inEffect(chosen, gatewayTranscribes)`, for this phone's choice on the gateway it is signed in to. */
fun VoiceBackend.Companion.inEffect(settings: SettingsStore, connection: ConnectionStore): VoiceBackend =
    inEffect(chosen = settings.voiceBackend, gatewayTranscribes = connection.stt.enabled)
