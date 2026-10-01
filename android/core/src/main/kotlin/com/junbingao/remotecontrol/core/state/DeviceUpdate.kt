package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState

/**
 * Amendment A36: what an app says about a device's client, and when a person may ask for an update
 * at all.
 *
 * The gateway brings every device to the wheel it serves without being asked, so the client
 * version is nobody's to watch: an app states neither it nor its build, and offers nothing while
 * nothing is wrong. What is left is an update in flight and one that failed. Both apps decide this
 * the same way, and the rule is here rather than in a view so it can be tested without one.
 */
object DeviceUpdate {
    /** The line a device draws about its client, when there is one. Null means it says nothing at all. */
    sealed interface Notice {
        data object Updating : Notice
        data class Failed(val message: String) : Notice
    }

    /** Why Retry update cannot be asked for. Null means it can. */
    enum class Block { offline, noServedBuild }

    /**
     * `localError` is a refusal the device replied with, which lives in the app rather than on the
     * record: the gateway never saw an update start.
     */
    fun notice(device: Device, localError: String? = null): Notice? {
        if (device.updateState == DeviceUpdateState.updating) return Notice.Updating
        if (localError != null) return Notice.Failed(localError)
        val message = device.updateMessage
        if (device.updateState == DeviceUpdateState.failed && message != null) return Notice.Failed(message)
        return null
    }

    /** A person steps in only where the gateway gave up: a failed update is the one state Retry is offered in. */
    fun canRetry(device: Device): Boolean = device.updateState == DeviceUpdateState.failed

    fun block(device: Device, servedBuild: String?): Block? {
        if (!device.online) return Block.offline
        return if (servedBuild == null) Block.noServedBuild else null
    }
}
