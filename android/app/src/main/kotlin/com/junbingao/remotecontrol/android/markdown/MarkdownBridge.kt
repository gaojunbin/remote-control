package com.junbingao.remotecontrol.android.markdown

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONException
import org.json.JSONObject

/**
 * The one message the renderer sends: how tall it drew, and what went wrong if anything did.
 *
 * `renderer.js` is the iPhone's file, unchanged, and posts to WebKit's
 * `window.webkit.messageHandlers.renderStatus`. [SCRIPT], loaded before it from the app's own
 * origin, gives it that name on Android on top of the one method exposed here, which takes a
 * string and nothing else.
 */
internal class MarkdownBridge(private val onStatus: (height: Double?, error: String?) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    /** Called on WebView's own thread; the status is read here and handed to the main thread. */
    @JavascriptInterface
    fun post(message: String) {
        val (height, error) = parse(message) ?: return
        main.post { onStatus(height, error) }
    }

    companion object {
        const val NAME = "RCMarkdownBridge"

        const val SCRIPT = """(function () {
  "use strict";
  var bridge = window.$NAME;
  if (!bridge) return;
  window.webkit = { messageHandlers: { renderStatus: {
    postMessage: function (message) { bridge.post(JSON.stringify(message)); }
  } } };
})();
"""

        /**
         * The height, clamped as the iPhone clamps it (36 to 4000 points), and the error cut to
         * 160 characters; a message that is not the renderer's shape is ignored.
         */
        fun parse(message: String): Pair<Double?, String?>? = try {
            val json = JSONObject(message)
            val height = json.optDouble("height", Double.NaN).takeIf { it.isFinite() }?.coerceIn(36.0, 4000.0)
            val error = if (json.has("error") && !json.isNull("error")) json.optString("error").take(160) else null
            height to error
        } catch (_: JSONException) {
            null
        }
    }
}
