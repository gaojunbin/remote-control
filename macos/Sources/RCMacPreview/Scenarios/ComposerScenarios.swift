import RCCore
import RCMac
import SwiftUI

/// The composer feature's scenarios: every state of the box at the foot of a
/// conversation, drawn where the chat page draws it — at the bottom of the
/// white column, beside the sidebar's canvas at 1024 points and wider — so a
/// render lines up with the web's screenshot of the same session on the mock
/// gateway. Under `--demo` each opens the offline demo's nearest session.
enum ComposerScenarios {
    /// One session of the mock gateway and its nearest one in the offline demo.
    struct Place: Sendable {
        let mock: (device: String, session: String)
        let demo: (device: String, session: String)

        static let idle = Place(mock: ("dev-mac", "ses-push"),
                                demo: (DemoFixtures.macDeviceID, DemoFixtures.erroredSessionID))
        static let queued = Place(mock: ("dev-mac", "ses-vite"),
                                  demo: (DemoFixtures.macDeviceID, DemoFixtures.liveSessionID))
        static let commands = Place(mock: ("dev-ci", "ses-otlp"),
                                    demo: (DemoFixtures.macDeviceID, DemoFixtures.piSessionID))
        static let pi = Place(mock: ("dev-mac", "ses-pi"), demo: (DemoFixtures.macDeviceID, DemoFixtures.piSessionID))
        static let terminal = Place(mock: ("dev-mac", "ses-terminal"),
                                    demo: (DemoFixtures.macDeviceID, DemoFixtures.terminalSessionID))
        static let terminalCodex = Place(mock: ("dev-ci", "ses-codex-terminal"),
                                         demo: (DemoFixtures.laptopDeviceID, DemoFixtures.attachHintSessionID))
        static let sharedClaude = Place(mock: ("dev-mac", "ses-shared"),
                                        demo: (DemoFixtures.macDeviceID, DemoFixtures.sharedSessionID))
        static let sharedCodex = Place(mock: ("dev-mac", "ses-codex-shared"),
                                       demo: (DemoFixtures.macDeviceID, DemoFixtures.codexSharedSessionID))
        static let running = Place(mock: ("dev-mac", "ses-flaky"),
                                   demo: (DemoFixtures.macDeviceID, DemoFixtures.liveSessionID))
        static let question = Place(mock: ("dev-ci", "ses-answer"),
                                    demo: (DemoFixtures.macDeviceID, DemoFixtures.sharedSessionID))
        static let offline = Place(mock: ("dev-ci", "ses-attach"),
                                   demo: (DemoFixtures.ciDeviceID, DemoFixtures.doneSessionID))
    }

    static var all: [PreviewScenario] {
        states + [
            composer("composer-zh", .idle, language: .zhHans),
            composer("composer-typed-zh", .idle, stage: "typed", language: .zhHans),
            composer("composer-upnext-zh", .queued, stage: "upnext", language: .zhHans, queue: true),
            composer("composer-editing-zh", .queued, stage: "editing", language: .zhHans, queue: true),
            composer("composer-listening-zh", .idle, stage: "listening", language: .zhHans, settle: .seconds(1)),
            composer("composer-terminal-zh", .terminalCodex, language: .zhHans),
            composer("composer-900", .idle, width: 900),
            composer("composer-model-900", .idle, stage: "model", width: 900),
            composer("composer-upnext-900", .queued, stage: "upnext", width: 900, queue: true),
            composer("composer-listening-900", .idle, stage: "listening", width: 900, settle: .seconds(1)),
            composer("composer-480", .idle, stage: "typed", width: 480)
        ]
    }

    /// Every state the brief lists, in English at 1280.
    private static var states: [PreviewScenario] {
        [
            composer("composer-empty", .idle),
            composer("composer-typed", .idle, stage: "typed"),
            composer("composer-long", .idle, stage: "long"),
            composer("composer-attachments", .idle, stage: "attachments"),
            composer("composer-too-many", .idle, stage: "too-many", settle: .seconds(1)),
            composer("composer-commands", .commands, stage: "commands"),
            composer("composer-commands-query", .commands, stage: "commands-query"),
            composer("composer-command-hint", .commands, stage: "command-hint"),
            composer("composer-commands-pi", .pi, stage: "commands"),
            composer("composer-model", .idle, stage: "model"),
            composer("composer-model-list", .idle, stage: "model-list"),
            composer("composer-model-codex", .commands, stage: "model"),
            composer("composer-permissions", .idle, stage: "permissions"),
            composer("composer-upnext", .queued, queue: true),
            composer("composer-upnext-list", .queued, stage: "upnext", queue: true),
            composer("composer-editing", .queued, stage: "editing", queue: true),
            composer("composer-send-menu", .running, stage: "send-menu"),
            composer("composer-answer", .question, stage: "answer", settle: .seconds(2)),
            composer("composer-listening", .idle, stage: "listening", settle: .seconds(1)),
            composer("composer-finishing", .idle, stage: "finishing", settle: .seconds(1.2)),
            composer("composer-polishing", .idle, stage: "polishing", settle: .seconds(1.6)),
            composer("composer-polished", .idle, stage: "polished", settle: .seconds(2.6)),
            composer("composer-polish-failed", .idle, stage: "polish-failed", settle: .seconds(1.6)),
            composer("composer-voice-error", .idle, stage: "voice-error"),
            composer("composer-terminal", .terminal),
            composer("composer-terminal-codex", .terminalCodex),
            composer("composer-shared-claude", .sharedClaude),
            composer("composer-shared-codex", .sharedCodex),
            composer("composer-offline", .offline)
        ]
    }

    private static func composer(_ name: String, _ place: Place, stage: String? = nil,
                                 width: CGFloat? = nil, height: CGFloat? = nil,
                                 language: InterfaceLanguage? = nil, settle: Duration = .milliseconds(900),
                                 queue: Bool = false) -> PreviewScenario {
        PreviewScenario(name: name, width: width, height: height, stage: stage.map { "composer.\($0)" },
                        language: language, settle: settle,
                        setup: { context in
                            let target = context.gateway == nil ? place.demo : place.mock
                            let chat = await context.openChat(deviceId: target.device, sessionId: target.session)
                            // The mock gateway seeds its queue; the demo holds
                            // what a running turn is sent, so it is given three.
                            if queue, context.gateway == nil, let chat { await seedQueue(chat) }
                        },
                        content: { context in
                            AnyView(context.chat.map { ComposerStandIn(chat: $0) })
                        })
    }

    @MainActor private static func seedQueue(_ chat: ChatStore) async {
        for text in ["Then run the full test suite.",
                     "After that, bump Vite in the lockfile and rebuild the dev server.",
                     "And update the changelog."] {
            chat.draft = text
            await chat.send()
        }
    }
}

/// The chat page's frame around the composer: the sidebar's canvas at 1024
/// points and wider, and the white column the composer sits at the foot of.
private struct ComposerStandIn: View {
    let chat: ChatStore
    @Environment(\.layoutClass) private var layout

    var body: some View {
        HStack(spacing: 0) {
            if !layout.maxWidth1023 {
                Palette.canvas.frame(width: LayoutSize.sidebarW)
            }
            VStack(spacing: 0) {
                Spacer(minLength: 0)
                ComposerView(chat: chat)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.surface)
        }
    }
}
