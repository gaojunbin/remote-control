package com.junbingao.remotecontrol.android.markdown

import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Everything the Markdown web view may load, answered by the app itself — the counterpart of the
 * iPhone's `rcmarkdown:` scheme handler. The page, the bridge, and the packaged renderer, KaTeX
 * and Mermaid files (js, css and woff2 under `assets/markdown/`, which the build copies from the
 * iPhone's own directory); every other request, on this origin or any other, is answered with
 * nothing, so the view cannot read an arbitrary file, reach the gateway or touch the network on
 * behalf of what an agent wrote.
 */
internal class MarkdownLocalAssets(private val assets: AssetManager) {
    /** The page on screen, replaced whenever the source, the appearance or the size changes. */
    @Volatile var document: String = ""

    fun respond(url: Uri): WebResourceResponse = when (val resolved = resolve(url)) {
        is Resolved.Page -> text("text/html", document)
        is Resolved.Bridge -> text("application/javascript", MarkdownBridge.SCRIPT)
        is Resolved.Asset -> open(resolved.path)?.let { found(resolved.mime, resolved.encoding, it) } ?: nothing()
        null -> nothing()
    }

    private fun open(path: String): InputStream? = try {
        assets.open(path)
    } catch (_: IOException) {
        null
    }

    /** Whether the packaged renderer is there at all; without it every visual shows its source. */
    fun isPackaged(): Boolean = try {
        assets.list("markdown")?.contains("renderer.js") == true
    } catch (_: IOException) {
        false
    }

    sealed interface Resolved {
        data object Page : Resolved
        data object Bridge : Resolved
        data class Asset(val path: String, val mime: String, val encoding: String?) : Resolved
    }

    companion object {
        private val types = mapOf(
            "js" to ("application/javascript" to "utf-8"),
            "css" to ("text/css" to "utf-8"),
            "woff2" to ("font/woff2" to null),
        )

        /**
         * What a URL is, or null for everything the view may not load: another host, another
         * scheme, a path out of the directory, a file of any other kind.
         */
        fun resolve(url: Uri): Resolved? {
            if (url.scheme != "https" || url.host != MarkdownVisualDocument.ORIGIN.toUri().host) return null
            val path = url.path ?: return null
            if (path == MarkdownVisualDocument.PAGE.toUri().path) return Resolved.Page
            if (path == MarkdownVisualDocument.BRIDGE.toUri().path) return Resolved.Bridge
            val prefix = MarkdownVisualDocument.ASSETS.toUri().path ?: return null
            if (!path.startsWith(prefix)) return null
            val relative = path.removePrefix(prefix)
            val segments = relative.split('/')
            if (relative.isEmpty() || segments.any { it.isEmpty() || it == "." || it == ".." }) return null
            val (mime, encoding) = types[relative.substringAfterLast('.', "")] ?: return null
            return Resolved.Asset("markdown/$relative", mime, encoding)
        }

        private fun text(mime: String, body: String) =
            found(mime, "utf-8", ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))

        private fun found(mime: String, encoding: String?, data: InputStream) =
            WebResourceResponse(mime, encoding, 200, "OK", emptyMap(), data)

        private fun nothing() =
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
