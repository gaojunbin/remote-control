package com.junbingao.remotecontrol.win.chat

import com.junbingao.remotecontrol.core.protocol.ApprovalDecision
import com.junbingao.remotecontrol.core.protocol.ApprovalOption
import com.junbingao.remotecontrol.core.protocol.ApprovalPayload
import com.junbingao.remotecontrol.core.protocol.DiffPayload
import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.OptionStyle
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.QuestionPayload
import com.junbingao.remotecontrol.core.protocol.RequestStatus
import com.junbingao.remotecontrol.core.protocol.ToolCallPayload
import com.junbingao.remotecontrol.core.protocol.ToolKind
import com.junbingao.remotecontrol.core.protocol.ToolStatus
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.chat.blocks.CardRules
import com.junbingao.remotecontrol.win.chat.blocks.ChatPreText
import com.junbingao.remotecontrol.win.chat.blocks.DiffLineKind
import com.junbingao.remotecontrol.win.chat.blocks.JSONText
import com.junbingao.remotecontrol.win.chat.blocks.ToolIcon
import com.junbingao.remotecontrol.win.chat.blocks.ToolRowModel
import com.junbingao.remotecontrol.win.chat.page.ChatErrorWords
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `web/tests/ApprovalCard.test.tsx` and the words a resolved question card says (`QuestionCard.tsx`). */
class ChatCardRulesTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private val options = listOf(
        ApprovalOption(id = "allow", label = "Allow once", style = OptionStyle.primary),
        ApprovalOption(id = "allow_session", label = "Allow for session", style = OptionStyle.secondary),
        ApprovalOption(id = "deny", label = "Deny", style = OptionStyle.danger),
    )

    private fun approval(status: RequestStatus = RequestStatus.pending, decision: ApprovalDecision? = null) = ApprovalPayload(
        requestID = "req-1",
        tool = "Bash",
        kind = ToolKind.shell,
        title = "git commit -am \"fix: isolate the auth clock\"",
        options = options,
        status = status,
        decision = decision,
    )

    @Test
    fun theOptionsAreTheAgentsAcceptFirstAndRejectLast() {
        assertEquals(listOf("Allow once", "Allow for session", "Deny"), CardRules.ordered(options).map { it.label })
        val shuffled = listOf(options[2], options[1], options[0])
        assertEquals(listOf("allow", "allow_session", "deny"), CardRules.ordered(shuffled).map { it.id })
    }

    @Test
    fun theOthersKeepTheOrderTheyCameIn() {
        val many = listOf(
            ApprovalOption(id = "b", label = "B", style = OptionStyle.secondary),
            options[2],
            ApprovalOption(id = "a", label = "A", style = OptionStyle.secondary),
            options[0],
        )
        assertEquals(listOf("allow", "b", "a", "deny"), CardRules.ordered(many).map { it.id })
    }

    @Test
    fun aResolvedRequestSaysWhoDecided() {
        val result = CardRules.approvalResult(approval(RequestStatus.resolved, ApprovalDecision(optionID = "deny", by = EventSource.terminal)))
        assertEquals("Deny · decided by terminal", result)
    }

    @Test
    fun anOptionItNeverOfferedIsNamedVerbatim() {
        val result = CardRules.approvalResult(approval(RequestStatus.resolved, ApprovalDecision(optionID = "later", by = EventSource.remote)))
        assertEquals("later · decided by remote", result)
    }

    @Test
    fun aRequestAnsweredInTheTerminalSaysSo() {
        val decision = ApprovalDecision(optionID = ApprovalPayload.elsewhereOptionID, by = EventSource.terminal)
        assertEquals("Answered in the terminal", CardRules.approvalResult(approval(RequestStatus.resolved, decision)))
    }

    @Test
    fun anExpiredRequestIsMarked() {
        assertEquals("This request expired.", CardRules.approvalResult(approval(RequestStatus.expired)))
    }

    @Test
    fun aResolvedQuestionSaysWhoAnswered() {
        val item = QuestionItem(id = "q1", prompt = "Which database?")
        assertEquals(
            "Answered in the terminal",
            CardRules.questionResult(QuestionPayload(requestID = "r", questions = listOf(item), status = RequestStatus.resolved, by = EventSource.terminal)),
        )
        assertEquals("Answered", CardRules.questionResult(QuestionPayload(requestID = "r", questions = listOf(item), status = RequestStatus.resolved)))
        assertEquals("This question expired.", CardRules.questionResult(QuestionPayload(requestID = "r", questions = listOf(item), status = RequestStatus.expired)))
    }

    @Test
    fun aSecretAnswerIsNeverRedrawn() {
        assertEquals("", CardRules.freeText(QuestionAnswer.Text("hunter2"), secret = true))
        assertEquals("Postgres", CardRules.freeText(QuestionAnswer.Text("Postgres"), secret = false))
        assertEquals("", CardRules.freeText(QuestionAnswer.Options(listOf("pg")), secret = false))
        assertTrue(CardRules.answerIncludes(QuestionAnswer.Options(listOf("pg", "redis")), "redis"))
        assertFalse(CardRules.answerIncludes(QuestionAnswer.Text("redis"), "redis"))
    }
}

/** `ToolRow.tsx`: what opens, what a running row shows, and the word at its trailing edge. */
class ChatToolRowTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private fun tool(
        status: ToolStatus = ToolStatus.succeeded,
        output: String? = null,
        input: JsonElement? = null,
        patch: String? = null,
        durationMS: Int? = null,
        title: String = "pytest -q",
    ) = ToolCallPayload(
        tool = "Bash",
        kind = ToolKind.shell,
        title = title,
        status = status,
        input = input,
        output = output,
        diff = patch?.let { DiffPayload(path = "a.py", additions = 1, deletions = 0, patch = it) },
        startedAt = 1_000,
        durationMS = durationMS,
    )

    @Test
    fun aRunningToolShowsOnlyItsLiveOutput() {
        val model = ToolRowModel(tool(status = ToolStatus.running, output = "collected 12 items", input = JsonObject(emptyMap())), open = false)
        assertTrue(model.expanded)
        assertTrue(model.showsOutput)
        assertFalse(model.showsInput)
        assertFalse(ToolRowModel(tool(status = ToolStatus.running, output = ""), open = false).showsOutput)
    }

    @Test
    fun openingShowsTheInputTheOutputAndTheDiff() {
        val model = ToolRowModel(tool(output = "", input = JsonObject(mapOf("command" to JsonPrimitive("ls"))), patch = "+x"), open = true)
        assertTrue(model.showsInput)
        assertTrue(model.showsOutput)
        assertTrue(model.showsDiff)
    }

    @Test
    fun aRowWithNothingToShowHasNothingToOpen() {
        val model = ToolRowModel(tool(), open = false)
        assertFalse(model.hasDetail)
        assertEquals("", model.trailing(tool()))
    }

    @Test
    fun theTrailingWordIsTheTimeOrExpandOrCollapse() {
        val running = tool(status = ToolStatus.running)
        assertEquals("running 3.2s", ToolRowModel(running, open = false).trailing(running, now = 4_200))
        val timed = tool(output = "ok", durationMS = 6_400)
        assertEquals("6.4s", ToolRowModel(timed, open = false).trailing(timed))
        val untimed = tool(output = "ok")
        assertEquals("expand", ToolRowModel(untimed, open = false).trailing(untimed))
        assertEquals("collapse", ToolRowModel(untimed, open = true).trailing(untimed))
    }

    @Test
    fun aSlashCommandsNameIsPrintedOnce() {
        assertFalse(ToolRowModel(tool(title = "Bash"), open = false).showsTitle)
        assertTrue(ToolRowModel(tool(), open = false).showsTitle)
        assertTrue(ToolRowModel(tool(status = ToolStatus.failed), open = false).failed)
    }

    @Test
    fun everyKindHasItsIconAndTheRestTheWrench() {
        assertEquals(LucideIcon.terminal, ToolIcon.icon(ToolKind.shell))
        assertEquals(LucideIcon.bot, ToolIcon.icon(ToolKind.subagent))
        assertEquals(LucideIcon.listChecks, ToolIcon.icon(ToolKind.todo))
        assertEquals(LucideIcon.wrench, ToolIcon.icon(ToolKind.other))
    }
}

/** The sentences the core writes for its own failures, in the web's words. */
class ChatErrorWordsTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    @Test
    fun rcCoresSentencesBecomeTheWebsWords() {
        assertEquals(S.composer.alreadySent, ChatErrorWords.web("That message has already been sent."))
        assertEquals(S.errors.timeout, ChatErrorWords.web("The gateway did not answer in time."))
        assertEquals(S.errors.notFound, ChatErrorWords.web("That device or session no longer exists."))
        assertEquals(S.errors.generic, ChatErrorWords.web("Not connected to the gateway."))
    }

    @Test
    fun aDevicesOwnSentenceIsShownAsItArrived() {
        assertEquals("the CLI exited with 1", ChatErrorWords.web("the CLI exited with 1"))
        assertNull(ChatErrorWords.web(""))
        assertNull(ChatErrorWords.web(null))
    }
}

/** `DiffView.tsx`'s line classes. */
class ChatDiffLineTests {
    @Test
    fun everyLineIsColouredByItsFirstCharacters() {
        val patch = "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n context\n-old\n+new"
        val kinds = patch.split("\n").map { DiffLineKind(it) }
        assertEquals(
            listOf(DiffLineKind.meta, DiffLineKind.meta, DiffLineKind.meta, DiffLineKind.hunk, DiffLineKind.context, DiffLineKind.del, DiffLineKind.add),
            kinds,
        )
    }
}

/**
 * `JSON.stringify(value, null, 2)`, which prints a tool's input on the web. An object keeps the
 * order the device wrote its keys in, as the web's does — the Mac's alphabetical order was RCCore
 * keeping objects as dictionaries — with integer-like keys first, as JavaScript orders them.
 */
class ChatJSONTextTests {
    @Test
    fun objectsAndArraysAreIndentedByTwo() {
        val value = JsonObject(
            linkedMapOf(
                "command" to JsonPrimitive("pytest -q"),
                "args" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(true), JsonNull)),
                "env" to JsonObject(emptyMap()),
                "files" to JsonArray(emptyList()),
            ),
        )
        assertEquals(
            """
            {
              "command": "pytest -q",
              "args": [
                1,
                true,
                null
              ],
              "env": {},
              "files": []
            }
            """.trimIndent(),
            JSONText.stringify(value),
        )
    }

    @Test
    fun integerKeysComeFirstAsAJavaScriptObjectOrdersThem() {
        assertEquals(listOf("2", "10", "b", "a"), JSONText.orderedKeys(listOf("b", "10", "a", "2")))
        assertEquals(listOf("1", "01"), JSONText.orderedKeys(listOf("01", "1")))
    }

    @Test
    fun numbersAreWrittenAsJavaScriptWritesThem() {
        assertEquals("100", JSONText.numberText(100.0))
        assertEquals("123.456", JSONText.numberText(123.456))
        assertEquals("-0.5", JSONText.numberText(-0.5))
        assertEquals("0.000001", JSONText.numberText(0.000001))
        assertEquals("1.5e-7", JSONText.numberText(1.5e-7))
        assertEquals("100000000000000000000", JSONText.numberText(1e20))
        assertEquals("1e+21", JSONText.numberText(1e21))
        assertEquals("0.30000000000000004", JSONText.numberText(0.1 + 0.2))
        assertEquals("null", JSONText.numberText(Double.POSITIVE_INFINITY))
    }

    @Test
    fun stringsTakeJavaScriptsEscapes() {
        assertEquals("\"say \\\"hi\\\"\\n\\tand \\\\ go\"", JSONText.quoted("say \"hi\"\n\tand \\ go"))
        assertEquals("\"\\u0001é\"", JSONText.quoted("\u0001é"))
    }

    @Test
    fun aPreBlockEndsAtItsLastLine() {
        assertEquals("a\nb", ChatPreText.display("a\nb\n"))
        assertEquals("a\n", ChatPreText.display("a\n\n"))
        assertEquals("a", ChatPreText.display("a"))
    }
}
