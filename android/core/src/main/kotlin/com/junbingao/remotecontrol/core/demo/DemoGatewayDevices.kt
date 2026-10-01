package com.junbingao.remotecontrol.core.demo

import com.junbingao.remotecontrol.core.protocol.AgentsResult
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateResult
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.core.protocol.DirectoryListing
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.TerminalAttachResult
import com.junbingao.remotecontrol.core.protocol.TerminalExited
import com.junbingao.remotecontrol.core.protocol.TerminalLimits
import com.junbingao.remotecontrol.core.protocol.TerminalOpenResult
import com.junbingao.remotecontrol.core.protocol.TerminalOutput
import com.junbingao.remotecontrol.core.protocol.decodeBase64
import com.junbingao.remotecontrol.core.protocol.intValue
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The demo gateway's requests about a machine: what its agents are signed in with (A33), a folder
// made in the picker (A37), an update taken on (A22), and its terminals (A38). Every function here
// runs on the gateway's isolation.

/** Amendment A22: how long a demo device takes to fetch the wheel, install it and restart. Long enough that "Updating…" is a state you can read. */
private val updateDelay = 4.seconds

/** Protocol 7.3: four per machine, and a fifth is a conflict. */
private const val terminalsPerDevice = 4

/**
 * Amendment A33: what the agents on one machine are signed in with, and what is left of each
 * account's quota. The windows are read on request — one call per account — so the answer takes a
 * moment and the device list this gateway publishes never carries them.
 */
internal suspend fun DemoGateway.agents(request: GatewayRequest): JsonElement {
    val id = request.body["device_id"]?.stringValue ?: ""
    val target = device(id)
    if (!target.online) throw GatewayErrorBody(code = GatewayErrorCode.deviceOffline, message = "That device is offline.")
    pause(agentsDelay)
    val agents = DemoFixtures.agentsWithQuota(deviceID = id) ?: target.agents
    return JSONValue.encode(AgentsResult(agents = agents))
}

/**
 * Amendment A37: one folder, inside a directory this demo listed. The device owns the name rules,
 * so the tree answers them and this only insists on the two fields the request is made of.
 */
internal fun DemoGateway.makeDirectory(request: GatewayRequest): DirectoryListing {
    val path = request.body["path"]?.stringValue
    if (path.isNullOrEmpty()) throw GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "path is required")
    return directories.makeDirectory(path = path, named = request.body["name"]?.stringValue ?: "")
}

/**
 * Amendment A22: the device takes the update on, restarts, and comes back on the build it was
 * sent to. The gateway is what publishes each step, so the demo does too and the row follows
 * without a reload.
 */
internal fun DemoGateway.updateDevice(request: GatewayRequest): JsonElement {
    val id = request.body["device_id"]?.stringValue ?: ""
    val target = device(id)
    val build = request.body["build"]?.stringValue
    if (build.isNullOrEmpty()) throw GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "build is required")
    if (!target.online) throw GatewayErrorBody(code = GatewayErrorCode.deviceOffline, message = "That device is offline.")
    if (target.clientBuild == build) {
        throw GatewayErrorBody(code = GatewayErrorCode.conflict, message = "already on this build")
    }
    update(deviceID = id) { it.copy(updateState = DeviceUpdateState.updating, updateMessage = null) }
    updating?.cancel()
    updating = scope.launch {
        pause(updateDelay)
        if (isCancelled()) return@launch
        finishUpdate(deviceID = id, build = build)
    }
    return JSONValue.encode(DeviceUpdateResult(accepted = true, from = target.clientBuild))
}

/** The `hello` a restarted client sends, as the gateway republishes it: the new build, and an update state back at rest. */
private fun DemoGateway.finishUpdate(deviceID: String, build: String) {
    update(deviceID = deviceID) {
        // What it came back on, in the words the row shows: an update that named a version has to
        // leave that version behind it.
        it.copy(clientBuild = build, clientVersion = DemoFixtures.servedClientVersion,
                updateState = DeviceUpdateState.idle, updateMessage = null, lastSeen = DemoFixtures.now)
    }
}

// Terminals (amendment A38)

/** One demo shell, with what the wire needs around it. */
internal class DemoTerminal(val deviceID: String, val shell: DemoShell) {
    var seq = 0
}

internal fun DemoGateway.openTerminal(request: GatewayRequest): JsonElement {
    val id = request.body["device_id"]?.stringValue ?: ""
    val target = device(id)
    if (!target.online) throw GatewayErrorBody(code = GatewayErrorCode.deviceOffline, message = "That device is offline.")
    if (!target.offersTerminal) {
        throw GatewayErrorBody(code = GatewayErrorCode.unsupported, message = "This device offers no terminal.")
    }
    if (terminals.values.count { it.deviceID == id } >= terminalsPerDevice) {
        throw GatewayErrorBody(code = GatewayErrorCode.conflict, message = "This device already runs four terminals.")
    }
    val cols = request.body["cols"]?.intValue ?: 80
    val rows = request.body["rows"]?.intValue ?: 24
    val terminalID = uuidString()
    terminals[terminalID] = DemoTerminal(deviceID = id,
                                         shell = DemoShell(cols = TerminalLimits.cols(cols), rows = TerminalLimits.rows(rows)))
    // The prompt follows the reply: a frame that arrives before the screen knows the id would be
    // dropped as another terminal's.
    greeting?.cancel()
    greeting = scope.launch {
        pause(120.milliseconds)
        if (isCancelled()) return@launch
        greet(terminalID)
    }
    return JSONValue.encode(TerminalOpenResult(terminalID = terminalID))
}

private fun DemoGateway.greet(terminalID: String) {
    val terminal = terminals[terminalID] ?: return
    publish(terminal.shell.start(), from = terminalID)
}

internal fun DemoGateway.writeTerminal(request: GatewayRequest): JsonElement {
    val terminalID = terminalID(of = request)
    val terminal = terminals[terminalID]
        ?: throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "No such terminal")
    val bytes = request.body["data"]?.stringValue?.let(::decodeBase64)
        ?: throw GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "data must be base64")
    if (bytes.size > TerminalLimits.maxInputBytes) {
        throw GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "input is larger than 64 KiB")
    }
    val answer = terminal.shell.feed(bytes)
    publish(answer.output, from = terminalID)
    answer.code?.let { code ->
        terminals.remove(terminalID)
        continuation.trySend(GatewayEvent.Frame(AppFrame.TerminalExited(
            TerminalExited(terminalID = terminalID, deviceID = terminal.deviceID, code = code))))
    }
    return JSONValue.emptyObject
}

internal fun DemoGateway.resizeTerminal(request: GatewayRequest): JsonElement {
    val terminalID = terminalID(of = request)
    val terminal = terminals[terminalID]
        ?: throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "No such terminal")
    terminal.shell.resize(cols = request.body["cols"]?.intValue ?: terminal.shell.cols,
                          rows = request.body["rows"]?.intValue ?: terminal.shell.rows)
    return JSONValue.emptyObject
}

internal fun DemoGateway.attachTerminal(request: GatewayRequest): JsonElement {
    val terminalID = terminalID(of = request)
    val terminal = terminals[terminalID]
        ?: throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "That terminal is gone.")
    return JSONValue.encode(TerminalAttachResult(
        terminalID = terminalID, cols = terminal.shell.cols, rows = terminal.shell.rows,
        scrollback = Base64.getEncoder().encodeToString(terminal.shell.scrollback)))
}

/** Idempotent, exactly as the device is: closing a terminal that is already gone is not a refusal. */
internal fun DemoGateway.closeTerminal(request: GatewayRequest): JsonElement {
    terminals.remove(terminalID(of = request))
    return JSONValue.emptyObject
}

private fun terminalID(of: GatewayRequest): String {
    val id = of.body["terminal_id"]?.stringValue
    if (id.isNullOrEmpty()) throw GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "terminal_id is required")
    return id
}

/** Bytes out, with the rising `seq` an app reads a gap from. */
private fun DemoGateway.publish(bytes: ByteArray, from: String) {
    val terminal = terminals[from]
    if (bytes.isEmpty() || terminal == null) return
    terminal.seq += 1
    continuation.trySend(GatewayEvent.Frame(AppFrame.TerminalOutput(TerminalOutput(
        terminalID = from, deviceID = terminal.deviceID, seq = terminal.seq,
        data = Base64.getEncoder().encodeToString(bytes)))))
}
