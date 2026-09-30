package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.persistence.SecretStore
import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.PreferencePatch
import com.junbingao.remotecontrol.core.protocol.PreferencesResponse
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.UserRecord
import com.junbingao.remotecontrol.core.protocol.UserRole
import com.junbingao.remotecontrol.core.protocol.UserState
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.jsonObjectOf
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.state.GatewayAPI
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Anything that can perform one HTTP request. Tests and the demo gateway substitute their own implementation instead of reaching the network. */
interface HTTPTransport {
    /** The response's body, read in full, and the response it came with. */
    suspend fun perform(request: Request): Pair<ByteArray, Response>
}

/** An OkHttp transport that refuses redirects, so a bearer token can never be replayed to a host the user did not type. */
class OkHttpHTTPTransport(private val client: OkHttpClient = httpClient) : HTTPTransport {
    override suspend fun perform(request: Request): Pair<ByteArray, Response> = suspendCancellableCoroutine { waiter ->
        val call = client.newCall(request)
        waiter.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = waiter.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { readBody(it) }
                } catch (error: Exception) {
                    waiter.resumeWithException(error)
                    return
                }
                waiter.resume(body to response)
            }
        })
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        /** No cookie jar, no cache and no redirects: the bearer header is the only credential in play. */
        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** The whole body, or [TransportError.ResponseTooLarge] once it passes 8 MiB. */
        fun readBody(response: Response): ByteArray {
            val source = response.body.source()
            val buffer = Buffer()
            while (source.read(buffer, 64 * 1024) != -1L) {
                if (buffer.size > MAX_RESPONSE_BYTES) throw TransportError.ResponseTooLarge
            }
            return buffer.readByteArray()
        }
    }
}

/**
 * Bearer-token HTTP against one gateway origin.
 *
 * The token lives in the platform's secret store, keyed by origin plus username, and is attached
 * to every authenticated call. No cookie storage is involved: this is a native app, so the
 * browser's cookie path in the protocol does not apply.
 */
class GatewayHTTPClient(
    override val endpoint: GatewayEndpoint,
    private val transport: HTTPTransport = OkHttpHTTPTransport(),
    private val secrets: SecretStore,
) : GatewayAPI {
    /** The actor's one piece of state, so one volatile reference guards it. */
    @Volatile
    private var token: String? = null

    // Token lifecycle

    private fun secretKey(username: String): String = "token:${endpoint.origin}:$username"

    override suspend fun restoreToken(username: String): Boolean {
        val data = attempt { secrets.read(secretKey(username)) } ?: return false
        val value = attempt { data.decodeToString(throwOnInvalidSequence = true) }
        if (value.isNullOrEmpty()) return false
        token = value
        return true
    }

    fun adoptToken(value: String) {
        token = value
    }

    val hasToken: Boolean get() = token != null

    override suspend fun bearerToken(): String? = token

    private suspend fun storeToken(value: String, username: String) {
        token = value
        attempt { secrets.write(value.encodeToByteArray(), secretKey(username)) }
    }

    override suspend fun forgetToken(username: String) {
        token = null
        attempt { secrets.remove(secretKey(username)) }
    }

    // Routes

    override suspend fun health(): HealthResponse =
        send(Method.get, "/api/health", authenticated = false).decode()

    override suspend fun login(username: String, password: String): LoginResponse =
        signIn("/api/login", username, password)

    /** `POST /api/register` (A24). It answers exactly what login answers and is a sign-in, so the token is adopted the same way. */
    override suspend fun register(username: String, password: String): LoginResponse =
        signIn("/api/register", username, password)

    private suspend fun signIn(path: String, username: String, password: String): LoginResponse {
        val body = jsonObjectOf("username" to username, "password" to password)
        val response = send(Method.post, path, body = body, authenticated = false).decode<LoginResponse>()
        storeToken(response.token, response.user.username)
        return response
    }

    /** `POST /api/password` (A24): the caller's own password, never anyone else's. Other sign-ins of the account stay valid. */
    override suspend fun changePassword(current: String, new: String) {
        send(Method.post, "/api/password", body = jsonObjectOf("current_password" to current, "new_password" to new))
    }

    override suspend fun session(): SessionInfoResponse = send(Method.get, "/api/session").decode()

    override suspend fun logout() {
        attempt { send(Method.post, "/api/logout") }
        token = null
    }

    override suspend fun config(): GatewayConfig = send(Method.get, "/api/config").decode()

    /** Amendment A35: the caller's own account preferences, which the gateway keeps so every app and device of the account reads the same value. */
    override suspend fun preferences(): PreferencesResponse = send(Method.get, "/api/preferences").decode()

    /**
     * Amendments A35 and A41: set the fields that are present and leave the rest. The change goes
     * out to the account's other apps and to its devices, and the answer is the whole object.
     */
    override suspend fun patchPreferences(changes: PreferencePatch): PreferencesResponse =
        send(Method.patch, "/api/preferences", body = JSONValue.encode(changes)).decode()

    /** Amendment A29: the models the gateway's polish provider offers. `503` with code `unsupported` when the operator configured none. */
    override suspend fun polishModels(): PolishModelsResponse = send(Method.get, "/api/polish/models").decode()

    /**
     * Amendment A29: one dictation through that model. The gateway stores nothing and forwards
     * nothing to a device; the answer is a draft, and sending it stays the user's own separate
     * action.
     */
    override suspend fun polish(request: PolishRequest): PolishResponse =
        send(Method.post, "/api/polish", body = JSONValue.encode(request)).decode()

    override suspend fun devices(): List<Device> = send(Method.get, "/api/devices").decode<DeviceListResponse>().devices

    override suspend fun renameDevice(deviceID: String, name: String): Device =
        send(Method.patch, "/api/devices/${escape(deviceID)}", body = jsonObjectOf("name" to name))
            .decode<DeviceResponse>().device

    override suspend fun revokeDevice(deviceID: String) {
        send(Method.delete, "/api/devices/${escape(deviceID)}")
    }

    override suspend fun beginPairing(): PairingGrant =
        send(Method.post, "/api/devices/pairing", body = JSONValue.emptyObject).decode()

    override suspend fun cancelPairing(code: String) {
        send(Method.delete, "/api/devices/pairing/${escape(code)}")
    }

    /**
     * Amendment A23: bind a host's claim token to this account and take the pairing code the
     * gateway mints for it. The host's own long poll is waiting on the same code.
     */
    override suspend fun claimPairingRequest(token: String): PairingClaim =
        send(Method.post, "/api/pairing/requests/${escape(token)}/claim", body = JSONValue.emptyObject).decode()

    override suspend fun sessions(deviceID: String?, archived: Boolean?): List<Session> {
        val query = buildList {
            deviceID?.let { add("device_id" to it) }
            archived?.let { add("archived" to if (it) "true" else "false") }
        }
        return send(Method.get, "/api/sessions", query = query).decode<SessionListResponse>().sessions
    }

    // Accounts (protocol 3.9, admin only)

    override suspend fun users(): UserListResponse = send(Method.get, "/api/users").decode()

    override suspend fun createUser(username: String, password: String, role: UserRole): UserRecord {
        val body = jsonObjectOf("username" to username, "password" to password, "role" to role.rawValue)
        return send(Method.post, "/api/users", body = body).decode<UserResponse>().user
    }

    override suspend fun patchUser(username: String, state: UserState?, role: UserRole?, password: String?): UserRecord {
        val body = LinkedHashMap<String, JsonElement>()
        state?.let { body["state"] = JsonPrimitive(it.rawValue) }
        role?.let { body["role"] = JsonPrimitive(it.rawValue) }
        password?.let { body["password"] = JsonPrimitive(it) }
        return send(Method.patch, "/api/users/${escape(username)}", body = JsonObject(body)).decode<UserResponse>().user
    }

    override suspend fun deleteUser(username: String) {
        send(Method.delete, "/api/users/${escape(username)}")
    }

    override suspend fun setRegistration(open: Boolean): Boolean =
        send(Method.patch, "/api/registration", body = jsonObjectOf("open" to open)).decode<RegistrationResponse>().open

    override suspend fun registerPush(registration: APNSRegistration) {
        send(Method.post, "/api/push/apns/register", body = JSONValue.encode(registration))
    }

    override suspend fun unregisterPush(token: String) {
        send(Method.delete, "/api/push/apns/register", body = jsonObjectOf("token" to token))
    }

    // Plumbing

    private enum class Method(val rawValue: String) { get("GET"), post("POST"), patch("PATCH"), delete("DELETE") }

    /** Everything but ASCII letters, digits and `-._~` percent-encoded, so an id is one path segment. */
    private fun escape(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val character = code.toChar()
            if (code < 0x80 && (character.isLetterOrDigit() || character in "-._~")) {
                append(character)
            } else {
                append('%').append(code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    private suspend fun send(method: Method, path: String, query: List<Pair<String, String>> = emptyList(),
                             body: JsonElement? = null, authenticated: Boolean = true): JsonElement {
        val base = endpoint.apiURL(path).toString().toHttpUrlOrNull() ?: throw TransportError.InvalidEndpoint
        val url = if (query.isEmpty()) base else base.newBuilder().apply {
            for ((name, value) in query) addQueryParameter(name, value)
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
        if (authenticated) {
            val token = token ?: throw TransportError.Unauthorized
            request.header("Authorization", "Bearer $token")
        }
        request.method(method.rawValue, requestBody(method, body))
        val (data, response) = transport.perform(request.build())
        if (response.code == 401) throw TransportError.Unauthorized
        val json = if (data.isEmpty()) JSONValue.emptyObject else attempt { JSONValue.parse(data) } ?: JSONValue.emptyObject
        if (response.code !in 200..299) throw TransportError.Http(status = response.code, code = json["error"]?.get("code")?.stringValue)
        return json
    }

    /**
     * The JSON body, typed as JSON. OkHttp needs a body on every POST and PATCH, so one sent
     * without is an empty one with no type, which is what `URLSession` sends.
     */
    private fun requestBody(method: Method, body: JsonElement?): RequestBody? = when {
        body != null -> body.toString().encodeToByteArray().toRequestBody(jsonType)
        method == Method.post || method == Method.patch -> ByteArray(0).toRequestBody(null)
        else -> null
    }

    private companion object {
        val jsonType = "application/json".toMediaType()
    }
}
