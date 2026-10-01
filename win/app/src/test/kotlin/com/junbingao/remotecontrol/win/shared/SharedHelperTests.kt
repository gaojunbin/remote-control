package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.core.protocol.Command
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QuestionOption
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.protocol.SpeedChange
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.QuestionDraft
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `web/tests/commands.test.ts`, case for case on the mock gateway's own lists (`commandsFor` in
 * `web/mock/fixtures.ts`), and the A27 cases of `web/tests/protocol-fixtures.test.ts` on the
 * protocol's worked list.
 */
class SlashCommandTests {
    private val codex = listOf(
        Command(name = "compact", description = "Summarise the conversation to free up context"),
        Command(name = "review", description = "Review the working tree's changes and report issues", argument = "instructions"),
        Command(name = "init", description = "Write an AGENTS.md for this repository"),
        Command(name = "status", description = "Show the session's model, settings and token use"),
        Command(name = "usage", description = "Show account usage and when the limits reset"),
        Command(name = "skills", description = "List the skills this session can use"),
        Command(name = "hooks", description = "List the lifecycle hooks that are installed"),
        Command(name = "mcp", description = "List the MCP servers and the tools they bring"),
    )
    private val pi = listOf(
        Command(name = "compact", description = "Summarise the conversation to free up context", argument = "instructions", group = "Built-in"),
        Command(name = "release-notes", description = "Draft release notes from the commits since the last tag", argument = "tag", group = "Prompts"),
        Command(name = "refactor-plan", description = "Plan a refactor before touching the code", argument = "area", group = "Prompts"),
        Command(name = "skill:pdf-tables", description = "Extract tables from a PDF into CSV", group = "Skills"),
        Command(name = "skill:sql-review", description = "Review a migration for locks and index use", group = "Skills"),
        Command(name = "rc-status", description = "Show what the remote-control extension is attached to", group = "Extensions"),
        Command(name = "web-search", description = "Search the web and summarise the results", argument = "query", group = "Extensions"),
    )

    @Test
    fun theQueryOpensOnTheSlashAloneWithTheWholeListBehindIt() {
        assertEquals("", SlashCommands.query("/"))
    }

    @Test
    fun theQueryIsThePartialNameWhileItIsBeingTyped() {
        assertEquals("com", SlashCommands.query("/com"))
        assertEquals("skill:pdf", SlashCommands.query("/skill:pdf"))
    }

    @Test
    fun theQueryClosesTheMomentTheNameIsFollowedByAnything() {
        assertNull(SlashCommands.query("/review "))
        assertNull(SlashCommands.query("/review the diff"))
    }

    @Test
    fun ordinaryTextIsNoQueryIncludingASlashInsideIt() {
        assertNull(SlashCommands.query(""))
        assertNull(SlashCommands.query("run the tests"))
        assertNull(SlashCommands.query("look at src/lib/ws.ts"))
        assertNull(SlashCommands.query("\n/compact"))
    }

    @Test
    fun aTypedCommandSplitsTheFirstWordFromTheArgument() {
        assertEquals(SlashCommands.Typed(name = "review", argument = "focus on the retry logic"), SlashCommands.typed("/review focus on the retry logic"))
    }

    @Test
    fun theArgumentIsLeftOutWhenNothingButSpaceFollowed() {
        assertEquals(SlashCommands.Typed(name = "compact", argument = null), SlashCommands.typed("/compact"))
        assertEquals(SlashCommands.Typed(name = "compact", argument = null), SlashCommands.typed("/compact   "))
    }

    @Test
    fun onlyWhatTheSessionOffersMatches() {
        assertEquals("compact", SlashCommands.match(codex, draft = "/compact")?.command?.name)
        // A terminal treats an unknown slash as text, and so do we.
        assertNull(SlashCommands.match(codex, draft = "/nonesuch"))
        assertNull(SlashCommands.match(codex, draft = "compact"))
    }

    @Test
    fun aMatchCarriesWhateverWasTypedAfterTheName() {
        val match = SlashCommands.match(codex, draft = "/review gateway/link.py only")
        assertEquals("review", match?.command?.name)
        assertEquals("gateway/link.py only", match?.argument)
    }

    @Test
    fun filteringIsAPrefixOfTheNameInTheDevicesOwnOrder() {
        assertEquals(codex.map { it.name }, SlashCommands.filter(codex, query = "").map { it.name })
        assertEquals(listOf("status", "skills"), SlashCommands.filter(codex, query = "s").map { it.name })
        assertEquals(listOf("skills"), SlashCommands.filter(codex, query = "ski").map { it.name })
        assertTrue(SlashCommands.filter(codex, query = "zz").isEmpty())
    }

    @Test
    fun aNamespacedNameMatchesByItsPrefix() {
        assertEquals(listOf("skill:pdf-tables", "skill:sql-review"), SlashCommands.filter(pi, query = "skill:").map { it.name })
    }

    @Test
    fun noHeaderWhenTheAgentDistinguishesOneGroupOrNone() {
        assertEquals(listOf(SlashCommands.Section(group = null, items = codex)), SlashCommands.sections(codex))
    }

    @Test
    fun sectionsKeepTheOrderTheGroupsFirstAppearIn() {
        val sections = SlashCommands.sections(pi)
        assertEquals(listOf("Built-in", "Prompts", "Skills", "Extensions"), sections.map { it.group })
        assertEquals(pi.size, sections.flatMap { it.items }.size)
    }

    @Test
    fun aOneGroupListStaysFlat() {
        val one = listOf(Command(name = "compact", description = "x", group = "Built-in"))
        assertEquals(listOf(SlashCommands.Section(group = null, items = one)), SlashCommands.sections(one))
    }

    @Test
    fun aCompletionLeavesASpaceOnlyWhereAnArgumentGoes() {
        assertEquals("/compact", SlashCommands.completion(Command(name = "compact", description = "")))
        assertEquals("/review ", SlashCommands.completion(Command(name = "review", description = "", argument = "instructions")))
    }

    @Test
    fun theProtocolsWorkedListReadsTheSameWay() {
        val reply = WebFixtures.obj("app/reply.session.commands.json")
        val commands = WebFixtures.decode(ListSerializer(Command.serializer()), reply["result"]?.jsonObject?.get("commands"))
        // The worked list carries more than one group, which is what sections it.
        assertTrue(SlashCommands.sections(commands).size > 1)
        assertEquals(listOf("compact"), SlashCommands.filter(commands, query = "comp").map { it.name })

        val run = WebFixtures.obj("app/session.command.json")
        val name = assertNotNull(run["name"]?.jsonPrimitive?.content)
        val argument = run["argument"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val match = SlashCommands.match(commands, draft = "/$name ${argument ?: ""}")
        assertEquals(name, match?.command?.name)
        assertEquals(argument, match?.argument)
    }
}

/**
 * A20. The last case is the web's own (`web/tests/shared-control.test.tsx`, "sends the card
 * selections alongside the draft") on the protocol's pending question; the others pin the web's rule
 * where the core's `QuestionDraft` reads the draft another way.
 */
class AnsweringTests {
    private val questions = listOf(
        QuestionItem(id = "q1", prompt = "Which?", options = listOf(QuestionOption(id = "a", label = "A")), allowText = true),
        QuestionItem(id = "q2", prompt = "Why?", allowText = true),
    )

    @Test
    fun theComposerAnswersTheFirstQuestionWithNoSelection() {
        val draft = QuestionDraft(requestID = "r").setText("typed on the card", questionID = "q1")
        val answers = Answering.composeAnswer(questions, draft = draft, text = " from the composer ")
        assertEquals(mapOf("q1" to QuestionAnswer.Text("from the composer")), answers)
    }

    @Test
    fun aSelectionIsKeptAndTheNextQuestionTakesTheSentence() {
        var draft = QuestionDraft(requestID = "r").toggle("a", of = questions[0])
        assertEquals(
            mapOf("q1" to QuestionAnswer.Options(listOf("a")), "q2" to QuestionAnswer.Text("because")),
            Answering.composeAnswer(questions, draft = draft, text = "because"),
        )
        assertFalse(Answering.cardComplete(questions, draft = draft))
        draft = draft.setText("reasons", questionID = "q2")
        assertTrue(Answering.cardComplete(questions, draft = draft))
        assertEquals(mapOf("q1" to QuestionAnswer.Options(listOf("a")), "q2" to QuestionAnswer.Text("reasons")), Answering.cardAnswers(questions, draft = draft))
    }

    @Test
    fun aDraftWithNowhereToGoStaysPut() {
        val optionsOnly = listOf(QuestionItem(id = "q", prompt = "?", options = listOf(QuestionOption(id = "a", label = "A"))))
        assertNull(Answering.composeAnswer(optionsOnly, draft = QuestionDraft(requestID = "r"), text = "x"))
        assertNull(Answering.composeAnswer(questions, draft = QuestionDraft(requestID = "r"), text = "  "))
    }

    @Test
    fun theCardsSelectionsGoAlongsideTheDraft() {
        val pending = assertNotNull(WebFixtures.pendingQuestions().firstOrNull())
        val two = listOf(pending, QuestionItem(id = "q2", prompt = "Anything else?", options = emptyList(), multi = false, allowText = true))
        val draft = QuestionDraft(requestID = "r").toggle("widen", of = pending)
        assertEquals(
            mapOf("q1" to QuestionAnswer.Options(listOf("widen")), "q2" to QuestionAnswer.Text("and raise the timeout")),
            Answering.composeAnswer(two, draft = draft, text = "and raise the timeout"),
        )
        // Nothing selected anywhere: the draft answers the first question.
        assertEquals(mapOf("q1" to QuestionAnswer.Text("clamp it")), Answering.composeAnswer(two, draft = QuestionDraft(requestID = "r"), text = "clamp it"))
    }
}

/** `sessionOptions.ts` has no web test of its own: the rule is its comment's. */
class SessionOptionsTests {
    @Test
    fun aPatchChangesOnlyWhatItCarriesAndANullSpeedIsTheStandardTier() {
        val session = Session(sessionID = "s", deviceID = "d", agent = "codex", title = "", cwd = "/", model = "a", speed = "priority")
        assertEquals("priority", SessionOptions(model = "b").applied(to = session).speed)
        assertNull(SessionOptions(speed = SpeedChange.Standard).applied(to = session).speed)
        assertEquals("b", SessionOptions(model = "b").applied(to = session).model)
    }
}

/** `modelLabels.ts` has no web test of its own: the rule is its comment's. */
class ModelLabelTests {
    @Test
    fun labelPairsCoverWhateverTheAgentLists() {
        val models = listOf(AgentOption(id = "s", label = "Sonnet"), AgentOption(id = "o", label = "Opus"))
        val efforts = listOf(AgentOption(id = "h", label = "High"))
        assertEquals(listOf("Sonnet High", "Opus High"), LabelPair.pairs(models = models, efforts = efforts).map { it.key })
        assertEquals(listOf(null, null), LabelPair.pairs(models = models, efforts = emptyList()).map { it.effort })
        assertTrue(LabelPair.pairs(models = emptyList(), efforts = emptyList()).isEmpty())
    }
}

/** `attachments.ts`: the limits `web/tests/attachment-cap.test.tsx` counts against, which are the wire's, and the one check on the text. */
class AttachmentLimitTests {
    @Test
    fun theLimitsAreTheWebs() {
        assertEquals(8, AttachmentLimits.maxAttachments)
        assertEquals(6 * 1024 * 1024, AttachmentLimits.maxAttachmentBytes)
        assertEquals(64 * 1024, AttachmentLimits.maxTextBytes)
    }

    @Test
    fun textIsMeasuredInUTF8Bytes() {
        assertTrue(AttachmentLimits.textTooLong("界".repeat(22_000)))
        assertFalse(AttachmentLimits.textTooLong("short"))
    }
}

/**
 * `web/tests/shared-control.test.tsx`, case for case: the protocol's attach fixtures and the mock
 * gateway's agents, each variation spread over its fixture as the web test spreads it. One web case
 * has no form here — `{ ...claudeAgent, attach_ready: undefined }` has no hint — because the core's
 * `AgentInfo` reads an absent `attach_ready` as false, which is "not set up yet" and has one.
 */
class AttachTests {
    private val restartHint = "This terminal session was started without the attachment; restart it to control it from here"
    private val leaderHint = "Run rc-client grok setup on the device, then restart Grok to attach its sessions"
    private val daemonHint = "Run rc-client codex setup on the device to attach its Codex sessions"

    @BeforeEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private fun attachAgent(change: (MutableMap<String, kotlinx.serialization.json.JsonElement?>) -> Unit = {}) =
        WebFixtures.agent(WebFixtures.obj("objects/agent.claude-attach.json"), change)

    private fun daemonAgent(change: (MutableMap<String, kotlinx.serialization.json.JsonElement?>) -> Unit = {}) =
        WebFixtures.agent(WebFixtures.obj("objects/agent.codex-daemon.json"), change)

    private fun mock(agent: kotlinx.serialization.json.JsonObject, change: (MutableMap<String, kotlinx.serialization.json.JsonElement?>) -> Unit = {}) =
        WebFixtures.agent(agent, change)

    // A10

    @Test
    fun aReadyDeviceWithAnUnattachedCLIAsksForARestart() {
        assertEquals(restartHint, Attach.attachHint(attachAgent()))
    }

    @Test
    fun theHintFollowsTheAttachmentKindAndReadiness() {
        assertNull(Attach.attachHint(null))
        assertEquals(true, Attach.attachHint(mock(MockAgents.claude))?.contains("restart it"))
        assertEquals(true, Attach.attachHint(mock(MockAgents.claudeNoShim))?.contains("Remote Control shim"))
        assertEquals(true, Attach.attachHint(mock(MockAgents.codexNoDaemon))?.contains("rc-client codex setup"))
        // No `attach` means there is nothing to hint at.
        assertNull(Attach.attachHint(mock(MockAgents.codex) { it["attach"] = JsonNull }))
    }

    @Test
    fun stopIsHiddenWhereTheAttachmentCannotInterrupt() {
        assertFalse(Attach.canInterruptShared(attachAgent { it["shared_interrupt"] = JsonPrimitive(false) }))
        assertTrue(Attach.canInterruptShared(attachAgent()))
    }

    @Test
    fun stopNeedsTheInterruptCapabilityAsWellAsTheFlag() {
        val agent = attachAgent { agent ->
            agent["shared_interrupt"] = JsonPrimitive(true)
            val capabilities = agent["capabilities"]?.jsonArray.orEmpty().filter { it.jsonPrimitive.content != "interrupt" }
            agent["capabilities"] = kotlinx.serialization.json.JsonArray(capabilities)
        }
        assertFalse(Attach.canInterruptShared(agent))
    }

    // A11

    @Test
    fun theTwoBooleansAreReadOffTheAgent() {
        // The daemon names no subset, so every setting is the device's.
        val daemon = daemonAgent()
        for (key in SharedSetting.allCases) assertTrue(Attach.canSetShared(daemon, key), "$key")
        assertTrue(Attach.canAttachShared(daemon))
        assertFalse(Attach.canAttachShared(attachAgent()))
        // Both default to false when the device says nothing.
        assertFalse(Attach.canSetShared(mock(MockAgents.codexNoDaemon), SharedSetting.model))
        assertFalse(Attach.canAttachShared(mock(MockAgents.claude) { it["shared_attachments"] = null }))
        assertFalse(Attach.canSetShared(null, SharedSetting.model))
    }

    @Test
    fun eachHalfFollowsItsOwnFlag() {
        val settingsOnly = daemonAgent { it["shared_attachments"] = JsonPrimitive(false) }
        assertTrue(Attach.canSetShared(settingsOnly, SharedSetting.model))
        assertFalse(Attach.canAttachShared(settingsOnly))
        val attachmentsOnly = daemonAgent { it["shared_settings"] = JsonPrimitive(false) }
        assertFalse(Attach.canSetShared(attachmentsOnly, SharedSetting.model))
        assertTrue(Attach.canAttachShared(attachmentsOnly))
    }

    @Test
    fun stopAndInterruptAndSendFollowTheInterruptFlagAlone() {
        assertTrue(Attach.canInterruptShared(daemonAgent()))
        // Settings and attachments say nothing about interrupting.
        val noInterrupt = daemonAgent { it["shared_interrupt"] = JsonPrimitive(false) }
        assertFalse(Attach.canInterruptShared(noInterrupt))
        assertTrue(Attach.canSetShared(noInterrupt, SharedSetting.model))
    }

    @Test
    fun aTerminalCodexSessionHintsForTheDaemon() {
        assertEquals(daemonHint, Attach.attachHint(mock(MockAgents.codexNoDaemon)))
    }

    // A28

    @Test
    fun grokAsksForTheSetupCommandWhileTheLeaderIsOff() {
        assertEquals(leaderHint, Attach.attachHint(mock(MockAgents.grokNoLeader)))
    }

    @Test
    fun grokAsksForARestartOnceTheDeviceIsReady() {
        assertEquals(restartHint, Attach.attachHint(mock(MockAgents.grok)))
    }

    @Test
    fun aSharedGrokSessionKeepsBothPickers() {
        val grok = mock(MockAgents.grok)
        assertTrue(Attach.canSetShared(grok, SharedSetting.model))
        assertTrue(Attach.canSetShared(grok, SharedSetting.permissionMode))
    }

    @Test
    fun theLeaderTakesNoImages() {
        assertFalse(Attach.canAttachShared(mock(MockAgents.grok)))
    }

    @Test
    fun grokShowsStopWhileTheTerminalsTurnRuns() {
        assertTrue(Attach.canInterruptShared(mock(MockAgents.grok)))
    }

    // A40

    @Test
    fun settingsAnswerPerKeyAndFallBackToAllFourWithoutAList() {
        val attach = attachAgent()
        assertTrue(Attach.canSetShared(attach, SharedSetting.model))
        assertTrue(Attach.canSetShared(attach, SharedSetting.effort))
        assertFalse(Attach.canSetShared(attach, SharedSetting.permissionMode))
        assertFalse(Attach.canSetShared(attach, SharedSetting.speed))
        val noList = attachAgent { it["shared_settings_keys"] = null }
        for (key in SharedSetting.allCases) assertTrue(Attach.canSetShared(noList, key), "$key")
        // The boolean still decides first: a list without it carries nothing.
        val off = attachAgent { it["shared_settings"] = JsonPrimitive(false) }
        for (key in SharedSetting.allCases) assertFalse(Attach.canSetShared(off, key), "$key")
    }

    // A42

    @Test
    fun theInterruptIsReadOffTheChannelAsOffADaemon() {
        assertTrue(Attach.canInterruptShared(attachAgent()))
        assertTrue(Attach.canInterruptShared(daemonAgent()))
        // The shim is what provides the terminal, so a device without it carries no interrupt either.
        assertFalse(Attach.canInterruptShared(mock(MockAgents.claudeNoShim)))
    }

    @Test
    fun onlyAMessageTheCLIAbsorbedCarriesAChip() {
        assertEquals("will be re-sent", Attach.deliveryLabel("absorbed"))
        assertNull(Attach.deliveryLabel("delivered"))
        assertNull(Attach.deliveryLabel(null))
    }
}

/** `web/tests/language.test.tsx`: a relative time's words follow the interface language, and its numbers stay as they are. */
class RelativeTimeLanguageTests {
    /** `Date.UTC(2026, 8, 13, 12, 0, 0)`. */
    private val now = 1_789_300_800_000L

    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    @Test
    fun theWordsAreTranslatedAndTheNumbersLeftAlone() {
        assertEquals("3m", Format.relativeTime(now - 3 * 60_000, now = now))
        assertEquals("3m ago", Format.relativeAgo(now - 3 * 60_000, now = now))
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        assertEquals("3 分钟", Format.relativeTime(now - 3 * 60_000, now = now))
        assertEquals("3 分钟前", Format.relativeAgo(now - 3 * 60_000, now = now))
        assertEquals("昨天", Format.relativeAgo(now - 36 * 3_600_000, now = now))
    }
}
