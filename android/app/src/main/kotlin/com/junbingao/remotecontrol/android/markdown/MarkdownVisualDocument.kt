package com.junbingao.remotecontrol.android.markdown

import android.util.Base64
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The page one diagram or formula is drawn on — the document `MarkdownWebSurface.documentHTML()`
 * builds on the iPhone, with the same payload for the same renderer, the same styles in the
 * system sans, and the same Content Security Policy pointed at the one origin the app serves
 * itself from: nothing loads from anywhere else, no script runs that the app did not ship, and
 * the author's source is data the renderer reads, never code.
 */
internal object MarkdownVisualDocument {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PAGE = "$ORIGIN/markdown-view/index.html"
    const val BRIDGE = "$ORIGIN/markdown-view/bridge.js"
    const val ASSETS = "$ORIGIN/markdown/"

    /** Longer than this is not drawn at all ("Too long to render"), as on the iPhone. */
    fun isTooLong(kind: MarkdownVisualKind, source: String): Boolean {
        if (source.toByteArray(Charsets.UTF_8).size > 128 * 1024) return true
        if (kind != MarkdownVisualKind.diagram) return false
        return source.codePointCount(0, source.length) > 32 * 1024 || source.split("\n").size > 500
    }

    fun html(kind: MarkdownVisualKind, source: String, parts: List<MarkdownInlinePart>, dark: Boolean, fontPixels: Double): String {
        val encoded = Base64.encodeToString(payload(kind, source, parts, dark).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val library = if (kind == MarkdownVisualKind.diagram) "mermaid.min.js" else "katex.min.js"
        val color = if (dark) "#f2f2f0" else "#111111"
        val font = String.format(Locale.ROOT, "%.1f", fontPixels)
        return """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src $ORIGIN; style-src 'unsafe-inline' $ORIGIN; font-src $ORIGIN; img-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
        <link rel="stylesheet" href="${ASSETS}katex.min.css">
        <style>:root{color-scheme:${if (dark) "dark" else "light"}}
        html,body{margin:0;padding:0;background:transparent;color:$color;font:${font}px sans-serif;line-height:1.6}
        #content{padding:4px 0 8px;overflow-x:auto;overflow-y:hidden;overflow-wrap:anywhere;white-space:pre-wrap}
        svg{display:block;max-width:100%;height:auto;margin:auto} .katex-display{margin:8px 0;text-align:left}.katex{font-size:1.08em}
        code{font-family:monospace;background:rgba(128,128,128,.12);padding:1px 3px;border-radius:3px}a{color:$color}
        </style><script src="$BRIDGE"></script><script src="$ASSETS$library" defer></script><script src="${ASSETS}renderer.js" defer></script>
        </head><body><main id="content" data-payload="$encoded"></main></body></html>
        """.trimIndent()
    }

    /** What `renderer.js` reads: the kind, the source, the appearance, and the inline parts. */
    fun payload(kind: MarkdownVisualKind, source: String, parts: List<MarkdownInlinePart>, dark: Boolean): String {
        val payload = JSONObject()
            .put("kind", kind.rawValue)
            .put("source", source)
            .put("dark", dark)
        if (kind == MarkdownVisualKind.inlineMath) payload.put("parts", JSONArray(parts.map(::part)))
        return payload.toString()
    }

    private fun part(part: MarkdownInlinePart): JSONObject = when (part) {
        is MarkdownInlinePart.Math -> JSONObject().put("math", part.source).put("display", part.display)
        is MarkdownInlinePart.Run -> JSONObject().put("text", part.text).apply {
            if (part.strong) put("strong", true)
            if (part.em) put("em", true)
            if (part.code) put("code", true)
            if (part.strike) put("strike", true)
            part.link?.takeIf(::isOpenableLink)?.let { put("link", it) }
        }
    }

    /** The only links a diagram or a formula may open: the web and mail, as on the iPhone. */
    fun isOpenableLink(link: String): Boolean {
        val scheme = link.substringBefore(':', "").lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https" || scheme == "mailto"
    }
}
