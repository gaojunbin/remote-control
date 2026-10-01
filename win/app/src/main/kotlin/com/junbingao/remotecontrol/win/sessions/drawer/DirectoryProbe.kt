package com.junbingao.remotecontrol.win.sessions.drawer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.DirectoryListing
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.GitStatus
import com.junbingao.remotecontrol.core.state.GatewayChannel
import com.junbingao.remotecontrol.core.state.request
import com.junbingao.remotecontrol.core.state.trimmed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * `useDirectoryProbe`: whether the working directory the reader typed exists on the device, and its
 * git status, asked 400 ms after the typing stops.
 */
class DirectoryProbe {
    enum class Status { idle, checking, exists, missing }

    /** What the probe says about one path: whether it is there, and its git status when it is. */
    data class Reading(val status: Status, val git: GitStatus? = null)

    /** The answer, and the device and path it answers for. */
    private var answer: Pair<String, Reading> by mutableStateOf("" to Reading(Status.idle))

    /** Nothing to ask about is idle; an answer for an older path reads as checking. */
    fun status(deviceID: String?, path: String): Reading {
        if (deviceID == null || path.trimmed.isEmpty()) return Reading(Status.idle)
        if (answer.first != key(deviceID, path)) return Reading(Status.checking)
        return answer.second
    }

    /**
     * One probe, run for every change of the device or the path; a newer one cancels it, which is
     * what the pause before asking is for.
     */
    suspend fun run(deviceID: String?, path: String, channel: GatewayChannel?) {
        val trimmed = path.trimmed
        if (deviceID == null || trimmed.isEmpty() || channel == null) return
        val key = key(deviceID, path)
        delay(debounce)
        answer = try {
            channel.request(GatewayRequest.dirs(deviceID = deviceID, path = trimmed), DirectoryListing.serializer())
            val git = try {
                channel.request(GatewayRequest.git(deviceID = deviceID, path = trimmed), GitStatus.serializer())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            key to Reading(Status.exists, git)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            key to Reading(Status.missing)
        }
    }

    companion object {
        private val debounce = 400.milliseconds

        fun key(deviceID: String?, path: String): String = "${deviceID ?: ""} ${path.trimmed}"
    }
}
