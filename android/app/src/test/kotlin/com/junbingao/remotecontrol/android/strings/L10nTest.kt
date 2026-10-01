package com.junbingao.remotecontrol.android.strings

import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The iPhone's language rule and its catalogue, as the Android app reads them: English until
 * Chinese is picked, a change that every later lookup sees at once, and a table generated from
 * `ios/App/Localizable.xcstrings` with Android's own words laid over it. On Robolectric for the
 * platform's JSON reader.
 */
@RunWith(AndroidJUnit4::class)
class L10nTest {
    @After
    fun englishAgain() {
        L10n.use(L10n.english)
    }

    @Test
    fun englishIsTheLanguageUntilChineseIsPicked() {
        assertEquals(L10n.english, L10n.language)
        assertEquals("Settings", L10n.string("Settings"))
        L10n.use(L10n.chinese)
        assertEquals(L10n.chinese, L10n.language)
        assertEquals("设置", L10n.string("Settings"))
        L10n.use(L10n.english)
        assertEquals("Settings", L10n.string("Settings"))
    }

    @Test
    fun theLanguageCanFollowTheSettingsThatHoldIt() {
        val stored = mutableStateOf(L10n.chinese)
        L10n.follow { stored.value }
        assertEquals("设备", L10n.string("Devices"))
        stored.value = L10n.english
        assertEquals("Devices", L10n.string("Devices"))
        L10n.use(L10n.chinese)
        stored.value = L10n.english
        assertEquals("a later use stops following", "设备", L10n.string("Devices"))
    }

    @Test
    fun aKeyWithNoEntryIsItsOwnEnglishText() {
        assertFalse(L10n.knows("A sentence nobody wrote"))
        assertEquals("A sentence nobody wrote", L10n.string("A sentence nobody wrote"))
        L10n.use(L10n.chinese)
        assertEquals("A sentence nobody wrote", L10n.string("A sentence nobody wrote"))
    }

    @Test
    fun valuesGoWhereTheTranslationPutsThem() {
        val key = "Archive, %lld sessions on %@"
        assertEquals("Archive, 3 sessions on ci-runner-01", L10n.string(key, 3L, "ci-runner-01"))
        L10n.use(L10n.chinese)
        assertEquals("归档，ci-runner-01 上有 3 个会话", L10n.string(key, 3L, "ci-runner-01"))
        assertEquals("48.2 秒", L10n.string("%.1fs", 48.2))
        assertEquals("100%", L10n.string("%lld%%", 100L))
    }

    @Test
    fun everyKeyIsTranslatedWithTheSameValues() {
        assertEquals(Catalog.en.keys, Catalog.zhHans.keys)
        val mismatched = Catalog.en.keys.filter { placeholders(Catalog.en.getValue(it)) != placeholders(Catalog.zhHans.getValue(it)) }
        assertTrue("translations that lose or add a value: $mismatched", mismatched.isEmpty())
    }

    @Test
    fun androidSaysItsOwnNamesWhereTheIPhoneSaysApples() {
        assertEquals("Require biometric unlock", L10n.table(L10n.english).getValue("Require Face ID"))
        assertEquals("需要生物识别解锁", L10n.table(L10n.chinese).getValue("Require Face ID"))
        val apple = listOf("Face ID", "Touch ID", "iPhone", "iOS", "TestFlight", "App Store", "keychain")
        val leaks = overlay().keys.filter { key -> apple.any { L10n.table(L10n.english).getValue(key).contains(it) } }
        assertTrue("overlay entries that still name Apple: $leaks", leaks.isEmpty())
    }

    @Test
    fun theOverlayOnlyReplacesCatalogueKeysAndSystemWordsAddNone() {
        val catalogue = catalogueKeys()
        assertTrue(overlay().keys.all { it in catalogue })
        assertTrue(system().keys.none { it in catalogue })
        assertEquals(catalogue + system().keys, Catalog.en.keys)
    }

    @Test
    fun everyWordTheAppLooksUpIsInTheTable() {
        val literal = Regex("""L10n\.string\(\s*"((?:[^"\\]|\\.)*)"""")
        val missing = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            literal.findAll(file.readText()).map { unescape(it.groupValues[1]) }.filter { !L10n.knows(it) }.map { "${file.name}: $it" }
        }.toList()
        assertTrue("words with no entry: $missing", missing.isEmpty())
    }

    private fun placeholders(text: String): Set<String> {
        val spec = Regex("""%(?:(\d+)\$)?[-+ #0']*\d*(?:\.\d+)?(?:hh|h|ll|l|q|L|z|t|j)?([@dDiuUxXoOcCfFeEgGsS])""")
        var next = 0
        return spec.findAll(text.replace("%%", "")).map { match ->
            val position = match.groupValues[1].toIntOrNull() ?: ++next
            "$position:${match.groupValues[2].lowercase()}"
        }.toSet()
    }

    private fun unescape(text: String) = text.replace("\\\"", "\"").replace("\\$", "$").replace("\\n", "\n").replace("\\\\", "\\")

    private fun catalogueKeys(): Set<String> =
        JSONObject(File("../../ios/App/Localizable.xcstrings").readText()).getJSONObject("strings").keys().asSequence().toSet()

    private fun overlay(): Map<String, String> = entries(File("src/main/strings/overlay.json"))

    private fun system(): Map<String, String> = entries(File("src/main/strings/system.json"))

    /** A `{"key": {"en": …, "zh-Hans": …}}` file's English words; a key starting with "//" is its note. */
    private fun entries(file: File): Map<String, String> {
        val json = JSONObject(file.readText())
        return json.keys().asSequence().filter { !it.startsWith("//") }.associateWith { json.getJSONObject(it).getString("en") }
    }
}
