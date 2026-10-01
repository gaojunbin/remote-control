package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.PreferencePatch
import com.junbingao.remotecontrol.core.protocol.PreferencesResponse
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.protocol.UserState
import com.junbingao.remotecontrol.core.transport.APNSRegistration
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import com.junbingao.remotecontrol.core.transport.HealthResponse
import com.junbingao.remotecontrol.core.transport.LoginResponse
import com.junbingao.remotecontrol.core.transport.PairingClaim
import com.junbingao.remotecontrol.core.transport.PairingGrant
import com.junbingao.remotecontrol.core.transport.PolishModelsResponse
import com.junbingao.remotecontrol.core.transport.PolishRequest
import com.junbingao.remotecontrol.core.transport.PolishResponse
import com.junbingao.remotecontrol.core.transport.SessionInfoResponse
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.core.transport.UserListResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.nio.file.Files

// The doubles RCCore's suites each spell out in full, written once: a gateway and a channel that
// answer nothing, which a suite's own double extends with the routes it is about.

/** A gateway that refuses every route as not connected. A call nobody overrode is a bug in the test that made it. */
open class StubGateway(override val endpoint: GatewayEndpoint = GatewayEndpoint("https://gateway.example.invalid")) :
    GatewayAPI {
    override suspend fun health(): HealthResponse = throw TransportError.NotConnected
    override suspend fun login(username: String, password: String): LoginResponse = throw TransportError.NotConnected
    override suspend fun register(username: String, password: String): LoginResponse = throw TransportError.NotConnected
    override suspend fun changePassword(current: String, new: String): Unit = throw TransportError.NotConnected
    override suspend fun session(): SessionInfoResponse = throw TransportError.NotConnected
    override suspend fun logout() {}
    override suspend fun config(): GatewayConfig = throw TransportError.NotConnected
    override suspend fun preferences(): PreferencesResponse = throw TransportError.NotConnected
    override suspend fun patchPreferences(changes: PreferencePatch): PreferencesResponse = throw TransportError.NotConnected
    override suspend fun polishModels(): PolishModelsResponse = throw TransportError.NotConnected
    override suspend fun polish(request: PolishRequest): PolishResponse = throw TransportError.NotConnected
    override suspend fun devices(): List<Device> = throw TransportError.NotConnected
    override suspend fun renameDevice(deviceID: String, name: String): Device = throw TransportError.NotConnected
    override suspend fun revokeDevice(deviceID: String): Unit = throw TransportError.NotConnected
    override suspend fun beginPairing(): PairingGrant = throw TransportError.NotConnected
    override suspend fun cancelPairing(code: String): Unit = throw TransportError.NotConnected
    override suspend fun claimPairingRequest(token: String): PairingClaim = throw TransportError.NotConnected
    override suspend fun sessions(deviceID: String?, archived: Boolean?): List<Session> = throw TransportError.NotConnected
    override suspend fun users(): UserListResponse = throw TransportError.NotConnected
    override suspend fun createUser(username: String, password: String, role: UserRole): UserRecord =
        throw TransportError.NotConnected
    override suspend fun patchUser(username: String, state: UserState?, role: UserRole?, password: String?): UserRecord =
        throw TransportError.NotConnected
    override suspend fun deleteUser(username: String): Unit = throw TransportError.NotConnected
    override suspend fun setRegistration(open: Boolean): Boolean = throw TransportError.NotConnected
    override suspend fun registerPush(registration: APNSRegistration): Unit = throw TransportError.NotConnected
    override suspend fun unregisterPush(token: String): Unit = throw TransportError.NotConnected
    override suspend fun restoreToken(username: String): Boolean = false
    override suspend fun bearerToken(): String? = null
    override suspend fun forgetToken(username: String) {}
}

/** A channel that never connects and never speaks: for suites about what HTTP routes or frames do to a store, not about the socket. */
open class InertChannel : GatewayChannel {
    override val events: Flow<GatewayEvent> = emptyFlow()
    override suspend fun connect() {}
    override suspend fun disconnect() {}
    override suspend fun request(request: GatewayRequest): JsonElement = throw TransportError.NotConnected
}

/** A fresh directory for one test's cache or drafts, which the test deletes when it is done. */
fun scratchDirectory(prefix: String): File = Files.createTempDirectory("rc-$prefix-").toFile()
