package com.junbingao.remotecontrol.win.sessions.drawer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.DirectoryListing
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.state.GatewayChannel
import com.junbingao.remotecontrol.core.state.request
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.shared.ErrorText
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException

/**
 * What the directory picker holds (`DirectoryPicker.tsx`): the listing on screen, and the one
 * folder name being typed while New folder is open (A37). A new one is made each time the picker
 * opens.
 */
class DirectoryBrowser(val deviceID: String, private val channel: GatewayChannel?) {
    var listing: DirectoryListing? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(true)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var naming: Boolean by mutableStateOf(false)
        private set
    var folderName: String by mutableStateOf("")
    var folderBusy: Boolean by mutableStateOf(false)
        private set
    var folderError: String? by mutableStateOf(null)
        private set

    /** Stand in `path`, or in the device's home when there is none. */
    suspend fun open(path: String?) {
        // The name being typed belongs to the directory on screen, so leaving it takes the row
        // with it.
        naming = false
        loading = true
        try {
            listing = (channel ?: throw TransportError.NotConnected)
                .request(GatewayRequest.dirs(deviceID = deviceID, path = path), DirectoryListing.serializer())
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = message(failure)
        } finally {
            loading = false
        }
    }

    fun startNaming() {
        folderName = ""
        folderError = null
        naming = true
    }

    fun stopNaming() {
        naming = false
    }

    /**
     * A37: the reply is the new directory's own listing, so making a folder is also walking into it
     * — "Use this directory" then picks what was just made.
     */
    suspend fun createFolder() {
        val name = folderName.trimmed
        val listing = listing
        if (listing == null || name.isEmpty() || folderBusy) return
        folderBusy = true
        folderError = null
        try {
            this.listing = (channel ?: throw TransportError.NotConnected)
                .request(GatewayRequest.mkdir(deviceID = deviceID, path = listing.path, name = name), DirectoryListing.serializer())
            error = null
            naming = false
            folderName = ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            folderError = folderRefusal(failure)
        } finally {
            folderBusy = false
        }
    }

    companion object {
        /**
         * `conflict` is the one outcome the picker says in its own words — the device reports an
         * existing entry of any kind, and a person only needs to know the name is taken. Everything
         * else, `bad_request` included, is the device's own sentence.
         */
        fun folderRefusal(error: Throwable): String {
            if ((error as? GatewayErrorBody)?.code == GatewayErrorCode.conflict) return S.newSession.newFolderExists
            return ErrorText.text(error)
        }

        /** A listing the device refused shows its own words; a failure that never reached it, the generic sentence. */
        private fun message(error: Throwable): String? {
            val reply = error as? GatewayErrorBody ?: return S.errors.generic
            return if (reply.message.isEmpty() || reply.message == reply.code.rawValue) null else reply.message
        }
    }
}
