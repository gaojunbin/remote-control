// Generated beside the group files from web/src/strings.ts: one table per
// interface language, holding every group, and `S`'s accessors over them.

import Foundation
import RCCore

/// Every group of the app's own words in one language.
public struct StringTable: Sendable {
    public let nav: NavStrings
    public let common: CommonStrings
    public let account: AccountStrings
    public let login: LoginStrings
    public let users: UsersStrings
    public let devices: DevicesStrings
    public let terminal: TerminalStrings
    public let devicePage: DevicePageStrings
    public let pairing: PairingStrings
    public let sessions: SessionsStrings
    public let newSession: NewSessionStrings
    public let chat: ChatStrings
    public let status: StatusStrings
    public let composer: ComposerStrings
    public let commands: CommandsStrings
    public let voice: VoiceStrings
    public let settings: SettingsStrings
    public let connection: ConnectionStrings
    public let errors: ErrorsStrings
    public let a11y: A11yStrings
    public let labels: LabelsStrings
    public let format: FormatStrings
}

extension StringTable {
    static let en = StringTable(
        nav: .en,
        common: .en,
        account: .en,
        login: .en,
        users: .en,
        devices: .en,
        terminal: .en,
        devicePage: .en,
        pairing: .en,
        sessions: .en,
        newSession: .en,
        chat: .en,
        status: .en,
        composer: .en,
        commands: .en,
        voice: .en,
        settings: .en,
        connection: .en,
        errors: .en,
        a11y: .en,
        labels: .en,
        format: .en
    )
    static let zhHans = StringTable(
        nav: .zhHans,
        common: .zhHans,
        account: .zhHans,
        login: .zhHans,
        users: .zhHans,
        devices: .zhHans,
        terminal: .zhHans,
        devicePage: .zhHans,
        pairing: .zhHans,
        sessions: .zhHans,
        newSession: .zhHans,
        chat: .zhHans,
        status: .zhHans,
        composer: .zhHans,
        commands: .zhHans,
        voice: .zhHans,
        settings: .zhHans,
        connection: .zhHans,
        errors: .zhHans,
        a11y: .zhHans,
        labels: .zhHans,
        format: .zhHans
    )

    /// The table an interface language reads.
    public static func of(_ language: InterfaceLanguage) -> StringTable {
        switch language {
        case .en: .en
        case .zhHans: .zhHans
        }
    }
}

extension S {
    public static var nav: NavStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var common: CommonStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var account: AccountStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var login: LoginStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var users: UsersStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var devices: DevicesStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var terminal: TerminalStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var devicePage: DevicePageStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var pairing: PairingStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var sessions: SessionsStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var newSession: NewSessionStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var chat: ChatStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var status: StatusStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var composer: ComposerStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var commands: CommandsStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var voice: VoiceStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var settings: SettingsStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var connection: ConnectionStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var errors: ErrorsStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var a11y: A11yStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var labels: LabelsStrings { .of(InterfaceLanguageSource.shared.current) }
    public static var format: FormatStrings { .of(InterfaceLanguageSource.shared.current) }
}
