import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/TerminalPage.test.tsx`, on the demo gateway's shell: the page's
    /// rules on top of RCCore's `TerminalSession`.
    @Suite("Terminal", .serialized) @MainActor
    struct TerminalTests {
        init() { InterfaceLanguageSource.shared.current = .en }

        private func demo() async -> MacAppModel {
            let model = MacAppModel(options: LaunchOptions(demo: true, ephemeral: true))
            await model.restoreOrPrompt()
            _ = await wait { model.connection.hasSnapshot }
            return model
        }

        private func wait(_ condition: () -> Bool) async -> Bool {
            for _ in 0..<200 {
                if condition() { return true }
                try? await Task.sleep(for: .milliseconds(25))
            }
            return condition()
        }

        /// A screen on the demo's Mac with a stand-in for the emulator.
        private func screen(on model: MacAppModel, device: String = DemoFixtures.macDeviceID)
            -> (TerminalScreen, TerminalWrites) {
            let screen = TerminalScreen(deviceID: device, model: model)
            let written = TerminalWrites()
            screen.feed.writer = { written.bytes.append($0) }
            return (screen, written)
        }

        @Test func itWaitsForTheEmulatorsSizeThenDrawsWhatTheShellWrites() async {
            let model = await demo()
            defer { model.discardEphemeralState() }
            let (screen, written) = screen(on: model)
            screen.show()
            #expect(screen.status == .connecting && !screen.started)
            screen.resized(TerminalSize(cols: 100, rows: 30))
            #expect(await wait { screen.status == .connected })
            #expect(await wait { written.text.contains("demo:~$ ") })
            #expect(screen.started)
            screen.type(Data("echo hi\r".utf8))
            #expect(await wait { written.text.contains("echo hi") })
            screen.close()
            await model.signOut()
        }

        @Test func theShellsEndIsSaidWithItsCodeAndANewShellStartsAnother() async {
            let model = await demo()
            defer { model.discardEphemeralState() }
            let (screen, written) = screen(on: model)
            screen.resized(TerminalSize(cols: 80, rows: 24))
            screen.show()
            #expect(await wait { screen.status == .connected })
            screen.type(Data("exit\r".utf8))
            #expect(await wait { screen.status == .exited })
            #expect(screen.exitCode == 0)
            let before = written.bytes.count
            screen.restart()
            #expect(await wait { screen.status == .connected })
            #expect(await wait { written.bytes.count > before })
            // The shell that ended stays on screen above the new one's prompt.
            #expect(written.bytes.range(of: TerminalFeed.fullReset) == nil)
            screen.close()
            await model.signOut()
        }

        @Test func aDeviceThatOffersNoShellIsNeverAskedForOne() async {
            let model = await demo()
            defer { model.discardEphemeralState() }
            let (screen, _) = screen(on: model, device: DemoFixtures.ciDeviceID)
            screen.show()
            screen.resized(TerminalSize(cols: 80, rows: 24))
            try? await Task.sleep(for: .milliseconds(300))
            #expect(!screen.available)
            #expect(screen.status == .connecting && !screen.started && screen.reason == nil)
            screen.close()
            await model.signOut()
        }

        @Test func aRefusalSaysWhyAndReconnectTriesAgain() async {
            let model = await demo()
            defer { model.discardEphemeralState() }
            // The device runs four at most; the fifth is refused.
            var open: [TerminalScreen] = []
            for _ in 0..<4 {
                let (screen, _) = screen(on: model)
                screen.show()
                screen.resized(TerminalSize(cols: 80, rows: 24))
                _ = await wait { screen.status == .connected }
                open.append(screen)
            }
            let (fifth, _) = screen(on: model)
            fifth.show()
            fifth.resized(TerminalSize(cols: 80, rows: 24))
            #expect(await wait { fifth.status == .disconnected })
            #expect(fifth.reason == S.errors.conflictTerminal)
            open.removeFirst().close()
            try? await Task.sleep(for: .milliseconds(200))
            fifth.connect()
            #expect(await wait { fifth.status == .connected })
            #expect(fifth.reason == nil)
            fifth.close()
            open.forEach { $0.close() }
            await model.signOut()
        }

        @Test func aSignOutEndsTheShellsThePagesHold() async {
            let model = await demo()
            defer { model.discardEphemeralState() }
            let (screen, _) = screen(on: model)
            screen.show()
            screen.resized(TerminalSize(cols: 80, rows: 24))
            #expect(await wait { screen.status == .connected })
            await model.signOut()
            // Closed: nothing starts it again, whatever the page asks.
            screen.connect()
            try? await Task.sleep(for: .milliseconds(200))
            #expect(!screen.socketOpen)
            #expect(screen.status == .disconnected)
        }
    }
}

/// What the emulator was fed.
@MainActor
final class TerminalWrites {
    var bytes = Data()
    var text: String { String(decoding: bytes, as: UTF8.self) }
}
