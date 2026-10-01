package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.PreferencePatch
import com.junbingao.remotecontrol.core.protocol.Preferences
import kotlinx.coroutines.CancellationException

/**
 * Amendment A35: the account's resume switch, as this app reads and writes it.
 *
 * It is the gateway's, not the phone's, so nothing here is stored locally: the value is seeded from
 * `hello`, replaced by `preferences.updated` whenever another app or another device changes it, and
 * written with `PATCH /api/preferences`. Null means the gateway sent none, which is a gateway older
 * than the amendment, and the switch is shown disabled.
 *
 * The same object carries the Settings preferences A41 moved to the account; those are
 * [PreferenceSync]'s, which mirrors them into [SettingsStore]. This store reads and writes the one
 * switch and leaves the rest of the object as it found it.
 */
class PreferencesStore {
    var preferences: Preferences? by mutableStateOf(null)
        private set

    /** What the last write failed with, once, for the screen to show. */
    var errorMessage: String? by mutableStateOf(null)
        private set

    /** True while a write is out, so the switch cannot start a second one. */
    var isWriting: Boolean by mutableStateOf(false)
        private set

    private var api: GatewayAPI? = null

    /** Whether this gateway offers the switches at all. */
    val isOffered: Boolean get() = preferences != null

    /** The one preference there is today. False whenever it is not offered. */
    val resumeAfterLimit: Boolean get() = preferences?.resumeAfterLimit ?: false

    /** Bind the store to the connection's HTTP client. Called on every sign-in, and with null on sign-out, which also forgets the previous account's value. */
    fun attach(api: GatewayAPI?) {
        this.api = api
        if (api == null) preferences = null
        errorMessage = null
    }

    /** The socket's own copy. `hello` seeds it; `preferences.updated` replaces it, which is how turning the switch off in the browser reaches the phone. */
    fun receive(frame: AppFrame) {
        when (frame) {
            is AppFrame.Hello -> preferences = frame.hello.preferences
            is AppFrame.PreferencesUpdated -> preferences = frame.preferences
            else -> Unit
        }
    }

    /**
     * Write the switch. The value is applied before the round trip so the control answers the
     * finger, and put back with the reason if the gateway refuses; a `preferences.updated` on the
     * socket confirms it either way.
     */
    suspend fun setResumeAfterLimit(value: Boolean) {
        val api = api ?: return
        val previous = preferences ?: return
        if (isWriting) return
        errorMessage = null
        // Only this field: the rest of the object is the account's Settings preferences (A41),
        // which this switch neither reads nor disturbs.
        val changes = PreferencePatch(resumeAfterLimit = value)
        preferences = previous.applying(changes)
        isWriting = true
        try {
            preferences = api.patchPreferences(changes).preferences
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            preferences = previous
            errorMessage = GatewayMessage.text(error)
        } finally {
            isWriting = false
        }
    }

    fun clearError() {
        errorMessage = null
    }
}
