package com.junbingao.remotecontrol.android.screens.chat.markdown

import com.junbingao.remotecontrol.core.markdown.MarkdownDocument
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A streaming answer is parsed at most ten times a second, and a short history row before it is first drawn. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMarkdownParseModelTest {
    /** What a model holds, as the blocks a fresh parse of [source] gives. */
    private fun MarkdownParseModel.reads(source: String) = document.blocks == MarkdownDocument(source).blocks

    @Test
    fun aShortSourceIsReadyBeforeTheFirstDraw() = runTest {
        val model = MarkdownParseModel("**done**", backgroundScope)
        assertTrue(model.reads("**done**"))
    }

    @Test
    fun aLongSourceIsParsedOffTheFirstDraw() = runTest {
        val long = "a".repeat(MarkdownParseModel.synchronousSeedByteLimit + 1)
        val model = MarkdownParseModel(long, backgroundScope, StandardTestDispatcher(testScheduler))
        assertTrue("nothing is parsed before the view draws", model.document.blocks.isEmpty())
        model.submit(long)
        runCurrent()
        assertTrue(model.reads(long))
    }

    @Test
    fun deltasBetweenRefreshesCoalesceIntoTheLatestSource() = runTest {
        val model = MarkdownParseModel("", backgroundScope, StandardTestDispatcher(testScheduler))
        model.submit("one")
        runCurrent()
        assertTrue(model.reads("one"))
        model.submit("one two")
        model.submit("one two three")
        runCurrent()
        assertTrue("nothing is parsed again inside the interval", model.reads("one"))
        advanceTimeBy(MarkdownParseModel.refreshInterval.inWholeMilliseconds + 1)
        runCurrent()
        assertTrue("and then only the latest whole source", model.reads("one two three"))
    }

    @Test
    fun theSizeCheckCountsBytesAndStopsAtTheLimit() {
        assertEquals(3, MarkdownParseModel.utf8Size("abc", 10))
        assertEquals("a Chinese character is three bytes", 6, MarkdownParseModel.utf8Size("中文", 10))
        assertTrue("it stops counting at the limit", MarkdownParseModel.utf8Size("a".repeat(1_000), 10) <= 10)
    }
}
