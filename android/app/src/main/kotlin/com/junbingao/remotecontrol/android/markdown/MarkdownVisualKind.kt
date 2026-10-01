package com.junbingao.remotecontrol.android.markdown

/**
 * The three things that leave native text for the web view (`MarkdownVisualView.swift`): a
 * Mermaid diagram, a display formula, and a paragraph with inline formulas in it. The raw value is
 * what the renderer reads as `input.kind`.
 */
enum class MarkdownVisualKind(val rawValue: String) {
    diagram("diagram"),
    math("math"),
    inlineMath("inlineMath"),
}

/**
 * One piece of an `inlineMath` paragraph, as the iPhone hands them to the renderer: a formula, or
 * a run of text with its emphasis. Stage 3 builds them from the core's `MarkdownMath.spans`.
 */
sealed interface MarkdownInlinePart {
    data class Math(val source: String, val display: Boolean) : MarkdownInlinePart

    data class Run(
        val text: String,
        val strong: Boolean = false,
        val em: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        /** Kept only when it is an http, https or mailto link, as on the iPhone. */
        val link: String? = null,
    ) : MarkdownInlinePart
}
