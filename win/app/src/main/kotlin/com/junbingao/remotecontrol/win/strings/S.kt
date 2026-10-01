package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.win.standin.InterfaceLanguage
import com.junbingao.remotecontrol.win.standin.TimelineDetail

/**
 * The app's own words: `S.<group>.<key>`, read through the interface language every time, as the
 * web's `strings` proxy reads its table.
 *
 * Two things follow, as they do on the web (`docs/WEB.md` § "Language"). A composable reading `S`
 * redraws when the language changes, because the read goes through snapshot state. And **nothing
 * may read a string at static-init time**: a table built in a top-level `val` keeps the language
 * the app launched in. Build such lists inside a function or a getter.
 *
 * Only the app's own words are here. Everything a device reported — agent output, device names,
 * paths, branches, model and permission labels — is drawn as it arrived.
 */
object S {
    /** Never translated: both of the web's tables spell it the same way. */
    const val productName = "Remote Control"

    private val language: InterfaceLanguage get() = InterfaceLanguageSource.current

    val nav: NavStrings get() = NavStrings.of(language)
    val common: CommonStrings get() = CommonStrings.of(language)
    val account: AccountStrings get() = AccountStrings.of(language)
    val login: LoginStrings get() = LoginStrings.of(language)
    val users: UsersStrings get() = UsersStrings.of(language)
    val devices: DevicesStrings get() = DevicesStrings.of(language)
    val terminal: TerminalStrings get() = TerminalStrings.of(language)
    val devicePage: DevicePageStrings get() = DevicePageStrings.of(language)
    val pairing: PairingStrings get() = PairingStrings.of(language)
    val sessions: SessionsStrings get() = SessionsStrings.of(language)
    val newSession: NewSessionStrings get() = NewSessionStrings.of(language)
    val chat: ChatStrings get() = ChatStrings.of(language)
    val status: StatusStrings get() = StatusStrings.of(language)
    val composer: ComposerStrings get() = ComposerStrings.of(language)
    val commands: CommandsStrings get() = CommandsStrings.of(language)
    val voice: VoiceStrings get() = VoiceStrings.of(language)
    val settings: SettingsStrings get() = SettingsStrings.of(language)
    val connection: ConnectionStrings get() = ConnectionStrings.of(language)
    val errors: ErrorsStrings get() = ErrorsStrings.of(language)
    val a11y: A11yStrings get() = A11yStrings.of(language)
    val labels: LabelsStrings get() = LabelsStrings.of(language)
    val format: FormatStrings get() = FormatStrings.of(language)

    /** The Windows app's own words, read like every other group. */
    val win: WinStrings get() = WinStrings.of(language)

    /** The composer's Windows-only words. */
    val winComposer: WinComposerStrings get() = WinComposerStrings.of(language)

    /** The settings feature's Windows-only words. */
    val winSettings: WinSettingsStrings get() = WinSettingsStrings.of(language)

    // The helpers at the foot of `web/src/strings.ts`: the names the app prints for ids a device
    // or the gateway sent, each falling back to the id itself.

    /** Product names, never translated. A25 added the last two. */
    val agentLabels: Map<String, String> = mapOf(
        "claude" to "Claude Code",
        "codex" to "Codex",
        "grok" to "Grok Build",
        "pi" to "pi",
    )

    fun agentLabel(agent: String): String = agentLabels[agent] ?: agent

    /**
     * The platforms a device reports, written the way their makers write them. The device row
     * says the word, never the raw id (`docs/DESIGN.md` § "The device row"); a platform this table
     * does not know is printed as the device sent it.
     */
    val platformLabels: Map<String, String> = mapOf(
        "macos" to "macOS",
        "linux" to "Linux",
    )

    fun platformLabel(platform: String): String = platformLabels[platform] ?: platform

    /**
     * A33: the vendors an `AgentAccount.provider` can name, as they write themselves. Never
     * translated, and never extended with agent ids — a provider this table does not know is
     * printed as the device reported it.
     */
    val vendorLabels: Map<String, String> = mapOf(
        "anthropic" to "Anthropic",
        "openai" to "OpenAI",
        "xai" to "xAI",
    )

    fun vendorLabel(provider: String): String = vendorLabels[provider] ?: provider

    /** The two interface languages, each written in its own script. */
    fun interfaceLanguageLabel(language: InterfaceLanguage): String = when (language) {
        InterfaceLanguage.en -> "English"
        InterfaceLanguage.zhHans -> "中文"
    }

    fun stateLabel(state: String): String = labels.state[state] ?: state

    fun dotToneLabel(tone: String): String = labels.dotTone[tone] ?: tone

    fun timelineDetailLabel(detail: TimelineDetail): String = labels.timelineDetail[detail]

    /** A24: "Admin" or "Member", and the id itself for a role this app is too old for. */
    fun roleLabel(role: String): String = labels.role[role] ?: role

    /** A24: "Active" or "Disabled". */
    fun userStateLabel(state: String): String = labels.userState[state] ?: state

    /**
     * Row label for a session, from its `origin`: where it came from, never what it is doing
     * (`docs/DESIGN.md` § "The session row says where it came from"). The state is the dot's
     * colour alone, so a row never says the same thing twice.
     */
    fun sessionOriginLabel(origin: String): String = labels.origin[origin] ?: origin

    /**
     * The name every surface prints for a session, from its `title`. A thread the agent has not
     * named yet arrives with an empty title; the row, the chat header and the sidebar all fall
     * back to the same words in the title's own type, rather than leaving a blank line above the
     * meta (`docs/DESIGN.md` § "Session lists"). The session search reads the same value, so an
     * untitled row is found by those words too.
     */
    fun sessionTitle(title: String): String = title.trim().ifEmpty { sessions.untitled }
}

/** The two timeline detail levels, in the order Settings offers them. */
class TimelineDetailLabels(val simple: String, val detailed: String) {
    operator fun get(detail: TimelineDetail): String = when (detail) {
        TimelineDetail.simple -> simple
        TimelineDetail.detailed -> detailed
    }
}
