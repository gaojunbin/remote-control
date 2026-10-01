package com.junbingao.remotecontrol.win.devices

import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.win.app.WinAppModel
import com.junbingao.remotecontrol.win.devices.adddevice.GatewayClock
import java.util.Collections
import java.util.WeakHashMap

/** The lists feature's launch hook: what the device and session lists keep for the life of the app. */
object ListsFeature {
    /**
     * The gateway's clock per model. The model is the foundation's and has no slot for a feature's
     * state, so the one thing the lists read off `hello` is kept here, beside the connection it
     * came from — and goes with it.
     */
    private val clocks: MutableMap<ConnectionStore, GatewayClock> = Collections.synchronizedMap(WeakHashMap())

    fun install(on: WinAppModel) {
        val clock = GatewayClock()
        clocks[on.connection] = clock
        on.connection.addFrameHandler("lists.clock") { frame -> clock.receive(frame) }
        // `useSessions.reset()`: the agent filter is the account's view of its own list, so the
        // next person starts at All agents.
        on.onSignOut { on.sessions.agentFilter = null }
    }

    /** The gateway's clock as the connection's last `hello` set it. */
    fun clock(model: WinAppModel): GatewayClock = clocks[model.connection] ?: GatewayClock()
}
