package com.junbingao.remotecontrol.android.screens.devices

import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.state.DeviceUpdate

/**
 * Where a device row can lead (amendment A38, rule 20). Two destinations, not one: the row's tap
 * opens a shell and the row's menu opens the machine's page, so the stack needs to tell them apart.
 * The model's `devicePath` is a list of these.
 */
sealed interface DeviceRoute {
    /** The machine's own page: the agents on it, and what is left of each account's quota (A33). Reached from the menu as **Show quota**. */
    data class Page(val deviceID: String) : DeviceRoute

    /** A shell on the machine (7.3). */
    data class Terminal(val deviceID: String) : DeviceRoute
}

/**
 * What the row's tap does, decided from the device alone. Rule 20: tapping a device that is
 * online and offers a terminal opens one; an offline device or one without the capability says so
 * instead of opening anything.
 */
object DeviceTap {
    sealed interface Outcome {
        data object Terminal : Outcome

        /** Nothing opens, and this is what the row says in place. */
        data class Refused(val reason: String) : Outcome
    }

    fun outcome(device: Device): Outcome {
        if (!device.online) return Outcome.Refused(L10n.string("This device is offline."))
        if (!device.offersTerminal) return Outcome.Refused(L10n.string("This device does not offer a terminal."))
        return Outcome.Terminal
    }
}

/**
 * The row's swipe and its context menu, in one order on both apps (rule 20): **Rename**,
 * **Retry update** (only while an update has failed, A36), **Show quota**, **Revoke**.
 */
enum class DeviceRowAction(val rawValue: String) {
    rename("rename"),
    retryUpdate("retryUpdate"),
    showQuota("showQuota"),
    revoke("revoke");

    val id: String get() = rawValue

    /** The identifier the checks and the UI tests look the action up by. */
    val identifier: String
        get() = when (this) {
            rename -> "device.rename"
            retryUpdate -> "device.retryUpdate"
            showQuota -> "device.showQuota"
            revoke -> "device.revoke"
        }

    /** The SF Symbol's name, which `Sf.named` draws. */
    val symbol: String
        get() = when (this) {
            rename -> "pencil"
            retryUpdate -> "arrow.clockwise"
            showQuota -> "gauge.with.dots.needle.33percent"
            revoke -> "trash"
        }

    companion object {
        /** The actions this machine offers, in the order they are offered. */
        fun menu(device: Device): List<DeviceRowAction> = entries.filter { it != retryUpdate || DeviceUpdate.canRetry(device) }
    }
}
