package com.junbingao.remotecontrol.android.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** A paragraph with inline formulas, cut where the core's `MarkdownMath` finds them. */
class MarkdownVisualPartsTest {
    @Test
    fun formulasAndTextAlternateInTheirOrder() {
        val parts = MarkdownVisualParts.of("Euler: \$e^{i\\pi}+1=0\$ holds, and \$\$x^2\$\$ too.")
        assertEquals(
            listOf(
                MarkdownInlinePart.Run("Euler: "),
                MarkdownInlinePart.Math("e^{i\\pi}+1=0", display = false),
                MarkdownInlinePart.Run(" holds, and "),
                MarkdownInlinePart.Math("x^2", display = true),
                MarkdownInlinePart.Run(" too."),
            ),
            parts,
        )
    }

    @Test
    fun theTextBetweenIsReadByTheRunsItIsGiven() {
        val parts = MarkdownVisualParts.of("**bold** \$x\$") { text -> listOf(MarkdownInlinePart.Run(text.trim('*', ' '), strong = true)) }
        assertEquals(MarkdownInlinePart.Run("bold", strong = true), parts.first())
        assertEquals(MarkdownInlinePart.Math("x", display = false), parts.last())
    }

    @Test
    fun aPriceIsNotAFormula() {
        assertEquals(listOf(MarkdownInlinePart.Run("costs \$5 or \$10")), MarkdownVisualParts.of("costs \$5 or \$10"))
    }
}
