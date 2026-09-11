import Testing
import Foundation
@testable import RCCore

@Suite("Session list: one group per device")
struct SessionGroupingTests {
    private let mac = "device-mac"
    private let linux = "device-linux"

    private func device(_ id: String, name: String, online: Bool = true) -> Device {
        Device(deviceID: id, name: name, platform: .macos, hostname: "\(name).local",
               arch: "arm64", clientVersion: "0.1.0", online: online, lastSeen: 0, createdAt: 0)
    }

    private func session(_ id: String, device: String = "device-mac", title: String = "Work",
                         cwd: String = "/src", agent: String = "claude",
                         state: SessionState = .idle, control: SessionControl = .remote,
                         updatedAt: Int64 = 0, archived: Bool = false) -> Session {
        Session(sessionID: id, deviceID: device, agent: agent, title: title, cwd: cwd,
                state: state, control: control, updatedAt: updatedAt, archived: archived)
    }

    private var devices: [Device] {
        [device(mac, name: "mac-studio"), device(linux, name: "ci-runner", online: false)]
    }

    @Test("A session splits into its own device's live rows or its own Archive")
    func perDeviceSplit() {
        let sessions = [
            session("live", updatedAt: 100),
            session("ended", control: .none, updatedAt: 90),
            session("elsewhere", device: linux, updatedAt: 80)
        ]
        let groups = SessionListLayout.build(sessions: sessions, devices: devices)
        #expect(groups.map(\.id) == [mac, linux])
        #expect(groups.first?.active.map(\.sessionID) == ["live"])
        #expect(groups.first?.archive.map(\.sessionID) == ["ended"])
        #expect(groups.last?.active.map(\.sessionID) == ["elsewhere"])
        #expect(groups.last?.archive.isEmpty == true)
        #expect(groups.first?.name == "mac-studio")
        #expect(groups.last?.online == false)
    }

    @Test("Anything a CLI or a device still holds stays out of the Archive")
    func activeMembership() {
        for control in [SessionControl.remote, .terminal, .shared] {
            #expect(!SessionListLayout.isArchived(session("s", control: control)))
        }
        #expect(SessionListLayout.isArchived(session("s", control: .none)))
    }

    @Test("Archiving is offered on a row the device drives, and on no other")
    func archiveIsOfferedOnOneKindOfRow() {
        #expect(SessionListLayout.offersArchive(session("driven", control: .remote)))
        // The terminal owns these; the row leaves Active when the CLI exits.
        for control in [SessionControl.terminal, .shared, .none] {
            #expect(!SessionListLayout.offersArchive(session("held", control: control)))
        }
        // Nothing in the Archive offers anything: writing to it brings it back.
        #expect(!SessionListLayout.offersArchive(session("filed", control: .remote, archived: true)))
        #expect(!SessionListLayout.offersArchive(session("filed", control: .terminal, archived: true)))
    }

    @Test("A machine with live work leads, then the rest by their last activity")
    func groupOrder() {
        let sessions = [
            session("quiet", control: .none, updatedAt: 900),
            session("busy", device: linux, state: .running, updatedAt: 10)
        ]
        #expect(SessionListLayout.build(sessions: sessions, devices: devices).map(\.id) == [linux, mac])

        let both = [
            session("older", updatedAt: 10),
            session("newer", device: linux, updatedAt: 500)
        ]
        #expect(SessionListLayout.build(sessions: both, devices: devices).map(\.id) == [linux, mac])
    }

    @Test("A machine with nothing to show is not rendered at all")
    func emptyDeviceIsDropped() {
        let groups = SessionListLayout.build(sessions: [session("s")], devices: devices)
        #expect(groups.map(\.id) == [mac])
    }

    @Test("Inside a device, the user is asked first, then the work, then the rest")
    func orderWithinDevice() {
        let sessions = [
            session("idle", state: .idle, updatedAt: 900),
            session("running", state: .running, updatedAt: 100),
            session("approval", state: .needsApproval, updatedAt: 50),
            session("input", state: .needsInput, updatedAt: 80),
            session("starting", state: .starting, updatedAt: 200)
        ]
        let held = SessionListLayout.build(sessions: sessions, devices: devices).first?.active
        #expect(held?.map(\.sessionID) == ["input", "approval", "starting", "running", "idle"])
    }

    @Test("A hand-archived session joins its device's Archive, whatever still owns it")
    func manuallyArchived() {
        let sessions = [
            session("byHand", state: .running, updatedAt: 50, archived: true),
            session("ended", control: .none, updatedAt: 99),
            session("live", updatedAt: 10)
        ]
        let group = SessionListLayout.build(sessions: sessions, devices: devices).first
        #expect(group?.active.map(\.sessionID) == ["live"])
        #expect(group?.archive.map(\.sessionID) == ["ended", "byHand"])
        // The row itself says which of the two it is, so the list can mark it.
        #expect(group?.archive.last?.archived == true)
        #expect(group?.archive.first?.archived == false)
    }

    @Test("A device with nothing archived reports an empty Archive, so the header is hidden")
    func emptyArchiveIsHidden() {
        let groups = SessionListLayout.build(sessions: [session("live")], devices: devices)
        #expect(groups.first?.archive.isEmpty == true)
    }

    @Test("A device with only archived sessions still gets its group")
    func archiveOnlyDevice() {
        let groups = SessionListLayout.build(sessions: [session("ended", control: .none)], devices: devices)
        #expect(groups.map(\.id) == [mac])
        #expect(groups.first?.active.isEmpty == true)
        #expect(groups.first?.archive.count == 1)
    }

    @Test("An Archive is closed until it is asked for, and a match inside opens it")
    func archiveExpansion() {
        let sessions = [session("ended", title: "Add traces", control: .none)]
        let closed = SessionListLayout.build(sessions: sessions, devices: devices)
        #expect(closed.first?.archiveExpanded == false)

        let asked = SessionListLayout.build(sessions: sessions, devices: devices,
                                            archiveExpanded: [mac])
        #expect(asked.first?.archiveExpanded == true)

        let found = SessionListLayout.build(sessions: sessions, devices: devices, query: "traces")
        #expect(found.first?.archiveExpanded == true)

        // A search that matches only live rows leaves the Archive alone.
        let live = sessions + [session("live", title: "Add traces to the gateway")]
        let missed = SessionListLayout.build(sessions: live, devices: devices, query: "gateway")
        #expect(missed.first?.archive.isEmpty == true)
        #expect(missed.first?.archiveExpanded == false)
    }

    /// Amendment A15: the device clears `archived` when the session comes back
    /// to life and publishes it. Nothing about the row's place is remembered, so
    /// the next build of the list already has it among the live rows, and the
    /// one after that folds it back if the reader archives it again.
    @MainActor
    @Test("A session that comes back to life leaves the Archive at once, and folds back")
    func revivedSessionLeavesTheArchive() {
        let defaults = UserDefaults(suiteName: "rc-tests-\(UUID().uuidString)")!
        let store = SessionStore(defaults: defaults)
        store.toggleArchive(mac)
        let dormant = session("resumed", state: .stopped, control: .none,
                              updatedAt: 40, archived: true)
        var sessions = [session("live", state: .running, updatedAt: 60),
                        session("ended", control: .none, updatedAt: 50),
                        dormant]

        let folded = store.groups(sessions, devices: devices)
        #expect(folded.first?.active.map(\.sessionID) == ["live"])
        #expect(folded.first?.archive.map(\.sessionID) == ["ended", "resumed"])

        // The session.updated a resumed session publishes: the flag is gone and
        // a terminal owns it again.
        sessions[2] = session("resumed", state: .running, control: .terminal, updatedAt: 120)
        let revived = store.groups(sessions, devices: devices)
        #expect(revived.first?.active.map(\.sessionID) == ["resumed", "live"])
        #expect(revived.first?.archive.map(\.sessionID) == ["ended"])
        // The Archive is open because the reader opened it, not because the row
        // left: the stored choice is per device and survives the move.
        #expect(revived.first?.archiveExpanded == true)
        #expect(store.expandedArchives == [mac])

        sessions[2] = dormant
        let refolded = store.groups(sessions, devices: devices)
        #expect(refolded.first?.active.map(\.sessionID) == ["live"])
        #expect(refolded.first?.archive.map(\.sessionID) == ["ended", "resumed"])
    }

    /// The flag on its own decides the half, with nothing else changing. A
    /// session its device still owns sits in the Archive only while `archived`
    /// is set, so clearing it is enough to move the row.
    @Test("Clearing archived alone moves the row, and the last one out takes the header")
    func archivedFlagAloneDecidesTheHalf() {
        let held = session("resumed", control: .remote, updatedAt: 40)
        var archivedByHand = held
        archivedByHand.archived = true

        #expect(SessionListLayout.isArchived(archivedByHand))
        #expect(!SessionListLayout.isArchived(held))

        let before = SessionListLayout.build(sessions: [archivedByHand], devices: devices)
        #expect(before.first?.archive.map(\.sessionID) == ["resumed"])
        #expect(before.first?.active.isEmpty == true)

        // The whole Archive was that one row, so the sub-header goes with it.
        let after = SessionListLayout.build(sessions: [held], devices: devices)
        #expect(after.first?.archive.isEmpty == true)
        #expect(after.first?.active.map(\.sessionID) == ["resumed"])
    }

    @Test("Search reads the title, the folder and the agent, and drops empty machines")
    func search() {
        let sessions = [
            session("a", title: "Fix the parser", cwd: "/src/gateway", agent: "claude"),
            session("b", device: linux, title: "Traces", cwd: "/work/api", agent: "codex")
        ]
        func rows(_ query: String) -> [String] {
            SessionListLayout.build(sessions: sessions, devices: devices, query: query)
                .flatMap { $0.active.map(\.sessionID) }
        }
        #expect(rows("parser") == ["a"])
        #expect(rows("/work") == ["b"])
        #expect(rows("codex") == ["b"])
        #expect(rows("claude code") == ["a"])
        #expect(rows("  PARSER ") == ["a"])
        #expect(rows("nothing here").isEmpty)
        #expect(SessionListLayout.build(sessions: sessions, devices: devices, query: "parser").count == 1)
    }

    @Test("The agent filter applies before the grouping, so a machine can disappear")
    func agentFilter() {
        let sessions = [
            session("claude-one", agent: "claude", updatedAt: 100),
            session("codex-one", agent: "codex", updatedAt: 90),
            session("claude-two", device: linux, agent: "claude", updatedAt: 50)
        ]
        #expect(SessionListLayout.agents(in: sessions) == ["claude", "codex"])

        let codex = SessionListLayout.build(sessions: sessions, devices: devices, agentFilter: "codex")
        #expect(codex.map(\.id) == [mac])
        #expect(codex.first?.active.map(\.sessionID) == ["codex-one"])

        let claude = SessionListLayout.build(sessions: sessions, devices: devices, agentFilter: "claude")
        #expect(claude.map(\.id) == [mac, linux])
    }

    @Test("The agent filter offers only the agents the list contains, in label order")
    func agentOptions() {
        #expect(SessionListLayout.agents(in: []).isEmpty)
        #expect(SessionListLayout.agents(in: [session("a", agent: "codex")]) == ["codex"])
        let mixed = [session("a", agent: "codex"), session("b", agent: "amp"), session("c", agent: "claude")]
        #expect(SessionListLayout.agents(in: mixed) == ["amp", "claude", "codex"])
    }

    @Test("With one device picked, only that group is built")
    func deviceFilter() {
        let sessions = [session("a"), session("b", device: linux)]
        let only = SessionListLayout.build(sessions: sessions, devices: devices, deviceFilter: linux)
        #expect(only.map(\.id) == [linux])
        #expect(only.first?.active.map(\.sessionID) == ["b"])
    }

    @Test("A folded machine keeps its rows, so a count still reads them")
    func collapsedGroup() {
        let groups = SessionListLayout.build(sessions: [session("s")], devices: devices,
                                             collapsedDevices: [mac])
        #expect(groups.first?.collapsed == true)
        #expect(groups.first?.active.count == 1)
    }

    @Test("A search unfolds the machines it matched, and clearing it folds them back")
    func searchUnfoldsCollapsedGroups() {
        let sessions = [session("s", title: "Fix the parser")]
        let found = SessionListLayout.build(sessions: sessions, devices: devices,
                                            query: "parser", collapsedDevices: [mac])
        #expect(found.first?.collapsed == false)

        let cleared = SessionListLayout.build(sessions: sessions, devices: devices,
                                              collapsedDevices: [mac])
        #expect(cleared.first?.collapsed == true)
    }

    @Test("A session on a device the gateway never listed is still shown")
    func unknownDevice() {
        let groups = SessionListLayout.build(sessions: [session("s", device: "ghost")], devices: devices)
        #expect(groups.map(\.id) == ["ghost"])
        #expect(groups.first?.name == "ghost")
        #expect(groups.first?.online == false)
    }

    @MainActor
    @Test("Folding a machine away and opening its Archive both outlive the launch")
    func collapseRoundTrip() {
        let defaults = UserDefaults(suiteName: "rc-tests-\(UUID().uuidString)")!
        let store = SessionStore(defaults: defaults)
        #expect(store.collapsedDevices.isEmpty)
        #expect(store.expandedArchives.isEmpty)

        store.toggleCollapsed(mac)
        store.toggleArchive(linux)
        #expect(defaults.stringArray(forKey: "sessions.collapsedDevices") == [mac])
        #expect(defaults.stringArray(forKey: "sessions.archiveExpanded") == [linux])

        let reloaded = SessionStore(defaults: defaults)
        #expect(reloaded.collapsedDevices == [mac])
        #expect(reloaded.expandedArchives == [linux])
        #expect(reloaded.isCollapsed(mac))

        reloaded.toggleCollapsed(mac)
        #expect(SessionStore(defaults: defaults).collapsedDevices.isEmpty)
    }

    @MainActor
    @Test("The agent filter is a view of the list, not a setting")
    func agentFilterIsNotPersisted() {
        let defaults = UserDefaults(suiteName: "rc-tests-\(UUID().uuidString)")!
        let store = SessionStore(defaults: defaults)
        #expect(store.agentFilter == nil)
        store.agentFilter = "codex"
        #expect(SessionStore(defaults: defaults).agentFilter == nil)
    }
}
