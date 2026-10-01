package com.junbingao.remotecontrol.android.voice

import com.junbingao.remotecontrol.core.transport.STTSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The capture runs on a thread of its own while the main thread swaps sockets underneath it, so
 * the socket taking audio and the level last measured both pass through a lock — the iPhone's
 * `STTAudioRoute`. The level is what lets a segment boundary wait for a pause in the speech.
 *
 * Chunks reach the socket in the order they were captured: one reader takes them from a channel
 * and appends each to whichever socket was taking audio when it was captured.
 */
internal class STTAudioRoute(scope: CoroutineScope) {
    private val lock = Any()
    private var socket: STTSocket? = null
    private var level = 0.0
    private val chunks = Channel<Pair<STTSocket, ByteArray>>(Channel.UNLIMITED)

    init {
        scope.launch { for ((target, pcm) in chunks) target.append(pcm) }
    }

    val lastLevel: Double get() = synchronized(lock) { level }

    fun send(pcm: ByteArray, level: Double) {
        val target = synchronized(lock) {
            this.level = if (level.isFinite()) level else 0.0
            socket
        } ?: return
        chunks.trySend(target to pcm)
    }

    /** Install a socket and hand back the one it replaces. */
    fun exchange(next: STTSocket?): STTSocket? = synchronized(lock) {
        val previous = socket
        socket = next
        previous
    }
}
