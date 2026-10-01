package com.junbingao.remotecontrol.win.chat.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.QuestionAnswer
import com.junbingao.remotecontrol.core.protocol.SessionResult
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.GatewayChannel
import com.junbingao.remotecontrol.core.state.PendingSend
import com.junbingao.remotecontrol.core.state.request
import com.junbingao.remotecontrol.win.shared.ErrorText
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * The requests `ChatPage.tsx` issues from the transcript, the header and the resume notice, each
 * reporting its failure in the page's banner in the web's words (`errorText`, `refusalText`), and
 * each clearing the banner as it starts. What a reply carries of the session is handed to the
 * conversation at once, as the web upserts it.
 */
class ChatActions(private val chat: ChatStore, private val channel: GatewayChannel) {
    /** The banner's sentence for a request of the page's own. */
    var error: String? by mutableStateOf(null)
        private set
    var stopping: Boolean by mutableStateOf(false)
        private set

    /**
     * What the banner above the composer says: the page's own failure, or a failure the
     * conversation holds that the composer left for the page — a Remove, a setting, a takeover, an
     * answer typed in the field.
     */
    val banner: String? get() = error ?: ChatErrorWords.web(chat.errorMessage)

    fun dismiss() {
        error = null
        chat.clearError()
    }

    /**
     * A42: Stop on a terminal the device types into is an Escape, which the device refuses to type
     * while the CLI has a prompt up; that `conflict` is the device's own sentence.
     */
    suspend fun stop() {
        stopping = true
        error = null
        try {
            channel.request(GatewayRequest.stop(sessionID = chat.sessionID))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = ErrorText.refusal(failure, fallback = S.errors.stopFailed)
        } finally {
            stopping = false
        }
    }

    suspend fun approve(requestID: String, optionID: String) {
        error = null
        try {
            channel.request(GatewayRequest.approve(sessionID = chat.sessionID, requestID = requestID, optionID = optionID))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = ErrorText.text(failure, fallback = S.errors.approveFailed)
        }
    }

    /** The card's own Submit. A refusal travels back so the card keeps what was filled in (A20). */
    suspend fun answer(requestID: String, answers: Map<String, QuestionAnswer>): Boolean {
        error = null
        return try {
            channel.request(GatewayRequest.answer(sessionID = chat.sessionID, requestID = requestID, answers = answers))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = ErrorText.text(failure, fallback = S.errors.answerFailed)
            false
        }
    }

    /** "Open full output": the conversation fetches the block and swaps it in. */
    suspend fun openFull(blockID: String) {
        error = null
        val before = chat.errorMessage
        chat.loadFullBlock(blockID)
        val now = chat.errorMessage ?: return
        if (now == before) return
        chat.clearError()
        error = S.errors.expandFailed
    }

    /** A35: Change reports its own failure inside the popover. */
    suspend fun setResume(at: Long): Boolean {
        error = null
        return try {
            val result = channel.request(GatewayRequest.resumeSet(sessionID = chat.sessionID, at = Instant.ofEpochMilli(at)), SessionResult.serializer())
            chat.receive(AppFrame.SessionUpdated(result.session))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /** A35: Cancel at once, with no confirmation. */
    suspend fun cancelResume() {
        error = null
        try {
            val result = channel.request(GatewayRequest.resumeCancel(sessionID = chat.sessionID), SessionResult.serializer())
            chat.receive(AppFrame.SessionUpdated(result.session))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = ErrorText.text(failure, fallback = S.errors.resumeCancelFailed)
        }
    }

    /** The status line's Take over. */
    suspend fun takeover() {
        error = null
        try {
            val result = channel.request(GatewayRequest.takeover(sessionID = chat.sessionID), SessionResult.serializer())
            chat.receive(AppFrame.SessionUpdated(result.session))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = ErrorText.text(failure, fallback = S.errors.takeoverFailed)
        }
    }

    /** The unconfirmed bar's Retry, under the send's own request id. A refusal is the page's to report, as the web's catch does. */
    suspend fun retry(pending: PendingSend) {
        error = null
        val before = chat.errorMessage
        if (chat.retry(pending) != ChatStore.SendOutcome.refused) return
        val now = chat.errorMessage ?: return
        if (now == before) return
        chat.clearError()
        error = ChatErrorWords.web(now) ?: S.composer.sendFailed
    }
}

/**
 * The sentences the core writes for its own failures, in the web's words and the interface
 * language; a device's or a gateway's own sentence is shown as it arrived.
 */
object ChatErrorWords {
    fun web(message: String?): String? {
        if (message.isNullOrEmpty()) return null
        return when (message) {
            "That message has already been sent." -> S.composer.alreadySent
            "The gateway did not answer in time." -> S.errors.timeout
            "That device or session no longer exists." -> S.errors.notFound
            "That device is offline" -> S.errors.deviceOffline
            "Not connected to the gateway.", "The gateway sent a response this app could not read." -> S.errors.generic
            else -> message
        }
    }
}
