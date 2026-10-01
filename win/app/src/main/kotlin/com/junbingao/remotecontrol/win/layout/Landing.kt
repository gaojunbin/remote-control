package com.junbingao.remotecontrol.win.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.junbingao.remotecontrol.win.app.LocalShellState
import com.junbingao.remotecontrol.win.app.Route

/**
 * `web/src/layout/Landing.tsx`: where an open lands. `docs/DESIGN.md` § "The three screens":
 * Sessions when the account has at least one device and Devices when it has none, because a new
 * account's first job is enrolling a machine and everyone else's is the conversation.
 *
 * The choice is made once per open and is not remembered: this place replaces itself with its
 * answer, so someone who then opens Devices on an account with no devices stays there, and a
 * device arriving later moves nobody. The socket's snapshot is what fills the list, so nothing is
 * decided before it has synced, or every account would land on Devices for a round trip.
 */
@Composable
fun Landing() {
    val shell = LocalShellState.current
    BootView()
    LaunchedEffect(shell.hasSnapshot) {
        if (shell.hasSnapshot) shell.router.replace(Landing.destination(hasDevices = shell.hasDevices))
    }
}

object Landing {
    /** The rule itself, with nothing around it. */
    fun destination(hasDevices: Boolean): Route = if (hasDevices) Route.Sessions else Route.Devices
}
