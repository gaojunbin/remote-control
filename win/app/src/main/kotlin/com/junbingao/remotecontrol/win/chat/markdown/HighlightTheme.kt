package com.junbingao.remotecontrol.win.chat.markdown

/** How one run of highlighted code is drawn: an sRGB ink, bold or italic, and a tint behind it. */
data class HighlightStyle(
    val color: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val background: Int? = null,
)

/**
 * `highlight.js/styles/github.css`, the theme `Markdown.tsx` imports, as rules over the `hljs-*`
 * classes rehype-highlight puts on its spans. Each property a span does not get from a rule of its
 * own it inherits from the span around it, and among its own rules the one of higher specificity
 * wins, the later one on a tie, as CSS decides. A tint is not inherited, but the span that has one
 * paints it behind everything inside, so a run keeps the nearest one around it.
 */
object HighlightTheme {
    /** `.hljs { color: #24292e }`: the block's own ink. */
    val base = HighlightStyle(color = 0x24292E)

    private class Rule(
        /** Every class the span itself must carry. */
        val classes: List<String>,
        /** A class some enclosing span must carry (`.hljs-meta .hljs-keyword`). */
        val ancestor: String?,
        val color: Int?,
        val bold: Boolean?,
        val italic: Boolean?,
        val background: Int?,
    ) {
        val specificity: Int get() = classes.size + (if (ancestor == null) 0 else 1)
    }

    /** In the stylesheet's order. */
    private val rules: List<Rule> by lazy {
        val rules = mutableListOf<Rule>()
        fun add(selectors: List<String>, color: Int, bold: Boolean? = null, italic: Boolean? = null, background: Int? = null) {
            for (selector in selectors) {
                val parts = selector.split(" ")
                val own = parts.last().split(".")
                rules += Rule(own, if (parts.size > 1) parts.first() else null, color, bold, italic, background)
            }
        }
        add(
            listOf(
                "hljs-doctag", "hljs-keyword", "hljs-meta hljs-keyword", "hljs-template-tag", "hljs-template-variable",
                "hljs-type", "hljs-variable.language_",
            ),
            color = 0xD73A49,
        )
        add(listOf("hljs-title", "hljs-title.class_", "hljs-title.class_.inherited__", "hljs-title.function_"), color = 0x6F42C1)
        add(
            listOf(
                "hljs-attr", "hljs-attribute", "hljs-literal", "hljs-meta", "hljs-number", "hljs-operator", "hljs-variable",
                "hljs-selector-attr", "hljs-selector-class", "hljs-selector-id",
            ),
            color = 0x005CC5,
        )
        add(listOf("hljs-regexp", "hljs-string", "hljs-meta hljs-string"), color = 0x032F62)
        add(listOf("hljs-built_in", "hljs-symbol"), color = 0xE36209)
        add(listOf("hljs-comment", "hljs-code", "hljs-formula"), color = 0x6A737D)
        add(listOf("hljs-name", "hljs-quote", "hljs-selector-tag", "hljs-selector-pseudo"), color = 0x22863A)
        add(listOf("hljs-subst"), color = 0x24292E)
        add(listOf("hljs-section"), color = 0x005CC5, bold = true)
        add(listOf("hljs-bullet"), color = 0x735C0F)
        add(listOf("hljs-emphasis"), color = 0x24292E, italic = true)
        add(listOf("hljs-strong"), color = 0x24292E, bold = true)
        add(listOf("hljs-addition"), color = 0x22863A, background = 0xF0FFF4)
        add(listOf("hljs-deletion"), color = 0xB31D28, background = 0xFFEEF0)
        rules
    }

    /** The style of a span with `classes`, inside spans whose classes are `ancestors`, whose own style is `inherited`. */
    fun style(classes: List<String>, ancestors: List<List<String>>, inherited: HighlightStyle): HighlightStyle {
        val enclosing = ancestors.flatten().toSet()
        val matching = rules.withIndex()
            .filter { (_, rule) -> rule.classes.all { it in classes } && (rule.ancestor?.let { it in enclosing } ?: true) }
            .sortedWith(compareBy({ it.value.specificity }, { it.index }))
        var style = inherited
        for ((_, rule) in matching) {
            rule.color?.let { style = style.copy(color = it) }
            rule.bold?.let { style = style.copy(bold = it) }
            rule.italic?.let { style = style.copy(italic = it) }
            rule.background?.let { style = style.copy(background = it) }
        }
        return style
    }
}
