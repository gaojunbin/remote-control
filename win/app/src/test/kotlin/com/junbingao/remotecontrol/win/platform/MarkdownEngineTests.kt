package com.junbingao.remotecontrol.win.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The web's pipeline, run from the Mac app's own bundle in QuickJS, against the trees the Mac
 * app's JavaScriptCore made of the same Markdown (`markdown/corpus.json`: the Mac's Markdown tests'
 * inputs, the chat's sample message, and the shapes a message takes — raw HTML, unsafe links,
 * footnotes, tables, Chinese, highlighted code in a dozen languages). Every tree is the Mac's,
 * character for character.
 */
class MarkdownEngineTests {
    private val corpus by lazy {
        val text = javaClass.getResourceAsStream("/markdown/corpus.json")!!.use { it.readBytes().decodeToString() }
        Json.parseToJsonElement(text).jsonArray.map {
            it.jsonObject["markdown"]!!.jsonPrimitive.content to it.jsonObject["hast"]!!.jsonPrimitive.content
        }
    }

    @Test
    fun theBundleLoadsAndParses() {
        assertEquals("""[["p",{},["hello"]]]""", MarkdownEngine.shared.hast("hello"))
    }

    @Test
    fun everyTreeIsTheMacApps() {
        assertTrue(corpus.size >= 30)
        for ((markdown, expected) in corpus) {
            assertEquals(expected, MarkdownEngine.shared.hast(markdown), markdown.take(40))
        }
    }

    @Test
    fun aTextIsParsedOnce() {
        val first = MarkdownEngine.shared.hast("once *more*")
        assertNotNull(first)
        assertSame(first, MarkdownEngine.shared.hast("once *more*"))
    }

    @Test
    fun anyStringIsWrittenIntoTheScriptAsItIs() {
        assertEquals("\"a\\\"b\\\\c\\n\\u2028\\u0001\"", JavaScript.string("a\"b\\c\n \u0001"))
    }
}
