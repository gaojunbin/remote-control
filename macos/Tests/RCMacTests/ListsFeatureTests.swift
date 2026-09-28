import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// What the lists keep for the life of the app: the agent filter goes with
    /// the account that set it, as `useSessions.reset()` drops it on the web.
    @Suite("Lists: feature", .serialized) @MainActor
    struct ListsFeatureTests {
        @Test func signingOutPutsTheAgentFilterBackToAllAgents() async {
            let model = MacAppModel(options: LaunchOptions(demo: true, ephemeral: true))
            defer { model.discardEphemeralState() }
            await model.restoreOrPrompt()
            model.sessions.agentFilter = "codex"
            await model.signOut()
            #expect(model.sessions.agentFilter == nil)
        }

        /// The drawer opens the conversation it made, which the page can only
        /// draw once the list holds it: the gateway announces a new session
        /// before it answers `session.create`.
        @Test func aStartedSessionIsInTheListWhenTheReplyArrives() async throws {
            let model = MacAppModel(options: LaunchOptions(demo: true, ephemeral: true))
            defer { model.discardEphemeralState() }
            await model.restoreOrPrompt()
            for _ in 0..<200 where !model.connection.hasSnapshot { try? await Task.sleep(for: .milliseconds(25)) }
            let devices = DeviceOrder.online(model.connection.devices)
            let form = NewSessionForm(devices: devices, preset: nil)
            await form.loadHome(channel: model.connection.channel)
            let device = form.device(in: devices)
            #expect(!form.cwd.isEmpty)
            let session = try #require(await form.start(device: device, agent: form.agent(of: device),
                                                        channel: model.connection.channel))
            for _ in 0..<40 where model.connection.session(deviceID: session.deviceID, sessionID: session.sessionID) == nil {
                try? await Task.sleep(for: .milliseconds(25))
            }
            #expect(model.connection.session(deviceID: session.deviceID, sessionID: session.sessionID) != nil)
            await model.signOut()
        }
    }
}
