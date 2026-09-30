package com.junbingao.remotecontrol.core.transport

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The buffering both transport streams use.
 *
 * A stream of frames is worth reading for its most recent element, so back-pressure has to cost
 * the oldest one. Keeping the oldest is the other way round: once the buffer fills it keeps the
 * stale frames and discards every new one, so a burst of session events that outran the main
 * thread's pump would leave the app rendering the beginning of the burst and silently losing the
 * end of it.
 */
internal object EventBuffer {
    /** App frames. A burst of session events can outrun the pump on a busy session, and the transcript's gap repair costs a round trip. */
    const val appCapacity = 1024

    /** Dictation events, which are few and small — but a lost `stt.final` leaves the panel waiting for its 30 s timeout. */
    const val sttCapacity = 256

    /** A stream and the end that feeds it: `AsyncStream.makeStream` with `bufferingNewest`. */
    class Stream<Element>(val stream: Flow<Element>, val continuation: SendChannel<Element>)

    fun <Element> makeStream(capacity: Int): Stream<Element> {
        val channel = Channel<Element>(capacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        return Stream(channel.receiveAsFlow(), channel)
    }
}
