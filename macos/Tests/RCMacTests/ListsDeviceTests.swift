import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/DevicesPage.test.tsx` and `DeviceUpdate.test.tsx`, on the rules
    /// the device row and the device page read: the order, the counts, the
    /// platform as a word, the client line that speaks only while something is
    /// happening (A36), and why a retry cannot be sent.
    @Suite("Lists: devices", .serialized)
    struct ListsDeviceTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        private func device(_ id: String, name: String, online: Bool = true, platform: DevicePlatform = .macos,
                            state: DeviceUpdateState = .idle, message: String? = nil) -> Device {
            Device(deviceID: id, name: name, platform: platform, hostname: name, arch: "arm64",
                   clientVersion: "1.10.0", updateState: state, updateMessage: message,
                   online: online, lastSeen: 0, createdAt: 0)
        }

        @Test func listsTheDevicesByNameAsTheWebStoreKeepsThem() {
            let devices = [device("b", name: "mac-studio-office"), device("a", name: "ci-runner-01"),
                           device("c", name: "Beta", online: false)]
            #expect(DeviceOrder.byName(devices).map(\.name) == ["Beta", "ci-runner-01", "mac-studio-office"])
            #expect(DeviceOrder.online(devices).map(\.name) == ["ci-runner-01", "mac-studio-office"])
        }

        @Test func countsTheSessionsNobodyArchivedByHand() {
            let sessions = [ListsSessionLayoutTests.session("1", device: "a"),
                            ListsSessionLayoutTests.session("2", device: "a", control: .none),
                            ListsSessionLayoutTests.session("3", device: "a", archived: true),
                            ListsSessionLayoutTests.session("4", device: "b")]
            #expect(DeviceOrder.sessionCounts(sessions) == ["a": 2, "b": 1])
        }

        @Test func writesThePlatformsTheWayTheirMakersDoAndAnyOtherAsSent() {
            #expect(S.platformLabel("macos") == "macOS")
            #expect(S.platformLabel("linux") == "Linux")
            #expect(S.platformLabel("freebsd") == "freebsd")
        }

        @Test func saysNothingAboutAClientWhileNothingIsHappening() {
            #expect(DeviceUpdateWords.notice(for: device("a", name: "a"), localError: nil) == nil)
        }

        @Test func saysUpdatingWhileAnUpdateRuns() {
            let notice = DeviceUpdateWords.notice(for: device("a", name: "a", state: .updating), localError: nil)
            #expect(notice?.text == "Updating…" && notice?.failed == false)
        }

        @Test func saysWhyAnUpdateFailed() {
            let failed = device("a", name: "a", state: .failed, message: "the device did not come back")
            let notice = DeviceUpdateWords.notice(for: failed, localError: nil)
            #expect(notice?.text == "Update failed · the device did not come back" && notice?.failed == true)
        }

        @Test func showsTheDevicesOwnWordsWhenARetryIsRefused() {
            let notice = DeviceUpdateWords.notice(for: device("a", name: "a"), localError: "a session is running")
            #expect(notice?.text == "Update failed · a session is running" && notice?.failed == true)
        }

        @Test func blocksARetryWhileTheDeviceIsOfflineOrTheGatewayServesNoWheel() {
            #expect(DeviceUpdateWords.retryBlocked(device("a", name: "a", online: false), servedBuild: "abc")
                    == "This device is offline.")
            #expect(DeviceUpdateWords.retryBlocked(device("a", name: "a"), servedBuild: nil)
                    == "This gateway is not serving a client build.")
            #expect(DeviceUpdateWords.retryBlocked(device("a", name: "a"), servedBuild: "abc") == nil)
        }

        @Test func confirmsARetryWithTheVersionItInstallsOrTheGatewaysClient() {
            #expect(S.devices.updateBody("ci-runner-01", "1.10.0")
                    == "Update ci-runner-01 to 1.10.0? Its service restarts; sessions it drives are stopped.")
            #expect(S.devices.updateBody("ci-runner-01", nil)
                    == "Update ci-runner-01 to the gateway's client? Its service restarts; sessions it drives are stopped.")
        }

        @Test func speaksChineseOnTheRowToo() {
            InterfaceLanguageSource.shared.current = .zhHans
            defer { InterfaceLanguageSource.shared.current = .en }
            let notice = DeviceUpdateWords.notice(for: device("a", name: "a", state: .failed, message: "boom"),
                                                  localError: nil)
            #expect(notice?.text == "更新失败 · boom")
            #expect(DeviceUpdateWords.retryBlocked(device("a", name: "a", online: false), servedBuild: nil)
                    == "此设备已离线。")
        }
    }
}
