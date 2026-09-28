import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/DevicePage.test.tsx` § "what stands where the meters go", on
    /// the page's own request: Checking… until the reply, the accounts it
    /// brought by agent, and the one line that stands in for the meters.
    @Suite("Lists: device quota", .serialized) @MainActor
    struct ListsQuotaTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        private let account = AgentAccount(provider: "anthropic", method: .account, plan: "max",
                                           limits: [AgentLimit(windowMinutes: 300, usedPercent: 16)])

        @Test func asksAnOfflineDeviceNothingAndSaysTheQuotaIsUnavailable() async {
            let quota = DeviceQuota()
            let channel = ListsFakeChannel([:])
            await quota.check(deviceID: "a", online: false, channel: channel)
            #expect(quota.status == .offline)
            #expect(await channel.asked.isEmpty)
        }

        @Test func keepsTheFreshAccountsByAgent() async throws {
            let reply = AgentsResult(agents: [AgentInfo(agent: "claude", available: true, accounts: [account]),
                                              AgentInfo(agent: "grok", available: true)])
            let channel = ListsFakeChannel(["device.agents": .success(try JSONValue.encode(reply))])
            let quota = DeviceQuota()
            await quota.check(deviceID: "a", online: true, channel: channel)
            #expect(quota.status == .ready)
            #expect(quota.accounts["claude"]?.first?.limits?.first?.usedPercent == 16)
            #expect(quota.accounts["grok"] == nil)
            #expect(await channel.asked.first?.body["device_id"] == "a")
        }

        @Test func readsDeviceOfflineAsOfflineAndAnyOtherRefusalInTheDevicesWords() async {
            let quota = DeviceQuota()
            await quota.check(deviceID: "a", online: true, channel: ListsFakeChannel([
                "device.agents": .failure(GatewayErrorBody(code: .deviceOffline, message: "the device is offline"))
            ]))
            #expect(quota.status == .offline && quota.error == nil)
            await quota.check(deviceID: "a", online: true, channel: ListsFakeChannel([
                "device.agents": .failure(GatewayErrorBody(code: .internalError, message: "codex app-server exited"))
            ]))
            #expect(quota.status == .failed && quota.error == "codex app-server exited")
        }

        @Test func refreshIsAnotherAttempt() {
            let quota = DeviceQuota()
            quota.refresh()
            quota.refresh()
            #expect(quota.attempt == 2)
        }
    }

    /// `web/tests/SessionsPage.test.tsx` § "the dot legend": the four colours in
    /// order, in the interface language.
    @Suite("Lists: dot legend", .serialized) @MainActor
    struct ListsLegendTests {
        @Test func readsTheFourColoursInOrderInBothLanguages() {
            InterfaceLanguageSource.shared.current = .en
            #expect(SessionLegend.entries.map(\.tone) == [.working, .live, .off, .failed])
            #expect(SessionLegend.entries.map(\.label) == ["Working", "For you", "Not running", "Error"])
            InterfaceLanguageSource.shared.current = .zhHans
            #expect(SessionLegend.entries.map(\.label) == ["运行中", "等你处理", "未运行", "出错"])
            InterfaceLanguageSource.shared.current = .en
        }

        @Test func asksBeforeClosingOnlyAWorkingSessionAndOffersCloseOnlyOnARemoteOne() {
            let running = ListsSessionLayoutTests.session("r", state: .running)
            let idle = ListsSessionLayoutTests.session("i", state: .idle)
            #expect(SessionClose.asksFirst(running, online: true))
            #expect(!SessionClose.asksFirst(idle, online: true))
            #expect(!SessionClose.asksFirst(running, online: false))
            #expect(SessionListLayout.offersClose(idle))
            #expect(!SessionListLayout.offersClose(ListsSessionLayoutTests.session("t", control: .terminal)))
            #expect(!SessionListLayout.offersClose(ListsSessionLayoutTests.session("a", archived: true)))
        }
    }
}
