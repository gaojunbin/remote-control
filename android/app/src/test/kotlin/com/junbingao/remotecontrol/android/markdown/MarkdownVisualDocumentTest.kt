package com.junbingao.remotecontrol.android.markdown

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The page a visual is drawn on, as `MarkdownWebSurface.documentHTML()` builds it. */
@RunWith(AndroidJUnit4::class)
class MarkdownVisualDocumentTest {
    private fun payloadOf(html: String): JSONObject {
        val encoded = Regex("data-payload=\"([^\"]+)\"").find(html)!!.groupValues[1]
        return JSONObject(String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8))
    }

    @Test
    fun theSourceIsDataTheRendererReadsNeverMarkup() {
        val source = "graph TD; A-->B; </main><script>alert(1)</script>"
        val html = MarkdownVisualDocument.html(MarkdownVisualKind.diagram, source, emptyList(), dark = false, fontPixels = 17.0)
        assertFalse(html.contains("<script>alert"))
        val payload = payloadOf(html)
        assertEquals("diagram", payload.getString("kind"))
        assertEquals(source, payload.getString("source"))
        assertEquals(false, payload.getBoolean("dark"))
        assertFalse(payload.has("parts"))
    }

    @Test
    fun aDiagramLoadsMermaidAndAFormulaKatex() {
        val diagram = MarkdownVisualDocument.html(MarkdownVisualKind.diagram, "graph TD;A-->B", emptyList(), false, 17.0)
        val formula = MarkdownVisualDocument.html(MarkdownVisualKind.math, "x^2", emptyList(), false, 17.0)
        assertTrue(diagram.contains("${MarkdownVisualDocument.ASSETS}mermaid.min.js"))
        assertTrue(formula.contains("${MarkdownVisualDocument.ASSETS}katex.min.js"))
        assertTrue(formula.indexOf(MarkdownVisualDocument.BRIDGE) < formula.indexOf("renderer.js"))
    }

    @Test
    fun thePolicyNamesTheAppsOwnOriginAndNothingElse() {
        val html = MarkdownVisualDocument.html(MarkdownVisualKind.math, "x", emptyList(), false, 17.0)
        val policy = Regex("content=\"(default-src[^\"]+)\"").find(html)!!.groupValues[1]
        assertTrue(policy.startsWith("default-src 'none'"))
        assertTrue(policy.contains("script-src ${MarkdownVisualDocument.ORIGIN};"))
        assertTrue(policy.contains("connect-src 'none'"))
        assertFalse(policy.contains("unsafe-eval"))
        assertFalse(policy.contains("http:"))
    }

    @Test
    fun theAppearanceAndTheTypeSizeReachThePage() {
        val html = MarkdownVisualDocument.html(MarkdownVisualKind.math, "x", emptyList(), dark = true, fontPixels = 19.5)
        assertTrue(html.contains("color-scheme:dark"))
        assertTrue(html.contains("color:#f2f2f0"))
        assertTrue(html.contains("font:19.5px sans-serif"))
        assertEquals(true, payloadOf(html).getBoolean("dark"))
    }

    @Test
    fun inlinePartsCarryTheirEmphasisAndOnlyLinksThatOpenSafely() {
        val parts = listOf(
            MarkdownInlinePart.Run("Energy is ", strong = true),
            MarkdownInlinePart.Math("E=mc^2", display = false),
            MarkdownInlinePart.Run("see", link = "https://example.com"),
            MarkdownInlinePart.Run("run", link = "javascript:alert(1)"),
        )
        val html = MarkdownVisualDocument.html(MarkdownVisualKind.inlineMath, "src", parts, false, 17.0)
        val sent = payloadOf(html).getJSONArray("parts")
        assertEquals(4, sent.length())
        assertEquals(true, sent.getJSONObject(0).getBoolean("strong"))
        assertFalse(sent.getJSONObject(0).has("em"))
        assertEquals("E=mc^2", sent.getJSONObject(1).getString("math"))
        assertEquals(false, sent.getJSONObject(1).getBoolean("display"))
        assertEquals("https://example.com", sent.getJSONObject(2).getString("link"))
        assertFalse(sent.getJSONObject(3).has("link"))
    }

    @Test
    fun anOverlongSourceIsNotDrawnAtAll() {
        assertTrue(MarkdownVisualDocument.isTooLong(MarkdownVisualKind.math, "x".repeat(128 * 1024 + 1)))
        assertFalse(MarkdownVisualDocument.isTooLong(MarkdownVisualKind.math, "x".repeat(40 * 1024)))
        assertTrue(MarkdownVisualDocument.isTooLong(MarkdownVisualKind.diagram, "x".repeat(32 * 1024 + 1)))
        assertTrue(MarkdownVisualDocument.isTooLong(MarkdownVisualKind.diagram, "A-->B\n".repeat(500)))
        assertFalse(MarkdownVisualDocument.isTooLong(MarkdownVisualKind.diagram, "A-->B\n".repeat(400)))
    }
}
