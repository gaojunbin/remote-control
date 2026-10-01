package com.junbingao.remotecontrol.win.preview.scenarios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.chat.composer.ComposerView
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LayoutSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.VStack
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The composer feature's scenarios: every state of the box at the foot of a conversation, drawn
 * where the chat page draws it — at the bottom of the white column, beside the sidebar's canvas at
 * 1024 px and wider — so a render lines up with the Mac renderer's picture of the same session on
 * the mock gateway. Under `--demo` each opens the offline demo's nearest session.
 */
object ComposerScenarios {
    /** One session of the mock gateway and its nearest one in the offline demo. */
    class Place(val mock: Pair<String, String>, val demo: Pair<String, String>) {
        companion object {
            val idle = Place(mock = "dev-mac" to "ses-push", demo = DemoFixtures.macDeviceID to DemoFixtures.erroredSessionID)
            val queued = Place(mock = "dev-mac" to "ses-vite", demo = DemoFixtures.macDeviceID to DemoFixtures.liveSessionID)
            val commands = Place(mock = "dev-ci" to "ses-otlp", demo = DemoFixtures.macDeviceID to DemoFixtures.piSessionID)
            val pi = Place(mock = "dev-mac" to "ses-pi", demo = DemoFixtures.macDeviceID to DemoFixtures.piSessionID)
            val terminal = Place(mock = "dev-mac" to "ses-terminal", demo = DemoFixtures.macDeviceID to DemoFixtures.terminalSessionID)
            val terminalCodex = Place(mock = "dev-ci" to "ses-codex-terminal", demo = DemoFixtures.laptopDeviceID to DemoFixtures.attachHintSessionID)
            val sharedClaude = Place(mock = "dev-mac" to "ses-shared", demo = DemoFixtures.macDeviceID to DemoFixtures.sharedSessionID)
            val sharedCodex = Place(mock = "dev-mac" to "ses-codex-shared", demo = DemoFixtures.macDeviceID to DemoFixtures.codexSharedSessionID)
            val running = Place(mock = "dev-mac" to "ses-flaky", demo = DemoFixtures.macDeviceID to DemoFixtures.liveSessionID)
            val question = Place(mock = "dev-ci" to "ses-answer", demo = DemoFixtures.macDeviceID to DemoFixtures.sharedSessionID)
            val offline = Place(mock = "dev-ci" to "ses-attach", demo = DemoFixtures.ciDeviceID to DemoFixtures.doneSessionID)
        }
    }

    val all: List<PreviewScenario>
        get() = states + listOf(
            composer("composer-zh", Place.idle, language = InterfaceLanguage.zhHans),
            composer("composer-typed-zh", Place.idle, stage = "typed", language = InterfaceLanguage.zhHans),
            composer("composer-upnext-zh", Place.queued, stage = "upnext", language = InterfaceLanguage.zhHans, queue = true),
            composer("composer-editing-zh", Place.queued, stage = "editing", language = InterfaceLanguage.zhHans, queue = true),
            composer("composer-listening-zh", Place.idle, stage = "listening", language = InterfaceLanguage.zhHans, settle = 1.seconds),
            composer("composer-terminal-zh", Place.terminalCodex, language = InterfaceLanguage.zhHans),
            composer("composer-900", Place.idle, width = 900),
            composer("composer-model-900", Place.idle, stage = "model", width = 900),
            composer("composer-upnext-900", Place.queued, stage = "upnext", width = 900, queue = true),
            composer("composer-listening-900", Place.idle, stage = "listening", width = 900, settle = 1.seconds),
            composer("composer-480", Place.idle, stage = "typed", width = 480),
        )

    /** Every state the brief lists, in English at 1280. */
    private val states: List<PreviewScenario>
        get() = listOf(
            composer("composer-empty", Place.idle),
            composer("composer-typed", Place.idle, stage = "typed"),
            composer("composer-long", Place.idle, stage = "long"),
            composer("composer-attachments", Place.idle, stage = "attachments"),
            composer("composer-too-many", Place.idle, stage = "too-many", settle = 1.seconds),
            composer("composer-commands", Place.commands, stage = "commands"),
            composer("composer-commands-query", Place.commands, stage = "commands-query"),
            composer("composer-command-hint", Place.commands, stage = "command-hint"),
            composer("composer-commands-pi", Place.pi, stage = "commands"),
            composer("composer-model", Place.idle, stage = "model"),
            composer("composer-model-list", Place.idle, stage = "model-list"),
            composer("composer-model-codex", Place.commands, stage = "model"),
            composer("composer-permissions", Place.idle, stage = "permissions"),
            composer("composer-upnext", Place.queued, queue = true),
            composer("composer-upnext-list", Place.queued, stage = "upnext", queue = true),
            composer("composer-editing", Place.queued, stage = "editing", queue = true),
            composer("composer-send-menu", Place.running, stage = "send-menu"),
            composer("composer-answer", Place.question, stage = "answer", settle = 2.seconds),
            composer("composer-listening", Place.idle, stage = "listening", settle = 1.seconds),
            composer("composer-finishing", Place.idle, stage = "finishing", settle = 1200.milliseconds),
            composer("composer-polishing", Place.idle, stage = "polishing", settle = 1600.milliseconds),
            composer("composer-polished", Place.idle, stage = "polished", settle = 2600.milliseconds),
            composer("composer-polish-failed", Place.idle, stage = "polish-failed", settle = 1600.milliseconds),
            composer("composer-voice-error", Place.idle, stage = "voice-error"),
            composer("composer-terminal", Place.terminal),
            composer("composer-terminal-codex", Place.terminalCodex),
            composer("composer-shared-claude", Place.sharedClaude),
            composer("composer-shared-codex", Place.sharedCodex),
            composer("composer-offline", Place.offline),
        )

    private fun composer(
        name: String,
        place: Place,
        stage: String? = null,
        width: Int? = null,
        height: Int? = null,
        language: InterfaceLanguage? = null,
        settle: Duration = 900.milliseconds,
        queue: Boolean = false,
    ): PreviewScenario = PreviewScenario(
        name = name,
        width = width,
        height = height,
        stage = stage?.let { "composer.$it" },
        language = language,
        settle = settle,
        setup = { context ->
            val (device, session) = if (context.gateway == null) place.demo else place.mock
            val chat = context.openChat(deviceId = device, sessionId = session)
            // The mock gateway seeds its queue; the demo holds what a running turn is sent, so it is
            // given three.
            if (queue && context.gateway == null && chat != null) seedQueue(chat)
        },
        content = { context -> context.chat?.let { ComposerStandIn(it) } },
    )

    private suspend fun seedQueue(chat: ChatStore) {
        for (text in listOf(
            "Then run the full test suite.",
            "After that, bump Vite in the lockfile and rebuild the dev server.",
            "And update the changelog.",
        )) {
            chat.draft = text
            chat.send()
        }
    }
}

/** The chat page's frame around the composer: the sidebar's canvas at 1024 px and wider, and the white column the composer sits at the foot of. */
@Composable
private fun ComposerStandIn(chat: ChatStore) {
    val layout = LocalLayoutClass.current
    HStack(Modifier.fillMaxSize(), spacing = 0.dp) {
        if (!layout.maxWidth1023) Box(Modifier.width(LayoutSize.sidebarW).fillMaxHeight().background(Palette.canvas))
        VStack(Modifier.weight(1f).fillMaxHeight().background(Palette.surface), spacing = 0.dp) {
            Spacer(Modifier.weight(1f))
            ComposerView(chat)
        }
    }
}
