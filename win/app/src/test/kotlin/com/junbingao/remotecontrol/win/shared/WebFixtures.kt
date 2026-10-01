package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.QuestionItem
import com.junbingao.remotecontrol.core.protocol.WireJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * The fixtures the web tests read, read from where they read them: the protocol's worked examples,
 * as JSON so a variation can be spread over one the way a web test spreads it, and decoded the way
 * a device's reply is. The Mac's `WebFixtures`.
 */
object WebFixtures {
    /** `protocol/fixtures/<path>`. */
    fun obj(path: String): JsonObject {
        val root = File(checkNotNull(System.getProperty("rc.protocol.dir")) { "rc.protocol.dir is not set" })
        return WireJson.parseToJsonElement(File(root, "fixtures/$path").readText()).jsonObject
    }

    fun <Value> decode(type: DeserializationStrategy<Value>, json: JsonElement?): Value = WireJson.decodeFromJsonElement(type, json ?: JsonNull)

    /** An agent as a device reports it, changed first the way a web test spreads a fixture: a key set to null is left out, `JsonNull` is `null`. */
    fun agent(json: JsonObject, change: (MutableMap<String, JsonElement?>) -> Unit = {}): AgentInfo {
        val fields = LinkedHashMap<String, JsonElement?>(json)
        change(fields)
        return decode(AgentInfo.serializer(), JsonObject(fields.filterValues { it != null }.mapValues { it.value!! }))
    }

    /** The questions of `events/question.pending.json`. */
    fun pendingQuestions(): List<QuestionItem> = decode(ListSerializer(QuestionItem.serializer()), obj("events/question.pending.json")["questions"])
}

/** The mock gateway's agents (`web/mock/fixtures.ts`), with the fields the attachment rules read. */
object MockAgents {
    private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    val claude: JsonObject
        get() = JsonObject(
            mapOf(
                "agent" to JsonPrimitive("claude"), "available" to JsonPrimitive(true),
                "capabilities" to strings("worktree", "takeover", "interrupt", "queue", "attachments", "effort", "history", "commands"),
                "attach" to JsonPrimitive("channel"), "attach_ready" to JsonPrimitive(true), "shared_interrupt" to JsonPrimitive(true),
                "shared_settings" to JsonPrimitive(true), "shared_settings_keys" to strings("model", "effort"),
                "shared_attachments" to JsonPrimitive(false),
            ),
        )

    val claudeNoShim: JsonObject
        get() = JsonObject(
            claude.toMutableMap().apply {
                put("attach_ready", JsonPrimitive(false))
                put("capabilities", strings("worktree", "takeover", "interrupt", "queue", "attachments", "effort", "history"))
                put("shared_interrupt", JsonPrimitive(false))
                put("shared_settings", JsonPrimitive(false))
                remove("shared_settings_keys")
            },
        )

    val codex: JsonObject
        get() = JsonObject(
            mapOf(
                "agent" to JsonPrimitive("codex"), "available" to JsonPrimitive(true),
                "capabilities" to strings("worktree", "interrupt", "queue", "steer", "attachments", "effort", "history", "commands"),
                "attach" to JsonPrimitive("daemon"), "attach_ready" to JsonPrimitive(true), "shared_interrupt" to JsonPrimitive(true),
                "shared_settings" to JsonPrimitive(true), "shared_attachments" to JsonPrimitive(true),
            ),
        )

    val codexNoDaemon: JsonObject
        get() = JsonObject(
            codex.toMutableMap().apply {
                put("attach_ready", JsonPrimitive(false))
                put("shared_interrupt", JsonPrimitive(false))
                put("shared_settings", JsonPrimitive(false))
                put("shared_attachments", JsonPrimitive(false))
            },
        )

    val grok: JsonObject
        get() = JsonObject(
            mapOf(
                "agent" to JsonPrimitive("grok"), "available" to JsonPrimitive(true),
                "capabilities" to strings("worktree", "interrupt", "queue", "effort", "history", "commands"),
                "attach" to JsonPrimitive("leader"), "attach_ready" to JsonPrimitive(true), "shared_interrupt" to JsonPrimitive(true),
                "shared_settings" to JsonPrimitive(true), "shared_attachments" to JsonPrimitive(false),
            ),
        )

    val grokNoLeader: JsonObject
        get() = JsonObject(
            grok.toMutableMap().apply {
                put("attach_ready", JsonPrimitive(false))
                put("shared_interrupt", JsonPrimitive(false))
                put("shared_settings", JsonPrimitive(false))
            },
        )
}
