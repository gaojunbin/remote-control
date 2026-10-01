package com.junbingao.remotecontrol.win.app

import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateResult
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.state.PairingFlow
import com.junbingao.remotecontrol.core.state.request
import com.junbingao.remotecontrol.core.transport.GatewayHTTPClient
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException

// The Mac's `MacAppModel+Devices.swift`: the devices and agents a screen reads, and what is asked of them.

fun WinAppModel.device(id: String): Device? = connection.device(id)

fun WinAppModel.device(session: Session): Device? = connection.device(session.deviceID)

/**
 * The signed-in gateway's HTTP client, for the sockets the core opens on it directly
 * (`WS /ws/stt`). Null when nobody is signed in, and under the offline demo, which has no HTTP
 * client at all.
 */
val WinAppModel.httpClient: GatewayHTTPClient? get() = ConnectionFactory.httpClient(behind = connection.api)

/** The agent a session runs, as its device describes it. */
fun WinAppModel.agent(session: Session): AgentInfo? = connection.device(session.deviceID)?.agent(session.agent)

/**
 * Amendment A22: ask a device to fetch the build the gateway serves. The accepted case says
 * nothing here — `device.updated` carries the state the row draws from then on; a refusal is the
 * device's own words.
 */
suspend fun WinAppModel.updateDevice(device: Device) {
    val channel = connection.channel ?: return
    val build = connection.config.servedBuild ?: return
    deviceUpdateErrors = deviceUpdateErrors - device.deviceID
    try {
        channel.request(GatewayRequest.updateDevice(deviceID = device.deviceID, build = build), DeviceUpdateResult.serializer())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refusal: GatewayErrorBody) {
        deviceUpdateErrors = deviceUpdateErrors + (device.deviceID to refusal.message)
    } catch (_: Exception) {
        deviceUpdateErrors = deviceUpdateErrors + (device.deviceID to S.errors.generic)
    }
}

/** The Add device flow on this connection's own credential. */
fun WinAppModel.pairingFlow(): PairingFlow? = connection.api?.let { PairingFlow(api = it) }
