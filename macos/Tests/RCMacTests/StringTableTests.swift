import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// Every group of the web's string table, in both languages. The compiler
    /// already refuses a table with a key missing — both are built with the same
    /// memberwise initialiser — so this walks what it cannot see: an empty string,
    /// a word list of another length, a label table with other ids.
    @Suite("Strings")
    struct StringTableTests {
        private func groups(_ table: StringTable) -> [(String, Any)] {
            Mirror(reflecting: table).children.map { ($0.label ?? "?", $0.value) }
        }

        @Test func bothLanguagesCarryTheSameGroupsAndKeys() {
            let english = groups(.en)
            let chinese = groups(.zhHans)
            #expect(english.map(\.0) == chinese.map(\.0))
            #expect(english.count == 22)
            for ((group, en), (_, zh)) in zip(english, chinese) {
                let enKeys = Mirror(reflecting: en).children.map { $0.label ?? "?" }
                let zhKeys = Mirror(reflecting: zh).children.map { $0.label ?? "?" }
                #expect(enKeys == zhKeys, "group \(group)")
                #expect(!enKeys.isEmpty, "group \(group)")
            }
        }

        @Test func noWordIsEmptyAndEveryListMatches() {
            for ((group, en), (_, zh)) in zip(groups(.en), groups(.zhHans)) {
                for (enField, zhField) in zip(Mirror(reflecting: en).children, Mirror(reflecting: zh).children) {
                    let key = "\(group).\(enField.label ?? "?")"
                    switch (enField.value, zhField.value) {
                    case let (a as String, b as String):
                        #expect(!a.isEmpty && !b.isEmpty, "\(key)")
                    case let (a as [String], b as [String]):
                        #expect(a.count == b.count && !a.contains("") && !b.contains(""), "\(key)")
                    case let (a as [String: String], b as [String: String]):
                        #expect(Set(a.keys) == Set(b.keys), "\(key)")
                    case let (a as TimelineDetailLabels, b as TimelineDetailLabels):
                        #expect(!a.simple.isEmpty && !b.detailed.isEmpty, "\(key)")
                    default:
                        break
                    }
                }
            }
        }

        @Test func functionsKeepTheirNumbersInBothLanguages() {
            #expect(StringTable.en.format.minutesAgo(3) == "3m ago")
            #expect(StringTable.zhHans.format.minutesAgo(3) == "3 分钟前")
            #expect(StringTable.en.devices.sessionsCount(1) == "1 session")
            #expect(StringTable.en.devices.sessionsCount(2) == "2 sessions")
            #expect(StringTable.en.devices.updateBody("mac", nil).contains("the gateway's client"))
            #expect(StringTable.en.devices.updateBody("mac", "1.2.0") == "Update mac to 1.2.0? Its service restarts; sessions it drives are stopped.")
            #expect(StringTable.zhHans.settings.versions("1.0", "1") == "网关 1.0 · 协议 1")
        }

        @Test @MainActor func sFollowsTheInterfaceLanguage() {
            let source = InterfaceLanguageSource.shared
            let before = source.current
            defer { source.current = before }
            source.current = .en
            #expect(S.nav.devices == "Devices")
            #expect(S.mac.gateway == "Gateway")
            source.current = .zhHans
            #expect(S.nav.devices == "设备")
            #expect(S.mac.gateway == "网关")
            #expect(S.productName == "Remote Control")
        }

        @Test func macWordsHaveBothLanguages() {
            let en = Mirror(reflecting: MacStrings.en).children
            let zh = Mirror(reflecting: MacStrings.zhHans).children
            #expect(en.map(\.label) == zh.map(\.label))
            for (a, b) in zip(en, zh) {
                if let a = a.value as? String, let b = b.value as? String { #expect(!a.isEmpty && !b.isEmpty) }
            }
        }

        @Test @MainActor func labelsFallBackToTheIdTheyWereGiven() {
            InterfaceLanguageSource.shared.current = .en
            #expect(S.agentLabel("claude") == "Claude Code")
            #expect(S.agentLabel("cursor") == "cursor")
            #expect(S.platformLabel("macos") == "macOS")
            #expect(S.platformLabel("freebsd") == "freebsd")
            #expect(S.vendorLabel("openai") == "OpenAI")
            #expect(S.stateLabel("needs_approval") == "needs approval")
            #expect(S.roleLabel("owner") == "owner")
            #expect(S.interfaceLanguageLabel(.zhHans) == "中文")
            let untitled = Session(sessionID: "s", deviceID: "d", agent: "claude", title: "  ", cwd: "/")
            #expect(S.sessionTitle(untitled) == "Untitled session")
            #expect(S.sessionOriginLabel(untitled) == "Remote Control")
        }
    }
}
