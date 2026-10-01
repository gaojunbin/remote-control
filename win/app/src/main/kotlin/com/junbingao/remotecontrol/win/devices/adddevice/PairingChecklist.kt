package com.junbingao.remotecontrol.win.devices.adddevice

import com.junbingao.remotecontrol.core.protocol.PairingProgress
import com.junbingao.remotecontrol.core.protocol.PairingStep
import kotlin.math.min

/**
 * The rule `PairingProgress.tsx` lights the checklist by: three steps, the first done from the
 * start, each of the others active while the handshake is on its way to it, and a hairline of
 * progress over them.
 */
object PairingChecklist {
    enum class Mark { idle, active, done }

    /** No frame for this code yet ranks below the first step. */
    fun rank(step: PairingStep?): Int = step?.order ?: -1

    /** The progress line's filled share, in per cent. */
    fun progress(step: PairingStep?): Double {
        val rank = rank(step)
        return if (rank < 0) 12.0 else min(100.0, 25.0 * (rank + 1))
    }

    /** Gateway ready, Device handshake, Detect installed agents. */
    fun marks(step: PairingStep?): List<Mark> {
        val rank = rank(step)
        val enrolled = PairingStep.enrolled.order
        val online = PairingStep.online.order
        return listOf(
            Mark.done,
            if (rank >= enrolled) Mark.done else Mark.active,
            if (rank >= PairingStep.agents.order) Mark.done else if (rank == online) Mark.active else Mark.idle,
        )
    }

    /** The agents the new device found, by id, for the last step's line. */
    fun agents(live: PairingProgress?): String =
        live?.device?.agents.orEmpty().filter { it.available }.joinToString(" · ") { it.agent }
}
