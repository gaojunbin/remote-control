package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.demo.DemoGateway
import com.junbingao.remotecontrol.core.demo.demoGateway
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Command
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.SessionEvent
import com.junbingao.remotecontrol.core.protocol.SessionEventBody
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

// The wire cases of RCCore's suite of this name are in `protocol/SlashCommandTests.kt`, and
// `unknownNameIsRefused`, which asks the demo gateway alone, is in `demo/SlashCommandTests.kt`.

/**
 * Amendment A27: the terminal's `/` menu, on the phone. What is checked here is the rule the panel,
 * the hint line and Send all read — whether a draft is a command at all, which rows it leaves on
 * screen, and what the store does with it — plus the frames that carry it.
 */
class SlashCommandTests {
    // What a draft means

    /** The slash has to be the first character, or the draft is prose. */
    @Test
    fun onlyALeadingSlash() {
        assertNull(SlashDraft.parse("look in /etc/hosts"))
        assertNull(SlashDraft.parse(" /compact"))
        assertNull(SlashDraft.parse(""))
        assertNotNull(SlashDraft.parse("/"))
    }

    /** A bare slash opens the whole list; letters after it are the filter. */
    @Test
    fun namePartsOfADraft() {
        val bare = assertNotNull(SlashDraft.parse("/"))
        assertTrue(bare.name.isEmpty())
        assertFalse(bare.isComplete)
        assertNull(bare.argument)

        val typing = assertNotNull(SlashDraft.parse("/rev"))
        assertEquals("rev", typing.name)
        assertFalse(typing.isComplete)
    }

    /** A space finishes the name, and everything after it is the argument. */
    @Test
    fun argumentOfADraft() {
        val empty = assertNotNull(SlashDraft.parse("/review "))
        assertEquals("review", empty.name)
        assertTrue(empty.isComplete)
        assertNull(empty.argument, "a trailing space is where the argument goes, not an argument")

        val written = assertNotNull(SlashDraft.parse("/review  focus on the retry logic "))
        assertEquals("review", written.name)
        assertTrue(written.isComplete)
        assertEquals("focus on the retry logic", written.argument)
    }

    // What the panel shows

    private val pi: List<Command> get() = DemoFixtures.piCommands

    /** Rows are filtered by prefix of the name, whatever the phone capitalised. */
    @Test
    fun filtering() {
        assertEquals(pi.size, SlashDraft.filter(pi, query = "").size)
        assertEquals(listOf("skill:pdf-tables", "skill:web-research", "skill:screenshot"),
                     SlashDraft.filter(pi, query = "skill:").map { it.name })
        assertEquals(listOf("compact"), SlashDraft.filter(pi, query = "Comp").map { it.name })
        assertTrue(SlashDraft.filter(pi, query = "notes").isEmpty(), "a prefix, not a search: the middle of a name matches nothing")
    }

    /** A name is matched whole, and case is not what tells two commands apart. */
    @Test
    fun matching() {
        assertEquals("compact", SlashDraft.match(pi, name = "compact")?.name)
        assertEquals("compact", SlashDraft.match(pi, name = "Compact")?.name)
        assertNull(SlashDraft.match(pi, name = "comp"))
    }

    /** Headers are drawn only where there is more than one group. */
    @Test
    fun sectioning() {
        val grouped = CommandSection.build(pi)
        assertEquals(listOf("Prompts", "Skills", "Extensions", "Built-in"), grouped.map { it.title })
        assertEquals(pi.size, grouped.sumOf { it.commands.size })

        val oneSource = CommandSection.build(DemoFixtures.codexCommands)
        assertEquals(1, oneSource.size)
        assertNull(oneSource.firstOrNull()?.title, "one source names nothing the list does not say")
        assertTrue(CommandSection.build(emptyList()).isEmpty())
    }

    // The store

    /** A pi session offers its groups, and the panel closes on a finished name. */
    @Test
    fun piSessionOffersItsCommands() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(DemoFixtures.piSessionID, agent = DemoFixtures.pi, gateway = gateway)
        assertTrue(chat.offersCommands)
        chat.loadCommands()
        assertEquals(DemoFixtures.piCommands.size, chat.commands.size)

        chat.draft = "/"
        assertEquals(DemoFixtures.piCommands.size, chat.commandRows.size)
        assertEquals(listOf("Prompts", "Skills", "Extensions", "Built-in"), chat.commandSections.map { it.title })

        chat.draft = "/rel"
        assertEquals(listOf("release-notes"), chat.commandRows.map { it.name })
        assertNull(chat.draftCommand, "a half-typed name runs nothing")

        val row = assertNotNull(chat.commandRows.firstOrNull())
        chat.take(row)
        assertEquals("/release-notes ", chat.draft,
                     "a command that takes an argument is written with the space that shows where it goes")
        assertTrue(chat.commandRows.isEmpty(), "which closes the panel")
        assertEquals("release-notes", chat.commandHint?.name, "and hands over to the hint line")
        assertEquals("release-notes", chat.draftCommand?.name)

        chat.take(assertNotNull(SlashDraft.match(chat.commands, name = "changelog")))
        assertEquals("/changelog", chat.draft, "one that takes nothing is left ready to run")
    }

    /**
     * Claude offers /compact and nothing else. Amendment A40: Claude's panel holds exactly the one
     * command the device can type into the terminal the shim gives it.
     */
    @Test
    fun claudeOffersOneCommand() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(DemoFixtures.liveSessionID, agent = DemoFixtures.claude, gateway = gateway)
        assertTrue(chat.offersCommands)
        chat.loadCommands()
        assertEquals(listOf("compact"), chat.commands.map { it.name })
        assertEquals(false, chat.commands.firstOrNull()?.takesArgument, "and it takes no argument")

        chat.draft = "/comp"
        assertEquals(listOf("compact"), chat.commandRows.map { it.name }, "which the panel names")
        chat.draft = "/compact"
        assertEquals("compact", chat.draftCommand?.name, "and Send runs")
    }

    /**
     * The same agent without the shim draws no panel at all. The capability is the shim's, not the
     * agent's: a machine without it has no terminal of the device's own to type into (A40).
     */
    @Test
    fun claudeWithoutShimOffersNothing() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(DemoFixtures.liveSessionID, agent = DemoFixtures.claudeWithoutShim, gateway = gateway)
        assertFalse(chat.offersCommands)
        chat.loadCommands()
        assertTrue(chat.commands.isEmpty(), "the app never even asks")

        chat.draft = "/compact"
        assertNull(chat.commandDraft)
        assertTrue(chat.commandRows.isEmpty())
        assertNull(chat.draftCommand)
    }

    /** A session the terminal holds draws no panel either. */
    @Test
    fun terminalSessionDrawsNoPanel() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(DemoFixtures.grokSessionID, agent = DemoFixtures.grok, gateway = gateway)
        chat.loadCommands()
        assertEquals(DemoFixtures.grokCommands.size, chat.commands.size, "the device still says what the session offers")
        chat.draft = "/hooks"
        assertNull(chat.commandDraft, "but nothing here can type into it")
        assertTrue(chat.commandRows.isEmpty())

        assertFailsWith<GatewayErrorBody> {
            gateway.request(GatewayRequest.command(sessionID = DemoFixtures.grokSessionID, name = "hooks-list"))
        }
    }

    /** The row is in the transcript before the device has answered. */
    @Test
    fun theRowIsDrawnAtOnce() = runTest {
        // No frame handler here, so nothing the device sends is applied and the optimistic row is all
        // there is to look at.
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(DemoFixtures.piSessionID, agent = DemoFixtures.pi, gateway = gateway)
        chat.loadCommands()

        chat.draft = "/compact keep the decisions"
        assertEquals("compact", chat.draftCommand?.name)
        assertTrue(chat.canSend)
        chat.runCommand()
        assertTrue(chat.draft.isEmpty(), "the field is cleared the way sending clears it")
        assertNull(chat.errorMessage)
        assertEquals("/compact keep the decisions", chat.timeline.roots.lastOrNull()?.pending?.text)
    }

    /** Running one echoes it once and reports what it did. */
    @Test
    fun runningACommand() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(DemoFixtures.piSessionID, agent = DemoFixtures.pi, gateway = gateway)
        pump(gateway, into = chat)
        chat.loadCommands()

        chat.draft = "/compact keep the decisions"
        chat.runCommand()
        settle { chat.timeline.optimistic.isEmpty() }
        assertEquals(1, chat.timeline.roots.count { it.userMessage?.text == "/compact keep the decisions" },
                     "the device's echo replaces the row under the same id")
        settle { chat.timeline.roots.any { it.notice != null } }
        assertTrue(chat.timeline.roots.any { it.notice?.text?.contains("compacted") == true },
                   "and a state change reads as a notice rather than as a message")
    }

    /** A turn has to finish first, and nothing leaves the field until it has. */
    @Test
    fun aCommandWaitsForTheTurn() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(DemoFixtures.codexSharedSessionID, agent = DemoFixtures.codex, gateway = gateway)
        pump(gateway, into = chat)
        chat.loadCommands()
        assertEquals(DemoFixtures.codexCommands.map { it.name }, chat.commands.map { it.name })

        chat.draft = "/"
        assertEquals(1, chat.commandSections.size, "Codex has one source, so the list has no headers")
        assertNull(chat.commandSections.firstOrNull()?.title)

        chat.draft = "/usage"
        assertTrue(chat.isRunning)
        assertTrue(chat.commandsWaitForTurn, "the rows are dimmed and the card says why")
        assertFalse(chat.commandRows.isEmpty(), "but the list is still readable")
        assertFalse(chat.canSend, "and Send does not act")
        chat.runCommand()
        assertEquals("/usage", chat.draft, "nothing left the field")
        assertTrue(chat.timeline.optimistic.isEmpty(), "and nothing was drawn in the transcript")

        // The turn ends, and the same draft runs.
        chat.stop()
        settle { !chat.isRunning }
        assertTrue(chat.canSend)
        chat.runCommand()
        assertNull(chat.errorMessage)
        assertTrue(chat.draft.isEmpty())
    }

    /** Information a terminal would have printed arrives as a tool call. */
    @Test
    fun informationReadsAsAToolCall() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(DemoFixtures.codexSharedSessionID, agent = DemoFixtures.codex, gateway = gateway)
        pump(gateway, into = chat)
        chat.loadCommands()
        chat.stop()
        settle { !chat.isRunning }

        chat.draft = "/usage"
        chat.runCommand()
        settle { chat.timeline.roots.any { it.toolCall != null } }
        val call = assertNotNull(chat.timeline.roots.mapNotNull { it.toolCall }.lastOrNull())
        assertEquals("/usage", call.tool)
        assertEquals("/usage", call.title)
        assertEquals(ToolKind.other, call.kind)
        assertEquals(ToolStatus.succeeded, call.status)
        assertEquals(true, call.output?.contains("5-hour window"))
    }

    /** A command's own output is drawn at Simple, and the agent's working is not. */
    @Test
    fun commandOutputSurvivesTheSimpleLevel() {
        fun entry(tool: String): TimelineEntry {
            val timeline = Timeline()
            timeline.apply(SessionEvent(seq = 1, ts = 0, kind = SessionEvent.toolCallKind, blockID = "b",
                                        body = SessionEventBody.ToolCall(ToolCallPayload(tool = tool, kind = ToolKind.other,
                                                                                         title = tool,
                                                                                         status = ToolStatus.succeeded,
                                                                                         output = "…"))))
            return timeline.entries[0]
        }
        assertTrue(entry(tool = "/usage").isDrawn(at = TimelineDetail.simple), "the reader asked for this by name, so Simple keeps it")
        assertTrue(entry(tool = "/usage").isDrawn(at = TimelineDetail.detailed))
        assertFalse(entry(tool = "Bash").isDrawn(at = TimelineDetail.simple), "an ordinary tool call is still the agent's working")
    }

    /** The list is asked for again only once it has gone stale. */
    @Test
    fun refreshRule() = runTest {
        val gateway = demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)
        val chat = chat(DemoFixtures.piSessionID, agent = DemoFixtures.pi, gateway = gateway)
        chat.loadCommands()
        val first = chat.commands

        chat.refreshCommands()
        assertEquals(first.size, chat.commands.size, "a fresh answer stands")
        chat.refreshCommands(now = Instant.now().plus((ChatStore.commandsStaleAfter + 1.seconds).toJavaDuration()))
        assertEquals(first.size, chat.commands.size, "and a stale one is replaced by the next")
    }

    // Helpers

    private fun TestScope.chat(sessionID: String, agent: AgentInfo, gateway: DemoGateway): ChatStore {
        val session = assertNotNull(DemoFixtures.sessions.firstOrNull { it.sessionID == sessionID })
        val chat = ChatStore(session = session, channel = gateway, tasks = backgroundScope)
        chat.agent = agent
        return chat
    }

    private fun TestScope.pump(gateway: DemoGateway, into: ChatStore): Job = backgroundScope.launch {
        gateway.events.collect { event -> if (event is GatewayEvent.Frame) into.receive(event.frame) }
    }
}
