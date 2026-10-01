package com.junbingao.remotecontrol.win.platform

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The web's own Markdown pipeline, run in QuickJS: react-markdown 10's processor — remark-parse,
 * remark-gfm, remark-rehype and rehype-highlight on the common languages of highlight.js 11.11.2 —
 * as the Mac app bundles it (`macos/Sources/RCMac/Resources/Highlight/markdown.bundle.js`, whose
 * README says how it is built). The build serves that very file, so a table, a task list or a
 * highlighted block comes out of the parser exactly as it does in the browser and on the Mac.
 *
 * `hast(text)` is the bundle's `rcMarkdown(text)`: the tree as JSON, an element
 * `[tag, properties, children]` and a text node a string. Drawing it is the chat's.
 *
 * One engine for the app, on a thread of its own with room for deep documents, because a QuickJS
 * runtime is used from one thread at a time. A message is parsed once per text: a streaming answer
 * is parsed again on every delta, as React re-renders it.
 */
class MarkdownEngine private constructor() {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(null, task, "markdown", 16L * 1024 * 1024).apply { isDaemon = true }
    }
    private val thread = executor.asCoroutineDispatcher()
    private var runtime: QuickJs? = null
    private var loaded = false
    private val cache = object : LinkedHashMap<String, String?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean = size > CAPACITY
    }

    /**
     * The tree a message's text makes, as JSON; null without the pipeline — a bundle that failed to
     * load — when the text is drawn as it arrived, which is what the web draws while its Markdown
     * chunk is still on its way.
     */
    fun hast(text: String): String? = synchronized(cache) {
        if (cache.containsKey(text)) return cache[text]
        val tree = runCatching { runBlocking { parse(text) } }.getOrNull()
        cache[text] = tree
        tree
    }

    private suspend fun parse(text: String): String? = withContext(thread) {
        val js = runtime() ?: return@withContext null
        js.evaluate<String>("rcMarkdown(${JavaScript.string(text)})")
    }

    private suspend fun runtime(): QuickJs? {
        if (loaded) return runtime
        loaded = true
        val source = MarkdownEngine::class.java.getResourceAsStream("/highlight/markdown.bundle.js")
            ?.use { it.readBytes().decodeToString() } ?: return null
        val js = QuickJs.create(thread)
        js.maxStackSize = 8L * 1024 * 1024
        js.evaluate<Any?>(source, "markdown.bundle.js")
        runtime = js
        return js
    }

    companion object {
        /** How many trees are kept: enough for every message on screen and a page either side. */
        private const val CAPACITY = 256

        val shared: MarkdownEngine by lazy { MarkdownEngine() }
    }
}

/** How a Kotlin string is written into a script: as a JSON string literal, which is JavaScript. */
internal object JavaScript {
    fun string(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                ' ' -> out.append("\\u2028")
                ' ' -> out.append("\\u2029")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
