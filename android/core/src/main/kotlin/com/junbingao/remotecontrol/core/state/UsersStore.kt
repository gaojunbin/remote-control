package com.junbingao.remotecontrol.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.protocol.UserState
import kotlinx.coroutines.CancellationException

/**
 * The admin's accounts screen, as state (protocol 3.9, A24).
 *
 * Every mutation goes to the gateway first and applies what came back, so a row never shows a state
 * the gateway did not agree to. A failure is thrown rather than stored, because the sentence belongs
 * in whichever sheet or alert caused it and not at the top of the page.
 */
class UsersStore(private val api: GatewayAPI) {
    var users: List<UserRecord> by mutableStateOf(emptyList())
        private set
    var registrationOpen: Boolean by mutableStateOf(false)
        private set
    var isLoading: Boolean by mutableStateOf(false)
        private set

    /** The one error the page itself owns: the list that would not load. */
    var errorMessage: String? by mutableStateOf(null)
        private set

    val hasLoaded: Boolean get() = users.isNotEmpty()

    suspend fun load() {
        isLoading = true
        try {
            val response = api.users()
            if (isCancelled()) return
            users = response.users
            registrationOpen = response.registrationOpen
            errorMessage = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = AccountError.manage(error)
        } finally {
            isLoading = false
        }
    }

    /** The switch at the top. The gateway's answer is what the switch shows, so a refusal puts it back where it was. */
    suspend fun setRegistration(open: Boolean) {
        registrationOpen = api.setRegistration(open = open)
    }

    suspend fun create(username: String, password: String, role: UserRole) {
        apply(api.createUser(username = username, password = password, role = role))
    }

    suspend fun resetPassword(of: String, to: String) {
        apply(api.patchUser(of, state = null, role = null, password = to))
    }

    suspend fun setState(state: UserState, of: String) {
        apply(api.patchUser(of, state = state, role = null, password = null))
    }

    suspend fun delete(username: String) {
        api.deleteUser(username)
        users = users.filter { it.username != username }
    }

    /** Oldest first, the order `GET /api/users` promises, kept when a row is replaced and when one is added. */
    private fun apply(record: UserRecord) {
        val index = users.indexOfFirst { it.username == record.username }
        users = if (index >= 0) users.toMutableList().also { it[index] = record } else users + record
    }
}
