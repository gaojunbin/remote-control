import Testing
import Foundation
@testable import RCCore

/// Amendment A33: what an agent is signed in with, and what is left of its
/// quota. The wire is the protocol's four worked examples; what is checked here
/// is that they decode into the fields the page reads, and that the words the
/// page writes around them are the ones `docs/DESIGN.md` rules.
@Suite("Accounts and quota (A33)")
struct AgentAccountsTests {
    // MARK: - The wire

    @Test("Claude's account decodes with its tier and three windows, one confined to a model")
    func claudeAccount() throws {
        let claude = try agentFixture("agent.claude-attach.json")
        let account = try #require(claude.accounts?.first)
        #expect(claude.accounts?.count == 1)
        #expect(account.provider == "anthropic")
        #expect(account.method == .account)
        #expect(account.plan == "max")
        #expect(account.tier == "Max 5x")
        #expect(account.email == "me@example.com")
        #expect(account.endpoint == nil)
        #expect(account.limitsError == nil)
        #expect(account.limitsCheckedAt == 1_789_470_000_000)

        let limits = try #require(account.limits)
        #expect(limits.map(\.windowMinutes) == [300, 10080, 10080])
        #expect(limits.map(\.usedPercent) == [16, 54, 64])
        #expect(limits.map(\.scope) == [nil, nil, "Fable"])
        #expect(limits[0].resetsAt == 1_789_487_040_000)
    }

    @Test("Codex's is one account on a plan with the daemon's two windows")
    func codexAccount() throws {
        let codex = try agentFixture("agent.codex-daemon.json")
        let account = try #require(codex.accounts?.first)
        #expect(account.provider == "openai")
        #expect(account.plan == "pro")
        #expect(account.tier == nil)
        #expect(account.limits?.map(\.windowMinutes) == [300, 10080])
    }

    /// The vendor exposes no windows the device can read, so `limits` is absent
    /// and there is no error either: the page draws the line and no meter.
    @Test("Grok Build reports an account with no plan, no windows and no failure")
    func grokAccount() throws {
        let grok = try agentFixture("agent.grok.json")
        let account = try #require(grok.accounts?.first)
        #expect(account.provider == "xai")
        #expect(account.method == .account)
        #expect(account.plan == nil)
        #expect(account.email == "me@example.com")
        #expect(account.limits == nil)
        #expect(account.limitsError == nil)
    }

    @Test("pi holds one credential per provider, and a key names the host it is sent to")
    func piAccounts() throws {
        let pi = try agentFixture("agent.pi.json")
        let accounts = try #require(pi.accounts)
        #expect(accounts.count == 2)
        #expect(accounts[0].provider == "anthropic")
        #expect(accounts[0].method == .account)
        #expect(accounts[0].limits?.count == 2)
        #expect(accounts[1].provider == "openai")
        #expect(accounts[1].method == .apiKey)
        #expect(accounts[1].endpoint == "api.relay.example")
        #expect(accounts[1].limits == nil)
    }

    /// `hello` and `agents.updated` carry the credentials without the windows,
    /// which is why the page asks for them itself.
    @Test("A published device carries accounts without limits")
    func publishedDeviceHasNoLimits() throws {
        guard case .deviceUpdated(let device) = try AppFrame(data: try appFixture("device.updated.json"))
        else { Issue.record("device.updated does not decode as a device"); return }
        for agent in device.agents {
            for account in agent.accounts ?? [] {
                #expect(account.limits == nil)
                #expect(account.limitsError == nil)
                #expect(account.limitsCheckedAt == nil)
            }
        }
        #expect(device.agent("claude")?.accounts?.first?.tier == "Max 5x")
    }

    @Test("And a device.agents reply carries the same accounts with them")
    func replyCarriesLimits() throws {
        let json = try JSONDecoder().decode(JSONValue.self, from: appFixture("reply.device.agents.json"))
        let result = try #require(json["result"]).decode(AgentsResult.self)
        #expect(result.agents.count == 2)
        #expect(result.agents[0].accounts?.first?.limits?.count == 3)
        #expect(result.agents[1].accounts?.first?.limits?.count == 2)
    }

    /// An older device says nothing about accounts at all, and "did not look"
    /// has to stay distinguishable from "signed in nowhere".
    @Test("Absent, empty and populated are three different answers")
    func threeAnswers() throws {
        let absent = try JSONDecoder().decode(
            AgentInfo.self, from: Data(#"{"agent":"claude","available":true}"#.utf8))
        #expect(absent.accounts == nil)

        let empty = try JSONDecoder().decode(
            AgentInfo.self, from: Data(#"{"agent":"claude","available":true,"accounts":[]}"#.utf8))
        #expect(empty.accounts == [])
    }

    @Test("The windows are dropped for storage, and put back for the page")
    func limitsAreNotStored() throws {
        let claude = try agentFixture("agent.claude-attach.json")
        let account = try #require(claude.accounts?.first)
        let stored = account.withoutLimits
        #expect(stored.limits == nil)
        #expect(stored.limitsCheckedAt == nil)
        #expect(stored.tier == account.tier)
        #expect(claude.with(accounts: [stored]).accounts == [stored])
        #expect(claude.with(accounts: nil).accounts == nil)
    }

    // MARK: - The line that says how it is signed in

    @Test("The three vendors the app has a name for, and one it has not", arguments: [
        ("anthropic", "Anthropic"), ("openai", "OpenAI"), ("xai", "xAI"), ("mistral", "mistral")
    ])
    func vendorNames(provider: String, name: String) {
        #expect(AccountLine.vendorName(provider) == name)
    }

    @Test("An account reads vendor, plan, tier and email, each only when reported")
    func accountLine() {
        let full = AgentAccount(provider: "anthropic", method: .account, plan: "max",
                                tier: "Max 5x", email: "me@example.com")
        // The plan is raised at its first letter; the tier is the device's own
        // words and is printed exactly as it arrived.
        #expect(AccountLine.text(for: full) == "Anthropic account · Max · Max 5x · me@example.com")

        let plain = AgentAccount(provider: "xai", method: .account, plan: nil,
                                 email: "me@example.com")
        #expect(AccountLine.text(for: plain) == "xAI account · me@example.com")

        let bare = AgentAccount(provider: "openai", method: .account)
        #expect(AccountLine.text(for: bare) == "OpenAI account")
    }

    @Test("A key says so, and names a third-party host when there is one")
    func keyLine() {
        #expect(AccountLine.text(for: AgentAccount(provider: "anthropic", method: .apiKey))
                == "Anthropic API key")
        #expect(AccountLine.text(for: AgentAccount(provider: "openai", method: .apiKey,
                                                   endpoint: "api.relay.example"))
                == "OpenAI API key · api.relay.example")
        #expect(AccountLine.notSignedIn == "Not signed in")
    }

    // MARK: - The meter

    @Test("A window is named by the unit it divides into", arguments: [
        (300, "5-hour"), (1440, "24-hour"), (10080, "7-day"), (2880, "2-day"),
        (60, "1-hour"), (90, "90-minute")
    ])
    func windowNames(minutes: Int, name: String) {
        #expect(QuotaWindow.length(minutes: minutes) == name)
    }

    @Test("And carries its scope after it where the vendor confined it to one")
    func scopedWindow() {
        let weekly = AgentLimit(windowMinutes: 10080, usedPercent: 64)
        #expect(QuotaWindow.name(weekly) == "7-day")
        let scoped = AgentLimit(windowMinutes: 10080, scope: "Fable", usedPercent: 64)
        #expect(QuotaWindow.name(scoped) == "7-day · Fable")
    }

    /// The page's only colour rule: ink, then the warning colour past 80, then
    /// the danger colour at 100.
    @Test("The bands turn where the design says they turn", arguments: [
        (0.0, QuotaWindow.Band.normal), (80.0, .normal), (80.5, .warning), (99.9, .warning),
        (100.0, .danger), (140.0, .danger)
    ])
    func bands(percent: Double, band: QuotaWindow.Band) {
        #expect(QuotaWindow.band(usedPercent: percent) == band)
    }

    @Test("A bar draws what it can and no more")
    func fills() {
        #expect(QuotaWindow.fill(usedPercent: 0) == 0)
        #expect(QuotaWindow.fill(usedPercent: 50) == 0.5)
        #expect(QuotaWindow.fill(usedPercent: 140) == 1)
        #expect(QuotaWindow.fill(usedPercent: -10) == 0)
        #expect(QuotaWindow.percentage(16.4) == "16%")
        #expect(QuotaWindow.percentage(99.6) == "100%")
        #expect(QuotaWindow.percentage(120) == "100%")
    }

    @Test("A window that comes back today reads as a clock, and any other as a day and a clock")
    func resetWording() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .gmt
        let locale = Locale(identifier: "en_GB")
        // 2026-09-15 is a Tuesday.
        let now = Date(timeIntervalSince1970: 1_789_470_000)   // 2026-09-15 11:00 UTC
        let later = Int64(1_789_486_800_000)                   // the same day, 15:40 UTC
        let nextWeek = Int64(1_790_114_400_000)                // 2026-09-22 22:00 UTC, a Tuesday

        #expect(QuotaWindow.clock(at: later, now: now, calendar: calendar, locale: locale) == "15:40")
        #expect(QuotaWindow.clock(at: nextWeek, now: now, calendar: calendar, locale: locale)
                == "Tue 22:00")
        #expect(QuotaWindow.resets(at: later, now: now, calendar: calendar, locale: locale)
                == "resets 15:40")
    }

    // MARK: - The demo

    /// The demo holds every shape the page has to draw, because that is what
    /// the previews and the UI test read.
    @Test("The demo machines cover an account, a key, a failure, nothing and nowhere")
    func demoShapes() throws {
        let mac = try #require(DemoFixtures.agentsWithQuota(deviceID: DemoFixtures.macDeviceID))
        let claude = try #require(mac.first { $0.agent == "claude" }?.accounts?.first)
        #expect(claude.limits?.count == 3)
        #expect(claude.tier == "Max 5x")
        let grok = try #require(mac.first { $0.agent == "grok" }?.accounts?.first)
        #expect(grok.limits == nil && grok.limitsError == nil)
        let pi = try #require(mac.first { $0.agent == "pi" }?.accounts)
        #expect(pi.count == 2)
        #expect(pi[1].method == .apiKey && pi[1].endpoint == "api.relay.example")

        let laptop = try #require(DemoFixtures.agentsWithQuota(deviceID: DemoFixtures.laptopDeviceID))
        let expired = try #require(laptop.first { $0.agent == "claude" }?.accounts?.first)
        #expect(expired.limits == nil)
        #expect(expired.limitsError?.isEmpty == false)
        #expect(laptop.first { $0.agent == "grok" }?.accounts == [])

        // Nothing carrying a window ever reaches the stored device list.
        for device in DemoFixtures.devices {
            for agent in device.agents {
                for account in agent.accounts ?? [] {
                    #expect(account.limits == nil)
                    #expect(account.limitsCheckedAt == nil)
                }
            }
        }
    }

    // MARK: - Fixtures

    private func fixture(_ relativePath: String) throws -> Data {
        let url = URL(filePath: #filePath)
            .deletingLastPathComponent()   // ios/Tests/RCCoreTests
            .deletingLastPathComponent()   // ios/Tests
            .deletingLastPathComponent()   // ios
            .deletingLastPathComponent()   // repository root
            .appending(path: "protocol/fixtures/\(relativePath)")
        return try Data(contentsOf: url)
    }

    private func agentFixture(_ name: String) throws -> AgentInfo {
        try JSONDecoder().decode(AgentInfo.self, from: try fixture("objects/\(name)"))
    }

    private func appFixture(_ name: String) throws -> Data { try fixture("app/\(name)") }
}
