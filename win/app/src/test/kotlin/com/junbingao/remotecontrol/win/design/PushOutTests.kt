package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Lines broken as SwiftUI breaks the Mac's: `text/linebreaks.json` holds where SwiftUI started
 * each line of 250 paragraphs — two-line ones ending in a word that was pushed down and ones that
 * were not, longer ones, Chinese — set at 13 and 14 px in a box of the given width.
 */
class PushOutTests {
    private data class Case(val text: String, val size: Float, val width: Int, val starts: List<Int>)

    private val corpus by lazy {
        val text = javaClass.getResourceAsStream("/text/linebreaks.json")!!.use { it.readBytes().decodeToString() }
        Json.parseToJsonElement(text).jsonArray.map { element ->
            val case = element.jsonObject
            Case(
                case["text"]!!.jsonPrimitive.content,
                case["size"]!!.jsonPrimitive.float,
                case["width"]!!.jsonPrimitive.float.toInt(),
                case["starts"]!!.jsonArray.map { it.jsonPrimitive.int },
            )
        }
    }

    /** Where each line of `text` starts once it is pushed out at `width`, in the original's offsets. */
    private fun starts(text: String, size: Float, width: Int): List<Int> {
        val faced = FaceRuns.apply(AnnotatedString(text), size, FontWeight.Normal, false, 0f, InterfaceLanguage.en)
        val style = composeTextStyle(size, FontWeight.Normal, false, 0f, size * 1.5f, Color.Black, TextAlign.Start, InterfaceLanguage.en)
        val pushed = PushOut.at(width, faced, style, Int.MAX_VALUE, TextOverflow.Clip, Measure.measurer)
        val laid = Measure.measurer.measure(pushed, style, constraints = Constraints(maxWidth = width))
        return (0 until laid.lineCount).map { line ->
            val start = laid.getLineStart(line)
            start - pushed.text.take(start).count { it == '⁠' }
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun linesBreakWhereSwiftUIBreaksThem() {
        assertTrue(corpus.size >= 200)
        val wrong = corpus.filter { starts(it.text, it.size, it.width) != it.starts }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n") { "${it.width}: ${it.text} → ${starts(it.text, it.size, it.width)}, SwiftUI ${it.starts}" })
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun theEmptyStatesHintKeepsTwoWordsTogether() {
        assertEquals(listOf(0, 41), starts("Add the machine where your coding agents are installed.", 14f, 328))
    }

    @Test
    fun aJoinKeepsEveryStyleOnItsCharacters() {
        val text = AnnotatedString.Builder("中文 bold").apply {
            addStyle(SpanStyle(color = Color.Red), 0, 2)
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 3, 7)
            addLink(androidx.compose.ui.text.LinkAnnotation.Url("https://example.com"), 1, 7)
        }.toAnnotatedString()
        val glued = Glue.apply(text, listOf(Glue.Join(gapStart = 1, wordStart = 1), Glue.Join(gapStart = 2, wordStart = 3)))
        assertEquals("中⁠文 bold", glued.text)
        assertEquals(listOf(0 to 3), glued.spanStyles.filter { it.item.color == Color.Red }.map { it.start to it.end })
        assertEquals(listOf(4 to 8), glued.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { it.start to it.end })
        assertEquals(listOf(2 to 8), glued.getLinkAnnotations(0, glued.length).map { it.start to it.end })
    }
}
