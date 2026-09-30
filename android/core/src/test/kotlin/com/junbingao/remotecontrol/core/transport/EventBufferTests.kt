package com.junbingao.remotecontrol.core.transport

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What back-pressure costs on the two transport streams. */
class EventBufferTests {
    /** A full buffer drops the oldest element, never the newest. */
    @Test
    fun theNewestSurvive() = runTest {
        val stream = EventBuffer.makeStream<Int>(capacity = 3)
        for (value in 1..5) stream.continuation.trySend(value)
        stream.continuation.close()

        val received = stream.stream.toList()
        assertEquals(listOf(3, 4, 5), received, "a burst that outran the reader loses its beginning, not its end")
    }

    /** Both transports declare their capacity here. */
    @Test
    fun capacities() {
        assertEquals(1024, EventBuffer.appCapacity)
        assertEquals(256, EventBuffer.sttCapacity)
    }
}
