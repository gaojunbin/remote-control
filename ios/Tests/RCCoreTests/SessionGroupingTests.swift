import Testing
import Foundation
@testable import RCCore

@Suite("Session list: Active and Archive")
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

    @Test("Anything a CLI or a device still holds is Active")
    func activeMembership() {
        for control in [SessionControl.remote, .terminal, .shared] {
            #expect(SessionListLayout.bucket(session("s", control: control), showsArchived: false) == .active)
        }
        #expect(SessionListLayout.bucket(session("s", control: .none), showsArchived: false) == .archive)
    }

    @Test("A hand-archived session appears only while the toggle is on")
    func archivedToggle() {
        let byHand = session("s", control: .remote, archived: true)
        #expect(SessionListLayout.bucket(byHand, showsArchived: false) == .hidden)
        #expect(SessionListLayout.bucket(byHand, showsArchived: true) == .archive)

        // The toggle never promotes it back to Active, whatever owns it.
        let list = SessionListLayout.build(sessions: [byHand], devices: devices, showsArchived: true)
        #expect(list.activeCount == 0)
        #expect(list.archiveCount == 1)
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
        let held = SessionListLayout.build(sessions: sessions, devices: devices).active.first?.sessions
        #expect(held?.map(\.sessionID) == ["input", "approval", "starting", "running", "idle"])
    }

    @Test("The Archive is one flat list, newest first, device carried on the row")
    func archiveOrder() {
        let sessions = [
            session("old", device: linux, control: .none, updatedAt: 10),
            session("new", control: .none, updatedAt: 99)
        ]
        let list = SessionListLayout.build(sessions: sessions, devices: devices)
        #expect(list.archive.map(\.session.sessionID) == ["new", "old"])
        #expect(list.archive.map(\.deviceName) == ["mac-studio", "ci-runner"])
    }

    @Test("A device with nothing open keeps its place and says so")
    func emptyDeviceKeepsItsPlace() {
        let list = SessionListLayout.build(sessions: [session("s")], devices: devices)
        #expect(list.active.map(\.id) == [mac, linux])
        #expect(list.active.last?.sessions.isEmpty == true)
        #expect(list.active.last?.online == false)
    }

    @Test("Device order comes from the gateway, not from what is running")
    func deviceOrderIsStable() {
        let sessions = [
            session("calm", updatedAt: 10),
            session("urgent", device: linux, state: .needsApproval, updatedAt: 99)
        ]
        let list = SessionListLayout.build(sessions: sessions, devices: devices)
        #expect(list.active.map(\.deviceName) == ["mac-studio", "ci-runner"])
    }

    @Test("Search reads the title, the folder and the agent, and drops empty machines")
    func search() {
        let sessions = [
            session("a", title: "Fix the parser", cwd: "/src/gateway", agent: "claude"),
            session("b", device: linux, title: "Traces", cwd: "/work/api", agent: "codex")
        ]
        func count(_ query: String) -> Int {
            SessionListLayout.build(sessions: sessions, devices: devices, query: query).activeCount
        }
        #expect(count("parser") == 1)
        #expect(count("/work") == 1)
        #expect(count("codex") == 1)
        #expect(count("  PARSER ") == 1)
        #expect(count("nothing here") == 0)

        let narrowed = SessionListLayout.build(sessions: sessions, devices: devices, query: "parser")
        #expect(narrowed.active.count == 1)
        #expect(narrowed.isSearching)
    }

    @Test("A match inside the Archive opens it")
    func searchOpensArchive() {
        let sessions = [session("a", title: "Add traces", control: .none, updatedAt: 5)]
        let quiet = SessionListLayout.build(sessions: sessions, devices: devices)
        #expect(!quiet.forcesArchiveOpen)

        let found = SessionListLayout.build(sessions: sessions, devices: devices, query: "traces")
        #expect(found.forcesArchiveOpen)

        let missed = SessionListLayout.build(sessions: sessions, devices: devices, query: "parser")
        #expect(!missed.forcesArchiveOpen)
        #expect(missed.isEmpty)
    }

    @Test("A session on a device the gateway never listed is still shown")
    func unknownDevice() {
        let list = SessionListLayout.build(sessions: [session("s", device: "ghost")], devices: devices)
        #expect(list.active.map(\.id) == [mac, linux, "ghost"])
        #expect(list.active.last?.deviceName == "ghost")
        #expect(list.active.last?.online == false)
    }

    @MainActor
    @Test("The open or closed choice outlives the launch")
    func expansionIsRemembered() {
        let defaults = UserDefaults(suiteName: "rc-tests-\(UUID().uuidString)")!
        let store = SessionStore(defaults: defaults)
        #expect(!store.isArchiveExpanded)
        store.isArchiveExpanded = true
        #expect(SessionStore(defaults: defaults).isArchiveExpanded)
    }
}
