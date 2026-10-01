package com.junbingao.remotecontrol.win.voice

import com.junbingao.remotecontrol.core.state.L10n
import com.junbingao.remotecontrol.core.transport.GatewayHTTPClient
import com.junbingao.remotecontrol.core.transport.STTEvent
import com.junbingao.remotecontrol.core.transport.STTSocket
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * One utterance through the core's `STTSocket`, reported the way the web's `SttSocket` reports it
 * (`web/src/features/voice/sttSocket.ts`).
 *
 * Two things differ underneath and are mapped here. The core's socket words its own transport
 * failures in English, where the web's socket reports a failure with no message and the composer
 * says `voice.failed`; those become `Failed` with no message. And it gives up waiting for a final
 * transcript on a timer of its own, which the web leaves to `useVoice` (`VoiceTiming.finalTimeout`)
 * and which keeps the words without an error: that one becomes `Closed`.
 *
 * The socket confines its state to a dispatcher of its own, so the frames, the stop and the cancel
 * go to it through one queue, in the order they were given; its events come back on `tasks`, the
 * composer's thread.
 */
class GatewaySpeechStream(
    client: GatewayHTTPClient?,
    private val onEvent: (SpeechEvent) -> Unit,
    private val tasks: CoroutineScope,
) : SpeechStream {
    private sealed interface Outgoing {
        class Frame(val data: ByteArray) : Outgoing

        data object Stop : Outgoing

        data object Cancel : Outgoing
    }

    private val socket: STTSocket? = client?.let { STTSocket(client = it) }
    private val outgoing = Channel<Outgoing>(Channel.UNLIMITED)
    private var reader: Job? = null
    private var cancelled = false

    init {
        val socket = socket
        tasks.launch {
            for (item in outgoing) {
                when (item) {
                    is Outgoing.Frame -> socket?.append(item.data)
                    Outgoing.Stop -> socket?.stop()
                    Outgoing.Cancel -> {
                        socket?.cancel()
                        return@launch
                    }
                }
            }
        }
    }

    override suspend fun start() {
        val socket = socket ?: throw TransportError.Unauthorized
        socket.start()
        reader = tasks.launch {
            socket.events.takeWhile { !cancelled }.collect { onEvent(event(it)) }
            // The socket said its last word: nothing more goes to it.
            outgoing.close()
        }
    }

    override fun append(frame: ByteArray) {
        if (cancelled) return
        outgoing.trySend(Outgoing.Frame(frame))
    }

    override fun stop() {
        if (cancelled) return
        outgoing.trySend(Outgoing.Stop)
    }

    override fun cancel() {
        if (cancelled) return
        cancelled = true
        reader?.cancel()
        outgoing.trySend(Outgoing.Cancel)
        outgoing.close()
    }

    companion object {
        fun event(event: STTEvent): SpeechEvent = when (event) {
            is STTEvent.Partial -> SpeechEvent.Partial(event.text)
            is STTEvent.Final -> SpeechEvent.Final(event.text)
            STTEvent.Closed -> SpeechEvent.Closed
            is STTEvent.Failed -> when (event.message) {
                L10n.string("The gateway did not return a transcript.") -> SpeechEvent.Closed
                in ownSentences -> SpeechEvent.Failed(null)
                else -> SpeechEvent.Failed(event.message)
            }
        }

        /**
         * What the core's socket says for a failure of its own, rather than one the gateway
         * reported: `stt.error` with no message, and the transport's.
         */
        private val ownSentences: Set<String>
            get() = setOf(
                "Transcription failed.",
                "audio upload failed",
                L10n.string("The transcription connection dropped."),
                L10n.string("The transcript did not finish. What was recognised is in your draft."),
            )
    }
}
