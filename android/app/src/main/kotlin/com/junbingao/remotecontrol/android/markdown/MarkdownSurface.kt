package com.junbingao.remotecontrol.android.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * The web view one visual is drawn in, locked down to the one page the app hands it: the
 * counterpart of the iPhone's `WKWebView` with a non-persistent data store and the `rcmarkdown:`
 * scheme handler. Every request goes through [MarkdownLocalAssets], and a navigation anywhere but
 * the page is either a link the person tapped, opened outside, or nothing.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor") // The renderer is JavaScript; the page is the app's own.
internal class MarkdownSurface(context: Context) : WebView(context) {
    var onHeight: (Double) -> Unit = {}
    var onError: (String?) -> Unit = {}
    var onLink: (Uri) -> Unit = {}

    private val assets = MarkdownLocalAssets(context.assets)
    private var identity = ""

    init {
        settings.javaScriptEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.setGeolocationEnabled(false)
        // The page sets its own type size from the phone's font size; the web view adds nothing.
        settings.textZoom = 100
        // No cookies at all. The setting is the process's, and the app has no other web content.
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        CookieManager.getInstance().setAcceptCookie(false)
        setBackgroundColor(Color.TRANSPARENT)
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        addJavascriptInterface(MarkdownBridge { height, error ->
            height?.let { onHeight(it) }
            onError(error)
        }, MarkdownBridge.NAME)
        webViewClient = Client()
    }

    /** Draw one visual; the same one again is left as it is, as on the iPhone. */
    fun show(kind: MarkdownVisualKind, source: String, parts: List<MarkdownInlinePart>, dark: Boolean, fontPixels: Double) {
        val next = listOf(kind.rawValue, dark, fontPixels, source, parts).toString()
        if (next == identity) return
        identity = next
        if (!assets.isPackaged()) {
            onError(L10n.string("The preview assets are unavailable. The source is still readable."))
            return
        }
        assets.document = MarkdownVisualDocument.html(kind, source, parts, dark, fontPixels)
        loadUrl(MarkdownVisualDocument.PAGE)
    }

    fun release() {
        stopLoading()
        removeJavascriptInterface(MarkdownBridge.NAME)
        webViewClient = WebViewClient()
        destroy()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
            assets.respond(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.toString() == MarkdownVisualDocument.PAGE) return false
            if (request.hasGesture() && MarkdownVisualDocument.isOpenableLink(url.toString())) onLink(url)
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                onError(L10n.string("The preview did not finish. The source is still available."))
            }
        }
    }
}
