package com.junbingao.remotecontrol.win.strings

import com.junbingao.remotecontrol.core.protocol.EventSource
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.TimelineDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach

/**
 * Every group of the web's string table, in both languages. The compiler already refuses a table
 * with a key missing — both are built with the same constructor — so this walks what it cannot
 * see: an empty string, a word list of another length, a label table with other ids.
 */
class StringTableTests {
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    /** A class's properties, in the order its constructor declares them. */
    private fun fields(value: Any): List<Pair<String, Any?>> =
        value.javaClass.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { field -> field.isAccessible = true; field.name to field.get(value) }

    @Test
    fun bothLanguagesCarryTheSameGroupsAndKeys() {
        val english = fields(StringTable.en)
        val chinese = fields(StringTable.zhHans)
        assertEquals(english.map { it.first }, chinese.map { it.first })
        assertEquals(22, english.size)
        for ((en, zh) in english.zip(chinese)) {
            val enKeys = fields(en.second!!).map { it.first }
            val zhKeys = fields(zh.second!!).map { it.first }
            assertEquals(enKeys, zhKeys, "group ${en.first}")
            assertTrue(enKeys.isNotEmpty(), "group ${en.first}")
        }
    }

    @Test
    fun noWordIsEmptyAndEveryListMatches() {
        for ((en, zh) in fields(StringTable.en).zip(fields(StringTable.zhHans))) {
            for ((a, b) in fields(en.second!!).zip(fields(zh.second!!))) {
                val key = "${en.first}.${a.first}"
                val x = a.second
                val y = b.second
                when {
                    x is String && y is String -> assertTrue(x.isNotEmpty() && y.isNotEmpty(), key)
                    x is List<*> && y is List<*> -> assertTrue(x.size == y.size && "" !in x && "" !in y, key)
                    x is Map<*, *> && y is Map<*, *> -> assertEquals(x.keys, y.keys, key)
                    x is TimelineDetailLabels && y is TimelineDetailLabels ->
                        assertTrue(x.simple.isNotEmpty() && y.detailed.isNotEmpty(), key)
                }
            }
        }
    }

    @Test
    fun functionsKeepTheirNumbersInBothLanguages() {
        assertEquals("3m ago", StringTable.en.format.minutesAgo(3))
        assertEquals("3 分钟前", StringTable.zhHans.format.minutesAgo(3))
        assertEquals("1 session", StringTable.en.devices.sessionsCount(1))
        assertEquals("2 sessions", StringTable.en.devices.sessionsCount(2))
        assertTrue(StringTable.en.devices.updateBody("mac", null).contains("the gateway's client"))
        assertEquals(
            "Update mac to 1.2.0? Its service restarts; sessions it drives are stopped.",
            StringTable.en.devices.updateBody("mac", "1.2.0"),
        )
        assertEquals("网关 1.0 · 协议 1", StringTable.zhHans.settings.versions("1.0", "1"))
        assertEquals("rc-client pair --gateway https://x --code 42", StringTable.en.pairing.manualPairCommand("https://x", "42"))
    }

    @Test
    fun sFollowsTheInterfaceLanguage() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
        assertEquals("Devices", S.nav.devices)
        assertEquals("Gateway", S.win.gateway)
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        assertEquals("设备", S.nav.devices)
        assertEquals("网关", S.win.gateway)
        assertEquals("Remote Control", S.productName)
    }

    @Test
    fun windowsWordsHaveBothLanguages() {
        for (tables in listOf(
            WinStrings.en to WinStrings.zhHans,
            WinComposerStrings.en to WinComposerStrings.zhHans,
            WinSettingsStrings.en to WinSettingsStrings.zhHans,
        )) {
            val en = fields(tables.first)
            val zh = fields(tables.second)
            assertEquals(en.map { it.first }, zh.map { it.first })
            for ((a, b) in en.zip(zh)) {
                if (a.second is String) assertTrue((a.second as String).isNotEmpty() && (b.second as String).isNotEmpty(), a.first)
            }
        }
        assertEquals("Blocked in Windows Settings.", WinStrings.en.pushBlocked)
        assertEquals("Remote Control 1.12.0 · Gateway 1.12.0 · Protocol 1", WinStrings.en.versions("1.12.0", "1.12.0", "1"))
    }

    @Test
    fun labelsFallBackToTheIdTheyWereGiven() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
        assertEquals("Claude Code", S.agentLabel("claude"))
        assertEquals("cursor", S.agentLabel("cursor"))
        assertEquals("macOS", S.platformLabel("macos"))
        assertEquals("freebsd", S.platformLabel("freebsd"))
        assertEquals("OpenAI", S.vendorLabel("openai"))
        assertEquals("needs approval", S.stateLabel("needs_approval"))
        assertEquals("owner", S.roleLabel("owner"))
        assertEquals("中文", S.interfaceLanguageLabel(InterfaceLanguage.zhHans))
        val untitled = Session(sessionID = "s", deviceID = "d", agent = "claude", title = "  ", cwd = "/")
        assertEquals("Untitled session", S.sessionTitle(untitled))
        assertEquals("Remote Control", S.sessionOriginLabel(untitled.copy(origin = EventSource.remote)))
        assertEquals("Detailed", S.timelineDetailLabel(TimelineDetail.detailed))
        assertEquals("Waiting for you", S.dotToneLabel("waiting"))
    }
}
