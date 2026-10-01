package com.junbingao.remotecontrol.android.markdown

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The gate every request of the Markdown web view goes through. */
@RunWith(AndroidJUnit4::class)
class MarkdownLocalAssetsTest {
    private fun resolve(url: String) = MarkdownLocalAssets.resolve(Uri.parse(url))

    @Test
    fun thePageAndTheBridgeAreTheAppsOwn() {
        assertEquals(MarkdownLocalAssets.Resolved.Page, resolve(MarkdownVisualDocument.PAGE))
        assertEquals(MarkdownLocalAssets.Resolved.Bridge, resolve(MarkdownVisualDocument.BRIDGE))
    }

    @Test
    fun packagedScriptsStylesAndFontsAreServed() {
        assertEquals(
            MarkdownLocalAssets.Resolved.Asset("markdown/renderer.js", "application/javascript", "utf-8"),
            resolve("${MarkdownVisualDocument.ASSETS}renderer.js"),
        )
        assertEquals("text/css", (resolve("${MarkdownVisualDocument.ASSETS}katex.min.css") as MarkdownLocalAssets.Resolved.Asset).mime)
        assertEquals(
            MarkdownLocalAssets.Resolved.Asset("markdown/fonts/KaTeX_Main-Regular.woff2", "font/woff2", null),
            resolve("${MarkdownVisualDocument.ASSETS}fonts/KaTeX_Main-Regular.woff2"),
        )
    }

    @Test
    fun nothingElseIsAnswered() {
        for (url in listOf(
            "https://example.com/markdown/renderer.js",
            "http://appassets.androidplatform.net/markdown/renderer.js",
            "file:///android_asset/markdown/renderer.js",
            "${MarkdownVisualDocument.ASSETS}VENDOR.json",
            "${MarkdownVisualDocument.ASSETS}katex-LICENSE.txt",
            "${MarkdownVisualDocument.ASSETS}../secrets.js",
            "${MarkdownVisualDocument.ASSETS}%2e%2e/secrets.js",
            "${MarkdownVisualDocument.ASSETS}",
            "${MarkdownVisualDocument.ORIGIN}/other/renderer.js",
            "${MarkdownVisualDocument.ORIGIN}/markdown-view/other.html",
        )) {
            assertNull(url, resolve(url))
        }
    }

    @Test
    fun theRendererIsPackagedAndARefusalIsANotFound() {
        val assets = MarkdownLocalAssets(ApplicationProvider.getApplicationContext<Context>().assets)
        assertTrue("the build copies the iPhone's renderer into the APK", assets.isPackaged())
        val served = assets.respond(Uri.parse("${MarkdownVisualDocument.ASSETS}renderer.js"))
        assertEquals(200, served.statusCode)
        assertTrue(served.data.readBytes().decodeToString().contains("renderStatus"))
        assertEquals(404, assets.respond(Uri.parse("https://example.com/x.js")).statusCode)
    }

    @Test
    fun theBridgeReadsOnlyTheRenderersMessage() {
        assertEquals(120.0 to null, MarkdownBridge.parse("""{"height":120}"""))
        assertEquals(36.0 to null, MarkdownBridge.parse("""{"height":2}"""))
        assertEquals(4000.0 to null, MarkdownBridge.parse("""{"height":90000}"""))
        val long = "x".repeat(400)
        assertEquals(40.0 to "x".repeat(160), MarkdownBridge.parse("""{"height":40,"error":"$long"}"""))
        assertEquals(null to null, MarkdownBridge.parse("""{"height":"tall"}"""))
        assertNull(MarkdownBridge.parse("not json"))
    }
}
