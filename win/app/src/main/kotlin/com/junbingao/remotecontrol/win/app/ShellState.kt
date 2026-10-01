package com.junbingao.remotecontrol.win.app

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the window's shell — the topbar and the landing rule — reads of the app model, which the
 * Mac's layout reads straight off `MacAppModel`. The model arrives with the core in stage 2 and
 * implements this; until then nothing provides it and the shell's screens are not drawn. Each
 * property is snapshot state in the model, so the shell redraws when it changes.
 */
interface ShellState {
    val router: Router

    /** The gateway's origin, which the topbar prints as a host. */
    val origin: String

    /** The signed-in account's username, which the topbar's avatar draws the initials of. */
    val username: String

    /** The web's `status === 'open'`: the socket is up, whether or not its `hello` has landed yet. */
    val connectionIsOpen: Boolean

    /** The socket's first snapshot has arrived, so the device list is what the account has. */
    val hasSnapshot: Boolean

    /** The account has at least one device. */
    val hasDevices: Boolean
}

val LocalShellState = staticCompositionLocalOf<ShellState> { error("No ShellState: the app model provides it") }
