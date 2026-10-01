package com.junbingao.remotecontrol.android.permissions

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One prompt at a time: a second ask while one is up waits for the same answer. */
class PendingAnswerTest {
    @Test
    fun theFirstCallerShowsThePromptAndTheSecondWaits() = runTest {
        val answer = PendingAnswer()
        val (first, showsFirst) = answer.await()
        val (second, showsSecond) = answer.await()
        assertTrue(showsFirst)
        assertFalse(showsSecond)
        val waiting = async { second.await() }
        answer.resolve(true)
        assertEquals(true, first.await())
        assertEquals(true, waiting.await())
    }

    @Test
    fun theNextAskAfterAnAnswerIsAPromptOfItsOwn() {
        val answer = PendingAnswer()
        answer.await()
        answer.resolve(false)
        assertTrue(answer.await().second)
    }
}
