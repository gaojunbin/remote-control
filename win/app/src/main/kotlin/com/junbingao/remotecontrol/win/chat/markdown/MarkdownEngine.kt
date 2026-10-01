package com.junbingao.remotecontrol.win.chat.markdown

import com.junbingao.remotecontrol.win.platform.MarkdownEngine

/**
 * The document a message's text makes: the web's own pipeline — the platform's `MarkdownEngine`,
 * react-markdown 10's processor run in QuickJS — and what the chat builds of the hast it returns.
 * Without the pipeline — a bundle that failed to load — the text is drawn as it arrived, which is
 * what the web draws while its Markdown chunk is still on its way.
 *
 * A message is built once per text: a streaming answer is built again on every delta, as React
 * re-renders it.
 */
fun MarkdownEngine.document(text: String): ChatMarkdown = Documents.document(text) {
    hast(text)?.let(HastNode::decode)?.let(MDBuilder::document) ?: ChatMarkdown.Plain(text)
}

/** The documents built, by text: enough for every message on screen and a page either side, few enough that a long day leaves nothing behind. */
private object Documents {
    private const val CAPACITY = 256

    private val cache = object : LinkedHashMap<String, ChatMarkdown>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChatMarkdown>): Boolean = size > CAPACITY
    }

    fun document(text: String, build: () -> ChatMarkdown): ChatMarkdown = synchronized(cache) {
        cache[text] ?: build().also { cache[text] = it }
    }
}
