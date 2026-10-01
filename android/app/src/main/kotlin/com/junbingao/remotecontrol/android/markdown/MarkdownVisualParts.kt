package com.junbingao.remotecontrol.android.markdown

import com.junbingao.remotecontrol.core.markdown.MarkdownMath

/**
 * The pieces of an `inlineMath` paragraph, as `MarkdownVisualView.swift` hands them to the
 * renderer: the core's `MarkdownMath.spans` decides where each formula is, and the text between
 * them is cut into runs of its own emphasis by [runs] — the native Markdown text's inline reading
 * (the iPhone's `markdownAttributed`), which the conversation passes in; on its own, text is one
 * plain run.
 */
object MarkdownVisualParts {
    fun of(source: String, runs: (String) -> List<MarkdownInlinePart.Run> = ::plain): List<MarkdownInlinePart> =
        MarkdownMath.spans(source).flatMap { span ->
            if (span.isMath) listOf(MarkdownInlinePart.Math(span.text, span.display)) else runs(span.text)
        }

    /** Text with no emphasis read into it: one run. */
    fun plain(text: String): List<MarkdownInlinePart.Run> = listOf(MarkdownInlinePart.Run(text))
}
