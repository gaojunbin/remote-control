package com.junbingao.remotecontrol.win.users

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.protocol.UserState
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.core.state.UsersStore
import com.junbingao.remotecontrol.win.shared.AccountErrors
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException

/**
 * `web/src/stores/users.ts` — A24: the accounts on this gateway as the admin sees them, on the
 * core's `UsersStore`. Nothing pushes accounts over the app socket, so the list is whatever the
 * last `GET /api/users` said, and every write re-reads it. It lives as long as the model, as the
 * web's store lives as long as the tab, so a second visit draws the list it had while it re-reads.
 */
class UsersModel {
    var loaded: Boolean by mutableStateOf(false)
        private set

    /** Why the last action that has no dialog of its own failed. */
    var error: String? by mutableStateOf(null)
        private set

    /** The switch's value while its write is out: it answers the click before the gateway does, and goes back if the gateway refuses. */
    private var pendingRegistration: Boolean? by mutableStateOf(null)
    private var store: UsersStore? by mutableStateOf(null)

    val users: List<UserRecord> get() = store?.users ?: emptyList()

    val registrationOpen: Boolean get() = pendingRegistration ?: store?.registrationOpen ?: false

    suspend fun load(on: ConnectionStore) {
        val store = store(on = on) ?: return
        store.load()
        loaded = true
        error = if (store.errorMessage == null) null else S.users.loadFailed
    }

    suspend fun openRegistration(open: Boolean, on: ConnectionStore) {
        val store = store(on = on) ?: return
        pendingRegistration = open
        error = null
        try {
            store.setRegistration(open = open)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = S.users.registrationFailed
        } finally {
            pendingRegistration = null
        }
    }

    suspend fun toggleState(of: UserRecord, on: ConnectionStore) {
        val store = store(on = on) ?: return
        error = null
        try {
            store.setState(if (of.state == UserState.disabled) UserState.active else UserState.disabled, of = of.username)
            load(on = on)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refusal: Exception) {
            error = AccountErrors.userErrorText(refusal, conflict = S.account.notAllowed)
        }
    }

    /**
     * The store the list is read from and the dialogs write through, made on the connection's own
     * credential the first time it is needed. Null for a member, whom the gateway answers `403`.
     */
    fun store(on: ConnectionStore): UsersStore? {
        if (store == null) store = on.usersStore()
        return store
    }

    /** Sign-out: nothing of the previous account stays (`signOut.ts`). */
    fun reset() {
        store = null
        loaded = false
        error = null
        pendingRegistration = null
    }
}
