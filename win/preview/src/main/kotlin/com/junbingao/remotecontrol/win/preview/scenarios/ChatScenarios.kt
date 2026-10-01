package com.junbingao.remotecontrol.win.preview.scenarios

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.StreamTextPayload
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.chat.ChatPage
import com.junbingao.remotecontrol.win.shared.Format
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * The chat feature's scenarios: every kind of conversation the web's mock gateway seeds
 * (`web/mock/fixtures.ts`), at 1280 × 860 and at 900 wide where the sidebar goes, and the states
 * that take a click, a failure or a scroll to reach. Each names the mock's session and a demo
 * session that shows the same kind of thing, so it renders on either.
 */
object ChatScenarios {
    val all: List<PreviewScenario> get() = conversations + states

    /** One scenario per seeded session, at both widths. */
    private val conversations: List<PreviewScenario>
        get() {
            val sessions = listOf(
                Triple("running", Ids(DemoFixtures.macDeviceID, DemoFixtures.liveSessionID), Ids("dev-mac", "ses-flaky")),
                Triple("approval", Ids(DemoFixtures.macDeviceID, DemoFixtures.approvalSessionID), Ids("dev-mac", "ses-vite")),
                Triple("question", Ids(DemoFixtures.macDeviceID, DemoFixtures.sharedSessionID), Ids("dev-ci", "ses-answer")),
                Triple("terminal", Ids(DemoFixtures.macDeviceID, DemoFixtures.terminalSessionID), Ids("dev-mac", "ses-terminal")),
                Triple("shared", Ids(DemoFixtures.macDeviceID, DemoFixtures.sharedSessionID), Ids("dev-mac", "ses-shared")),
                Triple("codex-shared", Ids(DemoFixtures.macDeviceID, DemoFixtures.codexSharedSessionID), Ids("dev-mac", "ses-codex-shared")),
                Triple("limit", Ids(DemoFixtures.macDeviceID, DemoFixtures.pausedSessionID), Ids("dev-mac", "ses-limit")),
                Triple("errored", Ids(DemoFixtures.macDeviceID, DemoFixtures.erroredSessionID), Ids("dev-mac", "ses-crash")),
                Triple("done", Ids(DemoFixtures.ciDeviceID, DemoFixtures.doneSessionID), Ids("dev-ci", "ses-otlp")),
                Triple("grok-shared", Ids(DemoFixtures.macDeviceID, DemoFixtures.grokSharedSessionID), Ids("dev-mac", "ses-grok-shared")),
                Triple("grok-terminal", Ids(DemoFixtures.laptopDeviceID, DemoFixtures.grokSessionID), Ids("dev-mac", "ses-grok-terminal")),
                Triple("pi", Ids(DemoFixtures.macDeviceID, DemoFixtures.piSessionID), Ids("dev-mac", "ses-pi")),
                Triple("attach", Ids(DemoFixtures.laptopDeviceID, DemoFixtures.attachHintSessionID), Ids("dev-ci", "ses-attach")),
                Triple("exited", Ids(DemoFixtures.macDeviceID, DemoFixtures.revivedSessionID), Ids("dev-mac", "ses-exited")),
            )
            return sessions.flatMap { (name, demo, mock) ->
                listOf(chat("chat-$name", demo, mock), chat("chat-$name-900", demo, mock, width = 900))
            }
        }

    /** The states a click, a failure or a scroll reaches. */
    private val states: List<PreviewScenario>
        get() {
            val running = Ids(DemoFixtures.macDeviceID, DemoFixtures.liveSessionID) to Ids("dev-mac", "ses-flaky")
            val done = Ids(DemoFixtures.ciDeviceID, DemoFixtures.doneSessionID) to Ids("dev-ci", "ses-otlp")
            val limit = Ids(DemoFixtures.macDeviceID, DemoFixtures.pausedSessionID) to Ids("dev-mac", "ses-limit")
            val missing = Ids(DemoFixtures.macDeviceID, "demo-session-gone") to Ids("dev-mac", "ses-gone")
            val markdown: suspend (PreviewContext) -> Unit = { context -> inject(markdownSample, context) }
            return listOf(
                chat("chat-running-simple", running, detail = TimelineDetail.simple),
                chat("chat-tools-open", running, stage = "chat.tools.open"),
                chat("chat-todos", running, stage = "chat.todos"),
                chat("chat-jump", running, stage = "chat.jump"),
                chat("chat-markdown", done, height = 1400, prepare = markdown),
                chat("chat-markdown-900", done, width = 900, height = 1400, prepare = markdown),
                chat("chat-resume-change", limit, stage = "chat.resume.change"),
                chat("chat-unconfirmed", done, stage = "chat.unconfirmed"),
                chat("chat-action-error", done, stage = "chat.error"),
                chat("chat-missing", missing),
                chat("chat-missing-900", missing, width = 900),
                chat("chat-running-zh", running, language = InterfaceLanguage.zhHans),
                chat("chat-limit-zh", limit, language = InterfaceLanguage.zhHans),
                chat("chat-markdown-zh", done, height = 1400, language = InterfaceLanguage.zhHans, prepare = markdown),
                chat("chat-missing-zh", missing, language = InterfaceLanguage.zhHans),
                chat("chat-resume-change-zh", limit, stage = "chat.resume.change", language = InterfaceLanguage.zhHans),
            )
        }

    data class Ids(val device: String, val session: String)

    private fun chat(
        name: String,
        ids: Pair<Ids, Ids>,
        width: Int? = null,
        height: Int? = null,
        stage: String? = null,
        language: InterfaceLanguage? = null,
        detail: TimelineDetail = TimelineDetail.detailed,
        prepare: suspend (PreviewContext) -> Unit = {},
    ): PreviewScenario = chat(name, ids.first, ids.second, width, height, stage, language, detail, prepare)

    /** A conversation route, opened on the demo or the mock by the ids that name the same kind of session on each. */
    private fun chat(
        name: String,
        demo: Ids,
        mock: Ids,
        width: Int? = null,
        height: Int? = null,
        stage: String? = null,
        language: InterfaceLanguage? = null,
        detail: TimelineDetail = TimelineDetail.detailed,
        prepare: suspend (PreviewContext) -> Unit = {},
    ): PreviewScenario = PreviewScenario(
        name,
        width = width,
        height = height,
        stage = stage,
        language = language,
        settle = 2500.milliseconds,
        setup = { context ->
            val ids = if (context.gateway == null) demo else mock
            // The level is the account's (A41), so every render names its own rather than
            // inherit the last one's.
            context.model.settings.timelineDetail = detail
            context.model.router.replace(Route.Chat(deviceId = ids.device, sessionId = ids.session))
        },
        prepare = prepare,
    )

    /** One agent message with every Markdown feature the web renders, put into the open conversation as a device would send it. */
    private suspend fun inject(markdown: String, context: PreviewContext) {
        context.wait { ChatPage.conversation(context.model)?.timeline?.historyLoaded == true }
        val chat = ChatPage.conversation(context.model) ?: return
        // The next seq after everything held, so the block lands last and the transcript sees no
        // gap to repair.
        val next = max(chat.timeline.lastSeq, chat.timeline.entries.maxOfOrNull { it.latestSeq } ?: 0) + 1
        val event = SessionEvent(
            seq = next,
            ts = Format.nowMillis,
            kind = SessionEvent.assistantTextKind,
            blockID = "md-sample",
            body = SessionEventBody.AssistantText(StreamTextPayload(text = markdown, done = true)),
        )
        chat.receive(AppFrame.SessionEvent(sessionID = chat.sessionID, deviceID = chat.deviceID, event = event))
    }

    val markdownSample = """
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
    """.trimIndent()
}
