package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.objectValue
import com.junbingao.remotecontrol.core.protocol.stringValue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The interface language, checked against the sources rather than by eye — `ios/Verification`'s
 * `LocalizationChecks`, for the core's own table. Three failures are worth catching before a build
 * reaches a phone: a key the table has no Chinese for, a translation that is not the one the
 * iPhone app shows, and a word the core asks `L10n` for that the table does not hold.
 */
class LocalizationTests {
    @AfterTest
    fun backToEnglish() = L10n.use(InterfaceLanguage.en)

    /** Every entry carries a translation, and it is the iPhone app's own. */
    @Test
    fun theTableIsTheCatalogues() {
        val checks = CheckRunner("language")
        val catalogue = JSONValue.parse(File(FixtureSource.repository, "ios/App/Localizable.xcstrings").readBytes())["strings"]
            ?.objectValue.orEmpty()
        checks.expect(catalogue.size > 200, "the iPhone catalogue holds the app's words (${catalogue.size})")
        for ((key, translation) in Localizable.zhHans) {
            checks.expect(translation.isNotBlank(), "\"$key\" has a translation")
            val iphone = catalogue[key]?.get("localizations")?.get("zh-Hans")?.get("stringUnit")?.get("value")?.stringValue
            checks.equal(translation, iphone, "\"$key\" reads as the iPhone app reads it")
        }
        checks.assertAll()
    }

    /**
     * A translation that reorders `%@` and `%lld` without saying so positionally hands an integer
     * to `%@`. This is the check that would have caught it.
     */
    @Test
    fun everyTranslationTakesTheSameValues() {
        val checks = CheckRunner("language")
        val mismatched = Localizable.zhHans.filter { (key, translation) -> !FormatSpecifiers.agree(key, translation) }.keys
        checks.equal(mismatched.toList(), emptyList(), "every translation takes the same values, in a stated order")
        checks.assertAll()
    }

    /** The two names are shown in their own script, so neither is translated. */
    @Test
    fun twoLanguages() {
        val checks = CheckRunner("language")
        for (language in InterfaceLanguage.allCases) checks.expect(language.title.isNotEmpty(), "${language.rawValue} names itself")
        checks.equal(InterfaceLanguage.allCases.map { it.rawValue }, listOf("en", "zh-Hans"), "the app offers exactly two languages")
        checks.equal(InterfaceLanguage(rawValue = "zh-Hans"), InterfaceLanguage.zhHans, "a stored word reads back as its language")
        checks.equal(InterfaceLanguage(rawValue = "fr"), null, "and one this build does not offer reads as none")
        checks.assertAll()
    }

    /** Every word the core's sources ask `L10n` for is in the table, so none shows English inside a Chinese screen. */
    @Test
    fun everyWordTheCoreAsksForIsInTheTable() {
        val checks = CheckRunner("language")
        val sources = File(System.getProperty("user.dir"), "src/main/kotlin")
        val used = sources.walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> SourceStrings.keys(file.readText()).map { it to file.name } }.toList()
        checks.expect(used.size > 30, "the sources ask for the core's words (${used.size})")
        for ((key, file) in used) {
            checks.expect(key in Localizable.zhHans, "$file writes \"$key\", which the table does not hold")
        }
        checks.assertAll()
    }

    /** One preference, one table, both ways: a string built outside a screen follows the chosen language. */
    @Test
    fun theChosenLanguageIsRead() {
        assertEquals("Wrong username or password.", L10n.string("Wrong username or password."))
        L10n.use(InterfaceLanguage.zhHans)
        assertEquals("用户名或密码错误。", L10n.string("Wrong username or password."))
        assertEquals("2 台设备", L10n.string("%lld devices", 2))
        assertEquals("网关请求失败（502）。", L10n.string("The gateway request failed (%lld).", 502))
        assertEquals("a word nobody translated", L10n.string("a word nobody translated"))
        L10n.use(InterfaceLanguage.en)
        assertEquals("2 devices", L10n.string("%lld devices", 2))
    }
}

/**
 * The `%…` placeholders in a format key, and whether a translation still takes the same values. A
 * translation may put them in another order, but only by numbering them (`%2$@`), which is what
 * the formatter needs to read them back in the right order.
 */
private object FormatSpecifiers {
    data class Placeholder(val index: Int?, val conversion: Char)

    fun agree(key: String, translation: String): Boolean {
        val source = parse(key)
        val target = parse(translation)
        if (source.size != target.size) return false
        if (target.none { it.index != null }) return source.map { it.conversion } == target.map { it.conversion }
        // Once one is numbered they all must be, or the unnumbered ones are read from wherever the
        // last numbered one left off.
        if (!target.all { it.index != null }) return false
        return target.all { placeholder ->
            val index = placeholder.index ?: return false
            index in 1..source.size && source[index - 1].conversion == placeholder.conversion
        }
    }

    fun parse(text: String): List<Placeholder> {
        val placeholders = mutableListOf<Placeholder>()
        var cursor = 0
        while (true) {
            val start = text.indexOf('%', cursor)
            if (start < 0 || start + 1 >= text.length) break
            cursor = start + 1
            if (text[cursor] == '%') {
                cursor += 1
                continue
            }
            var rest = cursor
            while (rest < text.length && text[rest].isDigit()) rest += 1
            var index: Int? = null
            if (rest < text.length && text[rest] == '$' && rest > cursor) {
                index = text.substring(cursor, rest).toInt()
                rest += 1
            } else {
                rest = cursor
            }
            // Length modifiers and width carry no value of their own.
            while (rest < text.length && (text[rest].isDigit() || text[rest] in "lhqzjt.+- #'")) rest += 1
            if (rest >= text.length) break
            placeholders.add(Placeholder(index, text[rest]))
            cursor = rest + 1
        }
        return placeholders
    }
}

/**
 * Every literal a `L10n.string(` call can resolve — a ternary between two keys counts as both —
 * read from Kotlin source as RCCore's check reads Swift.
 */
private object SourceStrings {
    fun keys(source: String): List<String> {
        val text = source.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")
        val keys = mutableListOf<String>()
        var from = 0
        while (true) {
            val call = text.indexOf("L10n.string(", from)
            if (call < 0) break
            var cursor = call + "L10n.string(".length
            var depth = 1
            // Only the first argument is a key; the ones after it are the values.
            var firstArgument = true
            while (cursor < text.length && depth > 0) {
                when (text[cursor]) {
                    '"' -> {
                        val end = literalEnd(text, cursor)
                        if (firstArgument) keys.add(unescape(text.substring(cursor + 1, end)))
                        cursor = end + 1
                        continue
                    }
                    '(' -> depth += 1
                    ')' -> depth -= 1
                    ',' -> if (depth == 1) firstArgument = false
                }
                cursor += 1
            }
            from = cursor
        }
        return keys.filter { it.isNotEmpty() }
    }

    private fun literalEnd(text: String, open: Int): Int {
        var cursor = open + 1
        while (cursor < text.length && text[cursor] != '"') cursor += if (text[cursor] == '\\') 2 else 1
        return cursor
    }

    private fun unescape(literal: String): String =
        literal.replace("\\\"", "\"").replace("\\$", "$").replace("\\n", "\n").replace("\\\\", "\\")
}
