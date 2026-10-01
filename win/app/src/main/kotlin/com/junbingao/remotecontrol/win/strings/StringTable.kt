// Generated beside the group files from web/src/strings.ts: one table per interface language,
// holding every group. `S` reads the groups one at a time through the current language.

package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.state.InterfaceLanguage

/** Every group of the app's own words in one language. */
class StringTable(
    val nav: NavStrings,
    val common: CommonStrings,
    val account: AccountStrings,
    val login: LoginStrings,
    val users: UsersStrings,
    val devices: DevicesStrings,
    val terminal: TerminalStrings,
    val devicePage: DevicePageStrings,
    val pairing: PairingStrings,
    val sessions: SessionsStrings,
    val newSession: NewSessionStrings,
    val chat: ChatStrings,
    val status: StatusStrings,
    val composer: ComposerStrings,
    val commands: CommandsStrings,
    val voice: VoiceStrings,
    val settings: SettingsStrings,
    val connection: ConnectionStrings,
    val errors: ErrorsStrings,
    val a11y: A11yStrings,
    val labels: LabelsStrings,
    val format: FormatStrings,
) {
    companion object {
        val en = StringTable(
            nav = NavStrings.en,
            common = CommonStrings.en,
            account = AccountStrings.en,
            login = LoginStrings.en,
            users = UsersStrings.en,
            devices = DevicesStrings.en,
            terminal = TerminalStrings.en,
            devicePage = DevicePageStrings.en,
            pairing = PairingStrings.en,
            sessions = SessionsStrings.en,
            newSession = NewSessionStrings.en,
            chat = ChatStrings.en,
            status = StatusStrings.en,
            composer = ComposerStrings.en,
            commands = CommandsStrings.en,
            voice = VoiceStrings.en,
            settings = SettingsStrings.en,
            connection = ConnectionStrings.en,
            errors = ErrorsStrings.en,
            a11y = A11yStrings.en,
            labels = LabelsStrings.en,
            format = FormatStrings.en,
        )

        val zhHans = StringTable(
            nav = NavStrings.zhHans,
            common = CommonStrings.zhHans,
            account = AccountStrings.zhHans,
            login = LoginStrings.zhHans,
            users = UsersStrings.zhHans,
            devices = DevicesStrings.zhHans,
            terminal = TerminalStrings.zhHans,
            devicePage = DevicePageStrings.zhHans,
            pairing = PairingStrings.zhHans,
            sessions = SessionsStrings.zhHans,
            newSession = NewSessionStrings.zhHans,
            chat = ChatStrings.zhHans,
            status = StatusStrings.zhHans,
            composer = ComposerStrings.zhHans,
            commands = CommandsStrings.zhHans,
            voice = VoiceStrings.zhHans,
            settings = SettingsStrings.zhHans,
            connection = ConnectionStrings.zhHans,
            errors = ErrorsStrings.zhHans,
            a11y = A11yStrings.zhHans,
            labels = LabelsStrings.zhHans,
            format = FormatStrings.zhHans,
        )

        /** The table an interface language reads. */
        fun of(language: InterfaceLanguage): StringTable = when (language) {
            InterfaceLanguage.en -> en
            InterfaceLanguage.zhHans -> zhHans
        }
    }
}
