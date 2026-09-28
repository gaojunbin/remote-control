import RCCore
import Testing
@testable import RCMac

/// `web/tests/sessionLayout.test.ts`: the grouping rule every session list
/// follows — a group per device, its active rows, then that device's own
/// Archive — tested on the selector both lists read.
@Suite("Lists: session layout")
struct ListsSessionLayoutTests {
    static func session(_ id: String, device: String = "dev-a", agent: String = "claude",
                        state: SessionState = .idle, control: SessionControl = .remote,
                        archived: Bool = false, minutesAgo: Int64 = 0, title: String? = nil,
                        cwd: String = "/work/api") -> Session {
        Session(sessionID: id, deviceID: device, agent: agent, title: title ?? id, cwd: cwd, state: state,
                control: control, updatedAt: -minutesAgo * 60_000, archived: archived)
    }

    static func device(_ id: String, name: String? = nil, online: Bool = true) -> Device {
        Device(deviceID: id, name: name ?? id, platform: .linux, hostname: name ?? id, arch: "x86_64",
               clientVersion: "0.1.0", online: online, lastSeen: 0, createdAt: 0)
    }

    private let devices = [device("dev-a", name: "mac-studio"), device("dev-b", name: "ci-runner", online: false)]

    private func ids(_ sessions: [Session]) -> [String] { sessions.map(\.sessionID) }

    private func group(_ groups: [DeviceGroup], _ deviceID: String) -> DeviceGroup? {
        groups.first { $0.id == deviceID }
    }

    @Test func splitsADeviceIntoTheRowsACLIStillHoldsAndItsOwnArchive() throws {
        let sessions = [Self.session("remote", control: .remote), Self.session("terminal", control: .terminal),
                        Self.session("shared", control: .shared),
                        Self.session("gone", state: .stopped, control: .none)]
        let dev = try #require(group(SessionLayout.build(sessions: sessions, devices: devices), "dev-a"))
        #expect(ids(dev.active).sorted() == ["remote", "shared", "terminal"])
        #expect(ids(dev.archive) == ["gone"])
    }

    @Test func filesAManuallyArchivedSessionUnderItsOwnDeviceWhateverHoldsIt() throws {
        let sessions = [Self.session("live"), Self.session("filed", archived: true),
                        Self.session("filed-b", device: "dev-b", control: .shared, archived: true)]
        let groups = SessionLayout.build(sessions: sessions, devices: devices)
        #expect(ids(try #require(group(groups, "dev-a")).active) == ["live"])
        #expect(ids(try #require(group(groups, "dev-a")).archive) == ["filed"])
        #expect(ids(try #require(group(groups, "dev-b")).active) == [])
        #expect(ids(try #require(group(groups, "dev-b")).archive) == ["filed-b"])
    }

    @Test func rendersNoGroupForADeviceWithNothingToShow() {
        let groups = SessionLayout.build(sessions: [Self.session("only")], devices: devices)
        #expect(groups.map(\.id) == ["dev-a"])
        #expect(groups.first?.archive.isEmpty == true)
    }

    @Test func putsDevicesWithSomethingLiveFirstThenBothHalvesByLastActivity() {
        let sessions = [Self.session("quiet-new", device: "dev-b", control: .none, minutesAgo: 1),
                        Self.session("live-old", device: "dev-a", minutesAgo: 90),
                        Self.session("live-new", device: "dev-c", minutesAgo: 5),
                        Self.session("quiet-old", device: "dev-d", control: .none, minutesAgo: 300)]
        let all = devices + [Self.device("dev-c"), Self.device("dev-d")]
        #expect(SessionLayout.build(sessions: sessions, devices: all).map(\.id) == ["dev-c", "dev-a", "dev-b", "dev-d"])
    }

    @Test func ordersADeviceByAttentionThenARunningTurnThenLastActivity() throws {
        let sessions = [Self.session("idle-new", minutesAgo: 1),
                        Self.session("running-old", state: .running, minutesAgo: 30),
                        Self.session("starting-new", state: .starting, minutesAgo: 2),
                        Self.session("approval-old", state: .needsApproval, minutesAgo: 90),
                        Self.session("input-new", state: .needsInput, minutesAgo: 5),
                        Self.session("stopped-new", state: .stopped, minutesAgo: 0)]
        let dev = try #require(group(SessionLayout.build(sessions: sessions, devices: devices), "dev-a"))
        #expect(ids(dev.active) == ["input-new", "approval-old", "starting-new", "running-old", "stopped-new", "idle-new"])
    }

    @Test func ordersOneDevicesArchiveByLastActivity() throws {
        let sessions = [Self.session("old", control: .none, minutesAgo: 120),
                        Self.session("new", archived: true, minutesAgo: 3),
                        Self.session("mid", control: .none, minutesAgo: 40)]
        let dev = try #require(group(SessionLayout.build(sessions: sessions, devices: devices), "dev-a"))
        #expect(ids(dev.archive) == ["new", "mid", "old"])
    }

    @Test func dropsAWholeGroupWhenTheAgentFilterEmptiesIt() {
        let sessions = [Self.session("a-claude", agent: "claude"), Self.session("a-codex", agent: "codex"),
                        Self.session("b-codex", device: "dev-b", agent: "codex")]
        let claude = SessionLayout.build(sessions: sessions, devices: devices, agentFilter: "claude")
        #expect(claude.map(\.id) == ["dev-a"])
        #expect(ids(claude.first?.active ?? []) == ["a-claude"])
        let codex = SessionLayout.build(sessions: sessions, devices: devices, agentFilter: "codex")
        #expect(codex.map(\.id).sorted() == ["dev-a", "dev-b"])
    }

    @Test func restrictsTheListToOneDevice() {
        let sessions = [Self.session("a-live"), Self.session("a-gone", control: .none),
                        Self.session("b-live", device: "dev-b")]
        let groups = SessionLayout.build(sessions: sessions, devices: devices, deviceFilter: "dev-a")
        #expect(groups.map(\.id) == ["dev-a"])
        #expect(ids(groups.first?.active ?? []) == ["a-live"])
        #expect(ids(groups.first?.archive ?? []) == ["a-gone"])
    }

    @Test func readsTheCollapseAndArchiveStateOffTheGivenDeviceIDs() throws {
        let sessions = [Self.session("a-live"), Self.session("a-gone", control: .none),
                        Self.session("b-live", device: "dev-b"), Self.session("b-gone", device: "dev-b", control: .none)]
        let groups = SessionLayout.build(sessions: sessions, devices: devices,
                                         collapsedDevices: ["dev-b"], archiveExpanded: ["dev-a"])
        let a = try #require(group(groups, "dev-a")), b = try #require(group(groups, "dev-b"))
        #expect(!a.collapsed && a.archiveExpanded)
        #expect(b.collapsed && !b.archiveExpanded)
    }

    @Test func opensAnArchiveASearchReachedIntoWithoutTouchingTheStoredIDs() throws {
        let sessions = [Self.session("live", title: "Fix the ingest regression"),
                        Self.session("gone", control: .none, title: "Ingest docs rewrite")]
        let matched = try #require(group(SessionLayout.build(sessions: sessions, devices: devices, query: "docs"), "dev-a"))
        #expect(ids(matched.active) == [] && ids(matched.archive) == ["gone"] && matched.archiveExpanded)
        let missed = try #require(group(SessionLayout.build(sessions: sessions, devices: devices, query: "regression"),
                                        "dev-a"))
        #expect(ids(missed.archive) == [] && !missed.archiveExpanded)
    }

    @Test func opensAFoldedDeviceASearchMatchedWithoutTouchingTheStoredIDs() throws {
        let sessions = [Self.session("live", title: "Fix the ingest regression"),
                        Self.session("gone", control: .none, title: "Ingest docs rewrite")]
        let folded: Set<String> = ["dev-a"]
        #expect(try #require(group(SessionLayout.build(sessions: sessions, devices: devices, collapsedDevices: folded),
                                   "dev-a")).collapsed)
        let searched = try #require(group(SessionLayout.build(sessions: sessions, devices: devices, query: "regression",
                                                              collapsedDevices: folded), "dev-a"))
        #expect(!searched.collapsed && ids(searched.active) == ["live"])
        let inArchive = try #require(group(SessionLayout.build(sessions: sessions, devices: devices, query: "docs",
                                                               collapsedDevices: folded), "dev-a"))
        #expect(!inArchive.collapsed && inArchive.archiveExpanded)
    }

    @Test func searchesTheTitleTheWorkingDirectoryAndTheDeviceName() throws {
        let sessions = [Self.session("one", title: "Fix flaky auth test", cwd: "/work/gateway"),
                        Self.session("two", title: "Add traces", cwd: "/work/ingest"),
                        Self.session("three", device: "dev-b", title: "Nightly sweep", cwd: "/work/api")]
        let byTitle = SessionLayout.build(sessions: sessions, devices: devices, query: "FLAKY")
        #expect(ids(try #require(group(byTitle, "dev-a")).active) == ["one"])
        let byPath = SessionLayout.build(sessions: sessions, devices: devices, query: "ingest")
        #expect(ids(try #require(group(byPath, "dev-a")).active) == ["two"])
        #expect(SessionLayout.build(sessions: sessions, devices: devices, query: "ci-runner").map(\.id) == ["dev-b"])
        // The web never searches the agent, which RCCore's own rule does.
        #expect(SessionLayout.build(sessions: sessions, devices: devices, query: "claude").isEmpty)
    }

    @Test func givesASessionOnADeviceTheGatewayNeverListedAGroupOfItsOwn() {
        let groups = SessionLayout.build(sessions: [Self.session("orphan", device: "dev-gone")], devices: devices)
        #expect(groups.map(\.name) == ["dev-gone"])
        #expect(groups.first?.online == false)
    }

    @Test func listsTheAgentsTheSessionsRunOnceEachInIDOrder() {
        let sessions = [Self.session("one", agent: "codex"), Self.session("two", agent: "claude"),
                        Self.session("three", agent: "codex"), Self.session("four", agent: "grok")]
        #expect(SessionLayout.agents(in: sessions) == ["claude", "codex", "grok"])
    }

    @Test func countsTheUnarchivedSessionsWaitingOnTheReader() {
        let sessions = [Self.session("a", state: .needsApproval), Self.session("b", state: .needsInput),
                        Self.session("c", state: .needsInput, archived: true), Self.session("d", state: .running)]
        #expect(SessionLayout.countWaiting(SessionLayout.unarchived(sessions)) == 2)
    }

    @Test func keepsTheOrderTheGatewayListedWhenTwoRowsTie() throws {
        let sessions = [Self.session("first", minutesAgo: 5), Self.session("second", minutesAgo: 5),
                        Self.session("third", minutesAgo: 5)]
        let dev = try #require(group(SessionLayout.build(sessions: sessions, devices: devices), "dev-a"))
        #expect(ids(dev.active) == ["first", "second", "third"])
    }
}

extension LanguageSensitive {
    /// `web/tests/SessionsPage.test.tsx` § "a session with no title": the row's
    /// own words are what the search reads.
    @Suite("Lists: untitled sessions", .serialized)
    struct ListsUntitledTests {
        @Test func isFoundByASearchForTheWordsItPrints() {
            InterfaceLanguageSource.shared.current = .en
            let untitled = ListsSessionLayoutTests.session("blank", title: " ")
            let devices = [ListsSessionLayoutTests.device("dev-a")]
            #expect(S.sessionTitle(untitled) == "Untitled session")
            #expect(SessionLayout.build(sessions: [untitled], devices: devices, query: "untitled").count == 1)
            InterfaceLanguageSource.shared.current = .zhHans
            #expect(SessionLayout.build(sessions: [untitled], devices: devices, query: "未命名").count == 1)
            InterfaceLanguageSource.shared.current = .en
        }
    }
}
