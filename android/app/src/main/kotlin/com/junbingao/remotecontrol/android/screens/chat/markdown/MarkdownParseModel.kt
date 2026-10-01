package com.junbingao.remotecontrol.android.screens.chat.markdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.markdown.MarkdownDocument
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One parser per visible Markdown view. Continuous output refreshes at most 10 times a second;
 * all intervening deltas coalesce into the latest full source. There is no trailing-only debounce
 * and no task fan-out on every token.
 *
 * [scope] is the view's own, so the work stops with it; the parse itself runs on [parsing].
 */
class MarkdownParseModel(
    initialSource: String = "",
    private val scope: CoroutineScope,
    private val parsing: CoroutineDispatcher = Dispatchers.Default,
) {
    var document: MarkdownDocument by mutableStateOf(MarkdownDocument(""))
        private set
    private var publishedSource: String? = null
    private var pendingSource: String? = null
    private var worker: Job? = null
    private var generation = 0
    private var lastPublish: TimeSource.Monotonic.ValueTimeMark? = null

    init {
        // Seed small history rows before the view measures them. Larger sources keep the
        // asynchronous path; inspecting the size visits at most this many bytes plus one.
        if (utf8Size(initialSource, synchronousSeedByteLimit + 1) <= synchronousSeedByteLimit) {
            document = MarkdownDocument(initialSource)
            publishedSource = initialSource
        }
    }

    fun submit(source: String) {
        if (source == pendingSource || (source == publishedSource && worker == null)) return
        pendingSource = source
        if (worker != null) return
        val epoch = generation
        worker = scope.launch {
            try {
                while (isActive && generation == epoch) {
                    lastPublish?.let { previous ->
                        val remaining = refreshInterval - previous.elapsedNow()
                        if (remaining > Duration.ZERO) delay(remaining)
                    }
                    if (!isActive || generation != epoch) break
                    val next = pendingSource ?: break
                    pendingSource = null
                    val parsed = withContext(parsing) { MarkdownDocument(next, isCancelled = { !isActive }) }
                    if (!isActive || generation != epoch) break
                    document = parsed
                    publishedSource = next
                    lastPublish = TimeSource.Monotonic.markNow()
                    if (pendingSource == null) break
                }
            } finally {
                if (generation == epoch) worker = null
            }
        }
    }

    fun cancel() {
        generation += 1
        worker?.cancel()
        worker = null
        pendingSource = null
    }

    companion object {
        /** Sources up to this many UTF-8 bytes are parsed before the view first draws. */
        const val synchronousSeedByteLimit = 32 * 1024

        /** How often a streaming answer is parsed again, at most. */
        val refreshInterval: Duration = 100.milliseconds

        /** The UTF-8 length of [text], counting no further than [limit]. */
        fun utf8Size(text: String, limit: Int): Int {
            var bytes = 0
            var index = 0
            while (index < text.length && bytes < limit) {
                val code = text.codePointAt(index)
                bytes += when {
                    code < 0x80 -> 1
                    code < 0x800 -> 2
                    code < 0x10000 -> 3
                    else -> 4
                }
                index += Character.charCount(code)
            }
            return bytes
        }
    }
}
