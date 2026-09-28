import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/accounts.test.ts`: the words a device page puts on an account
    /// and its windows — the vendor from a table of three ids, everything else
    /// the device reported printed as it arrived, and the meter's colour band.
    @Suite("Lists: account words", .serialized)
    struct ListsAccountWordsTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        private func account(_ provider: String, _ method: AccountMethod, plan: String? = nil, tier: String? = nil,
                             email: String? = nil, endpoint: String? = nil) -> AgentAccount {
            AgentAccount(provider: provider, method: method, plan: plan, tier: tier, email: email, endpoint: endpoint)
        }

        private func limit(_ minutes: Int, scope: String? = nil, used: Double = 0) -> AgentLimit {
            AgentLimit(windowMinutes: minutes, scope: scope, usedPercent: used)
        }

        /// Local wall-clock times, as `new Date(2026, 8, 15, …)` makes them.
        private func millis(_ day: Int, _ hour: Int, _ minute: Int) -> Int64 {
            let date = Calendar.current.date(from: DateComponents(year: 2026, month: 9, day: day, hour: hour,
                                                                  minute: minute))!
            return Int64(date.timeIntervalSince1970 * 1000)
        }

        @Test func namesTheThreeVendorsTheAppsKnowAndPrintsAnyOtherAsItself() {
            #expect(S.vendorLabel("anthropic") == "Anthropic")
            #expect(S.vendorLabel("openai") == "OpenAI")
            #expect(S.vendorLabel("xai") == "xAI")
            #expect(S.vendorLabel("mistral") == "mistral")
        }

        @Test func readsVendorPlanTierAndEmailForAnAccount() {
            #expect(AccountWords.signInLine(account("anthropic", .account, plan: "max", tier: "Max 5x",
                                                    email: "me@example.com"))
                    == "Anthropic account · Max · Max 5x · me@example.com")
        }

        @Test func raisesThePlansFirstLetterAndLeavesTheTierExactlyAsReported() {
            #expect(AccountWords.signInLine(account("openai", .account, plan: "pro", tier: "gpt-5.4 priority"))
                    == "OpenAI account · Pro · gpt-5.4 priority")
        }

        @Test func drawsNothingForWhatTheDeviceDidNotReport() {
            #expect(AccountWords.signInLine(account("xai", .account)) == "xAI account")
        }

        @Test func leadsAKeyWithTheVendorAndNamesAThirdPartyHost() {
            #expect(AccountWords.signInLine(account("anthropic", .apiKey)) == "Anthropic API key")
            #expect(AccountWords.signInLine(account("openai", .apiKey, endpoint: "api.relay.example"))
                    == "OpenAI API key · api.relay.example")
            #expect(AccountWords.signInLine(account("mistral", .apiKey)) == "mistral API key")
        }

        @Test func keepsThePlanTheTierAndTheEmailUntranslatedInChinese() {
            InterfaceLanguageSource.shared.current = .zhHans
            defer { InterfaceLanguageSource.shared.current = .en }
            #expect(AccountWords.signInLine(account("anthropic", .account, plan: "max", email: "me@x.io"))
                    == "Anthropic 账户 · Max · me@x.io")
        }

        @Test(arguments: [(300, "5-hour"), (1440, "24-hour"), (10080, "7-day"), (60, "1-hour"), (20160, "14-day"),
                          (90, "90-minute")])
        func namesAWindowByItsLength(minutes: Int, expected: String) {
            #expect(AccountWords.windowName(limit(minutes)) == expected)
        }

        @Test func putsWhatTheWindowIsConfinedToAfterIt() {
            #expect(AccountWords.windowName(limit(10080, scope: "Fable", used: 64)) == "7-day · Fable")
        }

        @Test func namesTheSameWindowInChinese() {
            InterfaceLanguageSource.shared.current = .zhHans
            defer { InterfaceLanguageSource.shared.current = .en }
            #expect(AccountWords.windowName(limit(300)) == "5 小时")
        }

        @Test func coloursTheMeterInkThenWarningPast80ThenDangerAt100() {
            #expect(AccountWords.meterTone(0) == .ink)
            #expect(AccountWords.meterTone(80) == .ink)
            #expect(AccountWords.meterTone(80.5) == .warn)
            #expect(AccountWords.meterTone(99) == .warn)
            #expect(AccountWords.meterTone(100) == .danger)
        }

        @Test func drawsAWholePercentageInsideTheTrack() {
            #expect(AccountWords.usedPercent(limit(300, used: 16.4)) == 16)
            #expect(AccountWords.usedPercent(limit(300, used: 16.5)) == 17)
            #expect(AccountWords.usedPercent(limit(300, used: 120)) == 100)
            #expect(AccountWords.usedPercent(limit(300, used: -1)) == 0)
        }

        @Test func givesTheClockAloneForAResetLaterTodayAndTheDayOtherwise() {
            let now = millis(15, 9, 0)
            #expect(AccountWords.resetsText(millis(15, 15, 40), now: now) == "resets 15:40")
            #expect(AccountWords.resetsText(millis(22, 22, 0), now: now) == "resets Tue 22:00")
        }

        @Test func saysWhenItResetsInChineseWithTheSameClock() {
            InterfaceLanguageSource.shared.current = .zhHans
            defer { InterfaceLanguageSource.shared.current = .en }
            #expect(AccountWords.resetsText(millis(15, 15, 40), now: millis(15, 9, 0)) == "15:40 重置")
            #expect(S.devicePage.checking == "检查中…")
        }
    }
}
