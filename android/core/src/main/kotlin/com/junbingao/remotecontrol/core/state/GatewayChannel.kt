package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.PreferencePatch
import com.junbingao.remotecontrol.core.protocol.PreferencesResponse
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.protocol.UserState
import com.junbingao.remotecontrol.core.protocol.decode
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
import com.junbingao.remotecontrol.core.transport.UserListResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement

/**
 * The socket surface the stores depend on. `GatewaySocket` is the real one; the in-memory demo
 * gateway is the other.
 */
interface GatewayChannel {
    val events: Flow<GatewayEvent>
    suspend fun connect()
    suspend fun disconnect()
    suspend fun request(request: GatewayRequest): JsonElement
}

/** Typed convenience over `request`: `request(.subscribe(…), as: SubscribeResult.self)` in RCCore. */
suspend fun <T> GatewayChannel.request(request: GatewayRequest, type: DeserializationStrategy<T>): T =
    request(request).decode(type)

/** The HTTP surface the stores depend on. */
interface GatewayAPI {
    val endpoint: GatewayEndpoint
    suspend fun health(): HealthResponse
    suspend fun login(username: String, password: String): LoginResponse
    suspend fun register(username: String, password: String): LoginResponse
    suspend fun changePassword(current: String, new: String)
    suspend fun session(): SessionInfoResponse
    suspend fun logout()
    suspend fun config(): GatewayConfig

    /** Amendment A35: the account's preferences, read and written through the gateway so the phone, the browser and every device agree. */
    suspend fun preferences(): PreferencesResponse

    /** Amendment A41: the fields the write names, and no others. */
    suspend fun patchPreferences(changes: PreferencePatch): PreferencesResponse

    /** Amendment A29: dictation polish, which the app offers only where the gateway reports `polish.enabled`. */
    suspend fun polishModels(): PolishModelsResponse
    suspend fun polish(request: PolishRequest): PolishResponse
    suspend fun devices(): List<Device>
    suspend fun renameDevice(deviceID: String, name: String): Device
    suspend fun revokeDevice(deviceID: String)
    suspend fun beginPairing(): PairingGrant
    suspend fun cancelPairing(code: String)
    suspend fun claimPairingRequest(token: String): PairingClaim
    suspend fun sessions(deviceID: String? = null, archived: Boolean? = null): List<Session>
    suspend fun users(): UserListResponse
    suspend fun createUser(username: String, password: String, role: UserRole): UserRecord
    suspend fun patchUser(username: String, state: UserState? = null, role: UserRole? = null,
                          password: String? = null): UserRecord
    suspend fun deleteUser(username: String)
    suspend fun setRegistration(open: Boolean): Boolean
    suspend fun registerPush(registration: APNSRegistration)
    suspend fun unregisterPush(token: String)
    suspend fun restoreToken(username: String): Boolean
    suspend fun bearerToken(): String?
    suspend fun forgetToken(username: String)
}
