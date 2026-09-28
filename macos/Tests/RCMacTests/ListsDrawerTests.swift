import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/NewSessionDrawer.test.tsx` and `DirectoryPicker.test.tsx`, on
    /// the form and the picker behind the drawer: the defaults it starts from,
    /// what a device or agent change clears, what `session.create` carries, and
    /// what making a folder says and does (A37).
    @Suite("Lists: new session drawer", .serialized) @MainActor
    struct ListsDrawerTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        private let claude = AgentInfo(agent: "claude", available: true,
                                       models: [AgentOption(id: "sonnet", label: "Sonnet 4.5"),
                                                AgentOption(id: "opus", label: "Opus")],
                                       defaultModel: "sonnet",
                                       permissionModes: [AgentOption(id: "default", label: "Ask")],
                                       defaultPermissionMode: "default",
                                       efforts: [AgentOption(id: "high", label: "High")], defaultEffort: "high",
                                       capabilities: [.worktree])
        private let codex = AgentInfo(agent: "codex", available: true,
                                      speeds: [AgentOption(id: "priority", label: "Fast")])
        private let grok = AgentInfo(agent: "grok", available: false)

        private func device(_ id: String, _ agents: [AgentInfo]) -> Device {
            Device(deviceID: id, name: id, platform: .macos, hostname: id, arch: "arm64", clientVersion: "",
                   online: true, lastSeen: 0, createdAt: 0, agents: agents)
        }

        private func created(_ session: Session) throws -> JSONValue {
            try JSONValue.encode(SessionResult(session: session))
        }

        @Test func startsOnThePresetDeviceWhenItIsOnlineElseTheFirst() {
            let devices = [device("a", [claude]), device("b", [codex])]
            #expect(NewSessionForm(devices: devices, preset: "b").deviceID == "b")
            #expect(NewSessionForm(devices: devices, preset: "gone").deviceID == "a")
            #expect(NewSessionForm(devices: devices, preset: nil).deviceID == "a")
            #expect(NewSessionForm(devices: [], preset: nil).deviceID == nil)
        }

        @Test func picksTheChosenAgentElseTheFirstInstalledElseTheFirst() {
            let form = NewSessionForm(devices: [], preset: nil)
            let mixed = device("a", [grok, codex, claude])
            #expect(form.agent(of: mixed)?.agent == "codex")
            form.chooseAgent("claude")
            #expect(form.agent(of: mixed)?.agent == "claude")
            #expect(form.agent(of: device("b", [grok]))?.agent == "grok")
            #expect(form.agent(of: nil) == nil)
        }

        @Test func startsAtTheAgentsOwnDefaultsAndClearsTheChoicesWithTheAgent() {
            let form = NewSessionForm(devices: [device("a", [claude])], preset: nil)
            #expect(form.model(of: claude) == "sonnet")
            #expect(form.effort(of: claude) == "high")
            #expect(form.permissionMode(of: claude) == "default")
            form.options.model = "opus"
            #expect(form.model(of: claude) == "opus")
            form.chooseAgent("codex")
            #expect(form.model(of: claude) == "sonnet")
        }

        @Test func aDeviceChangeKeepsATypedPathAndDropsAnInheritedOne() {
            let form = NewSessionForm(devices: [device("a", [claude]), device("b", [claude])], preset: nil)
            form.cwd = "/Users/me/dev"
            form.chooseDevice("b")
            #expect(form.cwd.isEmpty)
            form.setPath("/Users/me/work")
            form.chooseDevice("a")
            #expect(form.cwd == "/Users/me/work")
        }

        @Test func startsOnlyOnAnInstalledAgentAndAPath() {
            let form = NewSessionForm(devices: [], preset: nil)
            let mac = device("a", [claude])
            #expect(!form.canStart(device: mac, agent: claude))
            form.setPath("  ")
            #expect(!form.canStart(device: mac, agent: claude))
            form.setPath("/work")
            #expect(form.canStart(device: mac, agent: claude))
            #expect(!form.canStart(device: mac, agent: grok))
            #expect(!form.canStart(device: nil, agent: claude))
        }

        @Test func sendsTheDefaultsTheSpeedTierAndAWorktreeOnlyWhenAsked() async throws {
            let mac = device("a", [claude, codex])
            let reply = Session(sessionID: "s1", deviceID: "a", agent: "claude", title: "", cwd: "/work")
            let channel = ListsFakeChannel(["session.create": .success(try created(reply))])
            let form = NewSessionForm(devices: [mac], preset: nil)
            form.setPath(" /work ")
            form.worktree = true
            #expect(await form.start(device: mac, agent: claude, channel: channel)?.sessionID == "s1")
            let first = try #require(await channel.asked.first)
            #expect(first.body["cwd"] == "/work" && first.body["model"] == "sonnet")
            #expect(first.body["effort"] == "high" && first.body["permission_mode"] == "default")
            #expect(first.body["worktree"] == .bool(true) && first.body["speed"] == nil)

            form.chooseAgent("codex")
            form.worktree = true
            form.options.speed = .some("priority")
            _ = await form.start(device: mac, agent: codex, channel: channel)
            let second = try #require(await channel.asked.last)
            #expect(second.body["speed"] == "priority" && second.body["worktree"] == nil)
            #expect(second.body["model"] == nil)

            form.options.speed = .some(nil)
            _ = await form.start(device: mac, agent: codex, channel: channel)
            #expect(await channel.asked.last?.body["speed"] == nil)
        }

        @Test func saysTheDevicesOwnSentenceWhenItRefuses() async {
            let mac = device("a", [claude])
            let form = NewSessionForm(devices: [mac], preset: nil)
            form.setPath("/work")
            let refusing = ListsFakeChannel(["session.create": .failure(GatewayErrorBody(code: .agentUnavailable,
                                                                                          message: "claude is not installed"))])
            #expect(await form.start(device: mac, agent: claude, channel: refusing) == nil)
            #expect(form.error == "claude is not installed")
            #expect(NewSessionForm.refusal(GatewayErrorBody(code: .timeout, message: "timeout")) == nil)
            #expect(NewSessionForm.refusal(TransportError.notConnected) == "Could not start the session.")
        }

        @Test func makesTheFolderStandsInItAndPicksIt() async throws {
            let home = DirectoryListing(path: "/Users/me/dev", parent: "/Users/me",
                                        entries: [DirectoryEntry(name: "api", path: "/Users/me/dev/api", isGit: true)],
                                        recent: [])
            let made = DirectoryListing(path: "/Users/me/dev/notes", parent: "/Users/me/dev", entries: [], recent: [])
            let channel = ListsFakeChannel(["device.dirs": .success(try JSONValue.encode(home)),
                                            "device.mkdir": .success(try JSONValue.encode(made))])
            let browser = DirectoryBrowser(deviceID: "a", channel: channel)
            await browser.open(nil)
            browser.startNaming()
            browser.folderName = " notes "
            await browser.createFolder()
            #expect(browser.listing?.path == "/Users/me/dev/notes")
            #expect(!browser.naming && browser.folderName.isEmpty && browser.folderError == nil)
            let mkdir = try #require(await channel.asked.last)
            #expect(mkdir.type == "device.mkdir" && mkdir.body["path"] == "/Users/me/dev" && mkdir.body["name"] == "notes")
        }

        @Test func saysAClashInItsOwnWordsAndKeepsTheName() async throws {
            let home = DirectoryListing(path: "/Users/me/dev", parent: nil, entries: [], recent: [])
            let channel = ListsFakeChannel([
                "device.dirs": .success(try JSONValue.encode(home)),
                "device.mkdir": .failure(GatewayErrorBody(code: .conflict, message: "/Users/me/dev/api already exists"))
            ])
            let browser = DirectoryBrowser(deviceID: "a", channel: channel)
            await browser.open(nil)
            browser.startNaming()
            browser.folderName = "api"
            await browser.createFolder()
            #expect(browser.folderError == "A folder with that name already exists.")
            #expect(browser.naming && browser.folderName == "api")
        }

        @Test func showsWhatTheDeviceSaidAboutANameItRefused() {
            #expect(DirectoryBrowser.folderRefusal(GatewayErrorBody(code: .badRequest,
                                                                     message: "a folder name cannot begin with a dot"))
                    == "a folder name cannot begin with a dot")
            #expect(DirectoryBrowser.folderRefusal(GatewayErrorBody(code: .notFound, message: "not_found")) == "Not found.")
        }

        @Test func willNotSendAnEmptyName() async throws {
            let home = DirectoryListing(path: "/Users/me/dev", parent: nil, entries: [], recent: [])
            let channel = ListsFakeChannel(["device.dirs": .success(try JSONValue.encode(home))])
            let browser = DirectoryBrowser(deviceID: "a", channel: channel)
            await browser.open(nil)
            browser.startNaming()
            browser.folderName = "   "
            await browser.createFolder()
            #expect(await channel.asked.map(\.type) == ["device.dirs"])
        }

        @Test func leavesTheRowBehindWhenTheListingChanges() async throws {
            let home = DirectoryListing(path: "/Users/me/dev", parent: "/Users/me", entries: [], recent: [])
            let channel = ListsFakeChannel(["device.dirs": .success(try JSONValue.encode(home))])
            let browser = DirectoryBrowser(deviceID: "a", channel: channel)
            await browser.open(nil)
            browser.startNaming()
            await browser.open("/Users/me")
            #expect(!browser.naming)
        }

        @Test func probesNothingUntilThereIsAPathAndReadsAnOlderAnswerAsChecking() async throws {
            let probe = DirectoryProbe()
            #expect(probe.status(deviceID: nil, path: "/work").status == .idle)
            #expect(probe.status(deviceID: "a", path: "  ").status == .idle)
            #expect(probe.status(deviceID: "a", path: "/work").status == .checking)
            let listing = DirectoryListing(path: "/work", parent: "/", entries: [], recent: [])
            let channel = ListsFakeChannel(["device.dirs": .success(try JSONValue.encode(listing)),
                                            "device.git": .success(try JSONValue.encode(GitStatus(isRepo: true,
                                                                                                    branch: "main")))])
            await probe.run(deviceID: "a", path: "/work", channel: channel)
            #expect(probe.status(deviceID: "a", path: "/work").status == .exists)
            #expect(probe.status(deviceID: "a", path: "/work").git?.branch == "main")
            #expect(probe.status(deviceID: "a", path: "/work/api").status == .checking)
            await probe.run(deviceID: "a", path: "/nope", channel: ListsFakeChannel([:]))
            #expect(probe.status(deviceID: "a", path: "/nope").status == .missing)
        }
    }
}
