import Foundation
import RCCore
import RCMac
import SwiftUI

/// The chat feature's scenarios: every kind of conversation the web's mock
/// gateway seeds (`web/mock/fixtures.ts`), at 1280 × 860 and at 900 wide
/// where the sidebar goes, and the states that take a click, a failure or a
/// scroll to reach. Each names the mock's session and a demo session that
/// shows the same kind of thing, so it renders on either.
enum ChatScenarios {
    static var all: [PreviewScenario] {
        conversations + states
    }

    /// One scenario per seeded session, at both widths.
    private static var conversations: [PreviewScenario] {
        let sessions: [(name: String, demo: Ids, mock: Ids)] = [
            ("running", Ids(DemoFixtures.macDeviceID, DemoFixtures.liveSessionID), Ids("dev-mac", "ses-flaky")),
            ("approval", Ids(DemoFixtures.macDeviceID, DemoFixtures.approvalSessionID), Ids("dev-mac", "ses-vite")),
            ("question", Ids(DemoFixtures.macDeviceID, DemoFixtures.sharedSessionID), Ids("dev-ci", "ses-answer")),
            ("terminal", Ids(DemoFixtures.macDeviceID, DemoFixtures.terminalSessionID),
             Ids("dev-mac", "ses-terminal")),
            ("shared", Ids(DemoFixtures.macDeviceID, DemoFixtures.sharedSessionID), Ids("dev-mac", "ses-shared")),
            ("codex-shared", Ids(DemoFixtures.macDeviceID, DemoFixtures.codexSharedSessionID),
             Ids("dev-mac", "ses-codex-shared")),
            ("limit", Ids(DemoFixtures.macDeviceID, DemoFixtures.pausedSessionID), Ids("dev-mac", "ses-limit")),
            ("errored", Ids(DemoFixtures.macDeviceID, DemoFixtures.erroredSessionID), Ids("dev-mac", "ses-crash")),
            ("done", Ids(DemoFixtures.ciDeviceID, DemoFixtures.doneSessionID), Ids("dev-ci", "ses-otlp")),
            ("grok-shared", Ids(DemoFixtures.macDeviceID, DemoFixtures.grokSharedSessionID),
             Ids("dev-mac", "ses-grok-shared")),
            ("grok-terminal", Ids(DemoFixtures.laptopDeviceID, DemoFixtures.grokSessionID),
             Ids("dev-mac", "ses-grok-terminal")),
            ("pi", Ids(DemoFixtures.macDeviceID, DemoFixtures.piSessionID), Ids("dev-mac", "ses-pi")),
            ("attach", Ids(DemoFixtures.laptopDeviceID, DemoFixtures.attachHintSessionID),
             Ids("dev-ci", "ses-attach")),
            ("exited", Ids(DemoFixtures.macDeviceID, DemoFixtures.revivedSessionID), Ids("dev-mac", "ses-exited"))
        ]
        return sessions.flatMap { session in
            [chat("chat-\(session.name)", demo: session.demo, mock: session.mock),
             chat("chat-\(session.name)-900", demo: session.demo, mock: session.mock, width: 900)]
        }
    }

    /// The states a click, a failure or a scroll reaches.
    private static var states: [PreviewScenario] {
        let running = (demo: Ids(DemoFixtures.macDeviceID, DemoFixtures.liveSessionID), mock: Ids("dev-mac", "ses-flaky"))
        let done = (demo: Ids(DemoFixtures.ciDeviceID, DemoFixtures.doneSessionID), mock: Ids("dev-ci", "ses-otlp"))
        let limit = (demo: Ids(DemoFixtures.macDeviceID, DemoFixtures.pausedSessionID), mock: Ids("dev-mac", "ses-limit"))
        return [
            chat("chat-running-simple", demo: running.demo, mock: running.mock, detail: .simple),
            chat("chat-tools-open", demo: running.demo, mock: running.mock, stage: "chat.tools.open"),
            chat("chat-todos", demo: running.demo, mock: running.mock, stage: "chat.todos"),
            chat("chat-jump", demo: running.demo, mock: running.mock, stage: "chat.jump"),
            chat("chat-markdown", demo: done.demo, mock: done.mock, height: 1400,
                 prepare: { context in await inject(markdownSample, into: context) }),
            chat("chat-markdown-900", demo: done.demo, mock: done.mock, width: 900, height: 1400,
                 prepare: { context in await inject(markdownSample, into: context) }),
            chat("chat-resume-change", demo: limit.demo, mock: limit.mock, stage: "chat.resume.change"),
            chat("chat-unconfirmed", demo: done.demo, mock: done.mock, stage: "chat.unconfirmed"),
            chat("chat-action-error", demo: done.demo, mock: done.mock, stage: "chat.error"),
            chat("chat-missing", demo: Ids(DemoFixtures.macDeviceID, "demo-session-gone"),
                 mock: Ids("dev-mac", "ses-gone")),
            chat("chat-missing-900", demo: Ids(DemoFixtures.macDeviceID, "demo-session-gone"),
                 mock: Ids("dev-mac", "ses-gone"), width: 900),
            chat("chat-running-zh", demo: running.demo, mock: running.mock, language: .zhHans),
            chat("chat-limit-zh", demo: limit.demo, mock: limit.mock, language: .zhHans),
            chat("chat-markdown-zh", demo: done.demo, mock: done.mock, height: 1400, language: .zhHans,
                 prepare: { context in await inject(markdownSample, into: context) }),
            chat("chat-missing-zh", demo: Ids(DemoFixtures.macDeviceID, "demo-session-gone"),
                 mock: Ids("dev-mac", "ses-gone"), language: .zhHans),
            chat("chat-resume-change-zh", demo: limit.demo, mock: limit.mock, stage: "chat.resume.change",
                 language: .zhHans)
        ]
    }

    struct Ids: Sendable {
        let device: String
        let session: String

        init(_ device: String, _ session: String) {
            self.device = device
            self.session = session
        }
    }

    /// A conversation route, opened on the demo or the mock by the ids that
    /// name the same kind of session on each.
    private static func chat(_ name: String, demo: Ids, mock: Ids, width: CGFloat? = nil, height: CGFloat? = nil,
                             stage: String? = nil, language: InterfaceLanguage? = nil,
                             detail: TimelineDetail = .detailed,
                             prepare: @escaping @MainActor @Sendable (PreviewContext) async -> Void = { _ in })
        -> PreviewScenario {
        PreviewScenario(name: name, width: width, height: height, stage: stage, language: language,
                        settle: .milliseconds(2500),
                        setup: { context in
                            let ids = context.gateway == nil ? demo : mock
                            // The level is the account's (A41), so every render
                            // names its own rather than inherit the last one's.
                            context.model.settings.timelineDetail = detail
                            context.model.router.replace(.chat(deviceId: ids.device, sessionId: ids.session))
                        },
                        prepare: prepare)
    }

    /// One agent message with every Markdown feature the web renders, put into
    /// the open conversation as a device would send it.
    @MainActor
    private static func inject(_ markdown: String, into context: PreviewContext) async {
        _ = await context.wait { ChatPage.conversation(in: context.model)?.timeline.historyLoaded == true }
        guard let chat = ChatPage.conversation(in: context.model) else { return }
        // The next seq after everything held, so the block lands last and the
        // transcript sees no gap to repair.
        let next = max(chat.timeline.lastSeq, chat.timeline.entries.map(\.latestSeq).max() ?? 0) + 1
        let event = SessionEvent(seq: next, ts: Format.nowMillis,
                                 kind: SessionEvent.assistantTextKind, blockID: "md-sample",
                                 body: .assistantText(StreamTextPayload(text: markdown, done: true)))
        chat.receive(.sessionEvent(sessionID: chat.sessionID, deviceID: chat.deviceID, event: event))
    }

    static let markdownSample = """
        ## Plan

        The refresh path now holds **the session lock**, and the clock is *injected* — see `auth/session.py`.

        1. Reproduce the flake with `--count 20`
        2. Isolate the shared clock
           - freeze it per test
           - drop the module-level `time.monotonic`
        3. Guard the refresh path

        - [x] Reproduce the flake locally
        - [ ] Re-run the suite 100 times

        | run | failures | time |
        | --- | ---: | :---: |
        | 20 | 1 | 6.4s |
        | 100 | 0 | 41.1s |

        > The flake only showed up on CI, where tests share a worker.

        ```python
        def refresh(self, token: str) -> Token:
            with self._lock:  # one refresh at a time
                if self._expired(token):
                    raise TokenExpired(token)
                return self._rotate(token)
        ```

        ---

        ~~Retry on failure~~ is gone. Details: https://example.com/runs/42 and [the PR](https://github.com/example/pull/7).
        """
}
