package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation

/**
 * Two words a line may not break between: the spaces between them become no-break spaces, and
 * where there are none — between two Chinese characters, after a hyphen — a word joiner goes in.
 * Every style, annotation and link keeps the characters it had.
 */
internal object Glue {
    /** The break before `wordStart`, whose whitespace starts at `gapStart`, taken away. */
    data class Join(val gapStart: Int, val wordStart: Int)

    private const val NO_BREAK_SPACE = ' '
    private const val WORD_JOINER = '⁠'

    fun apply(text: AnnotatedString, joins: List<Join>): AnnotatedString {
        val chars = StringBuilder(text.text)
        for (join in joins) for (index in join.gapStart until join.wordStart) chars.setCharAt(index, NO_BREAK_SPACE)
        val inserted = joins.filter { it.gapStart == it.wordStart }.map { it.wordStart }.sorted()
        for (index in inserted.asReversed()) chars.insert(index, WORD_JOINER)
        if (inserted.isEmpty()) return rebuilt(text, chars.toString()) { it }
        // An insertion moves what starts at it and leaves what ends at it.
        return rebuilt(text, chars.toString()) { range -> range.first + inserted.count { it <= range.first } to range.second + inserted.count { it < range.second } }
    }

    private fun rebuilt(text: AnnotatedString, chars: String, moved: (Pair<Int, Int>) -> Pair<Int, Int>): AnnotatedString {
        val builder = AnnotatedString.Builder(chars)
        for (span in text.spanStyles) {
            val (start, end) = moved(span.start to span.end)
            builder.addStyle(span.item, start, end)
        }
        for (paragraph in text.paragraphStyles) {
            val (start, end) = moved(paragraph.start to paragraph.end)
            builder.addStyle(paragraph.item, start, end)
        }
        for (annotation in text.getStringAnnotations(0, text.length)) {
            val (start, end) = moved(annotation.start to annotation.end)
            builder.addStringAnnotation(annotation.tag, annotation.item, start, end)
        }
        for (link in text.getLinkAnnotations(0, text.length)) {
            val (start, end) = moved(link.start to link.end)
            when (val item = link.item) {
                is LinkAnnotation.Url -> builder.addLink(item, start, end)
                is LinkAnnotation.Clickable -> builder.addLink(item, start, end)
            }
        }
        return builder.toAnnotatedString()
    }
}
