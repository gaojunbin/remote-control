package com.junbingao.remotecontrol.android.screens.users

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.SwipeAction
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.protocol.UserState
import com.junbingao.remotecontrol.core.state.AccountError
import com.junbingao.remotecontrol.core.state.UsersStore
import kotlinx.coroutines.CancellationException

/**
 * What the accounts screen is in the middle of — the account whose password is being reset, the
 * one being deleted, the last refusal — and the three actions a row offers.
 *
 * Each alert and sheet shows the failure it caused, never the page: a refused reset re-opens its
 * alert with the sentence in the message, and only a refusal with nowhere else to go is the
 * screen's own line. The work is started in the app's scope, so leaving the screen does not cancel
 * a change halfway, as the iPhone's tasks outlive their view.
 */
class UserActions {
    var resetting: UserRecord? by mutableStateOf(null)
    var newPassword: String by mutableStateOf("")
    var resetError: String? by mutableStateOf(null)
        private set
    var deleting: UserRecord? by mutableStateOf(null)
    var actionError: String? by mutableStateOf(null)
        private set

    /**
     * The trailing swipe, edge first: SwiftUI lays it out from the edge inwards, so the row reads
     * Reset · Disable · Delete. The tints are explicit: the app sets its own tint at the root, and a
     * destructive swipe button takes that over the system red without one.
     */
    fun swipe(model: AppModel, store: UsersStore, record: UserRecord, danger: Color, attention: Color, quiet: Color): List<SwipeAction> {
        val state = if (record.isActive) {
            SwipeAction(L10n.string("Disable"), Sf.pauseCircle, attention, tag = "user.disable") { setState(model, store, UserState.disabled, record) }
        } else {
            SwipeAction(L10n.string("Enable"), Sf.playCircle, quiet, tag = "user.enable") { setState(model, store, UserState.active, record) }
        }
        return listOf(
            SwipeAction(L10n.string("Delete"), Sf.trash, danger, tag = "user.delete") { deleting = record },
            state,
            SwipeAction(L10n.string("Reset password"), Sf.key, quiet, tag = "user.reset") { beginReset(record) },
        )
    }

    /** The same three, as the context menu lists them. */
    fun menu(model: AppModel, store: UsersStore, record: UserRecord): List<MenuItem> = listOf(
        MenuItem.Action(L10n.string("Reset password"), Sf.key, tag = "user.reset") { beginReset(record) },
        if (record.isActive) {
            MenuItem.Action(L10n.string("Disable"), Sf.pauseCircle, tag = "user.disable") { setState(model, store, UserState.disabled, record) }
        } else {
            MenuItem.Action(L10n.string("Enable"), Sf.playCircle, tag = "user.enable") { setState(model, store, UserState.active, record) }
        },
        MenuItem.Action(L10n.string("Delete"), Sf.trash, role = ActionRole.destructive, tag = "user.delete") { deleting = record },
    )

    /** The switch at the top. The gateway's answer is what it shows, so a refusal puts it back. */
    fun setRegistration(model: AppModel, store: UsersStore, open: Boolean) {
        perform(model) {
            actionError = null
            store.setRegistration(open = open)
        }
    }

    /**
     * The row is read while the tap is still being handled: dismissing an alert clears the state a
     * task started from it would have read.
     */
    fun resetPassword(model: AppModel, store: UsersStore) {
        val record = resetting ?: return
        val password = newPassword
        newPassword = ""
        model.perform {
            try {
                actionError = null
                store.resetPassword(of = record.username, to = password)
                resetError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                resetError = L10n.platform(AccountError.manage(failure))
                resetting = record
            }
        }
    }

    fun delete(model: AppModel, store: UsersStore) {
        val record = deleting ?: return
        perform(model) {
            actionError = null
            store.delete(record.username)
        }
    }

    private fun beginReset(record: UserRecord) {
        resetting = record
        newPassword = ""
        resetError = null
    }

    private fun setState(model: AppModel, store: UsersStore, state: UserState, record: UserRecord) {
        perform(model) {
            actionError = null
            store.setState(state, of = record.username)
        }
    }

    /** A change that fails says so on the screen, in the words the gateway's refusal maps to. */
    private fun perform(model: AppModel, work: suspend () -> Unit) {
        model.perform {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                actionError = L10n.platform(AccountError.manage(failure))
            }
        }
    }
}
