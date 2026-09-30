package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.attempt
import com.junbingao.remotecontrol.core.protocol.AppFrame
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.ProtocolFailure
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.state.GatewayChannel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import java.util.UUID
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The app's connection to `/ws/app`.
 *
 * Reconnecting sends only `hello` handling and heartbeats. A request is never replayed
 * automatically: the user decides whether to retry, and the retry reuses the original request id
 * so the device can detect the duplicate.
 *
 * RCCore's actor, kept as one: every field below is read and written on [isolation], a dispatcher
 * that runs one piece of this socket's work at a time and lets another in wherever that work
 * suspends, as an actor does.
 */
class GatewaySocket(
    private val client: GatewayHTTPClient,
    private val factory: WebSocketFactory = OkHttpWebSocketFactory(),
) : GatewayChannel {
    private val isolation: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + isolation)
    private val buffer = EventBuffer.makeStream<GatewayEvent>(EventBuffer.appCapacity)
    override val events: Flow<GatewayEvent> = buffer.stream

    private val continuation = buffer.continuation
    private var connection: WebSocketConnection? = null
    private var worker: Job? = null
    private var watchdog: Job? = null
    private val pending = mutableMapOf<String, CancellableContinuation<JsonElement>>()
    private var writes: Deferred<Unit>? = null
    private var epoch = 0
    private var active = false

    @Volatile
    private var connected = false

    /** The hello has landed on the current connection, so the gateway is ready to be asked for something. */
    private var ready = false

    /** Requests issued while the socket is still coming back, each waiting for the hello under its own deadline. */
    private val waiting = mutableMapOf<UUID, CancellableContinuation<Unit>>()
    private var lastReceived = TimeSource.Monotonic.markNow()

    /** The frame kinds already reported as undecodable on this socket. */
    private val undecodable = mutableSetOf<String>()

    companion object {
        private val log: Logger = Logger.getLogger("com.junbingao.remotecontrol.gateway-socket")

        /** A frame at least this often, or the connection is half-open. Mobile NAT silently drops sockets without ever delivering a close. */
        private val silenceLimit = 60.seconds
        private val forwardedTimeout = 65.seconds
        private val localTimeout = 20.seconds

        /**
         * How long a request waits for a socket that is coming back before it is reported
         * unconfirmed. The gateway closes a silent socket after 25 s, so an app returning to the
         * foreground almost always finds one to rebuild; a TLS handshake, a hello and a subscribe
         * fit inside this with room.
         */
        val readyWait: Duration = 20.seconds

        private fun timeout(type: String): Duration =
            // The gateway itself gives a device 60 s for these two.
            if (type == "session.create" || type == "session.history") forwardedTimeout else localTimeout

        /**
         * A frame's `type`, and for a session event its `kind`. They are the only two values from
         * a frame that could not be read that say what went wrong without recording anything the
         * frame was carrying.
         */
        private fun kind(data: ByteArray): String {
            val json = attempt { JSONValue.parse(data) } ?: return "unparseable"
            val type = json["type"]?.stringValue ?: "untyped"
            val kind = json["event"]?.get("kind")?.stringValue ?: return type
            return "$type/$kind"
        }
    }

    override suspend fun connect() = withContext(isolation) {
        if (active) return@withContext
        active = true
        epoch += 1
        val generation = epoch
        worker = scope.launch { runLoop(generation) }
    }

    /** Always runs to the end, whoever asks and whatever becomes of them meanwhile: a socket left half torn down would reconnect. */
    override suspend fun disconnect(): Unit = withContext(NonCancellable) {
        val old = withContext(isolation) {
            active = false
            connected = false
            ready = false
            releaseWaiting()
            epoch += 1
            worker?.cancel()
            worker = null
            watchdog?.cancel()
            watchdog = null
            val old = connection
            connection = null
            writes?.cancel()
            writes = null
            failPending(TransportError.DeliveryUncertain)
            emit(GatewayEvent.State(ConnectionState.disconnected))
            old
        }
        old?.cancel()
    }

    val isConnected: Boolean get() = connected

    /**
     * Send a request and wait for its `reply`. Throws `DeliveryUncertain` when the socket dropped
     * before an answer arrived.
     *
     * The continuation is registered before the write is queued, and writes go through one chain,
     * so a reply can never arrive before the app is ready for it and two sends cannot reach the
     * gateway out of order.
     */
    override suspend fun request(request: GatewayRequest): JsonElement = withContext(isolation) {
        val text = request.encoded().decodeToString()
        val connection = readyConnection()
        if (!request.expectsReply) {
            enqueue(text, connection).written()
            return@withContext JSONValue.emptyObject
        }
        val generation = epoch
        val deadline = scope.launch {
            delay(timeout(request.type))
            expire(request.id, generation)
        }
        try {
            suspendCancellableCoroutine<JsonElement> { waiter ->
                // Amendment A12 makes a retry reuse its request id, so two of them can overlap — a
                // double tap, or two taps while the socket is coming back and both are held. The
                // first caller is answered as unconfirmed before the second takes the slot;
                // leaking it would suspend its coroutine for the life of the process and leave the
                // send it belongs to on the screen forever.
                pending.remove(request.id)?.let { stranded ->
                    stranded.resumeWithException(TransportError.DeliveryUncertain)
                    emit(GatewayEvent.RequestUncertain(request.id))
                }
                pending[request.id] = waiter
                val write = enqueue(text, connection)
                scope.launch {
                    try {
                        write.await()
                    } catch (_: Throwable) {
                        failRequest(request.id, TransportError.DeliveryUncertain)
                    }
                }
                waiter.invokeOnCancellation { scope.launch { discard(request.id, waiter) } }
            }
        } finally {
            deadline.cancel()
        }
    }

    /**
     * The connection to write on, waiting for one if the socket is on its way back up.
     *
     * A request issued the moment the app returns to the foreground would otherwise fail on a
     * socket the gateway had already closed for silence, which the user reads as a dead Send
     * button. The request is held instead and flushed once the hello lands; past [readyWait] it is
     * reported unconfirmed, and the outbox retry under the same id applies.
     */
    private suspend fun readyConnection(): WebSocketConnection {
        connection?.let { if (ready) return it }
        if (!active) throw TransportError.NotConnected
        waitForReady()
        val connection = connection
        if (!active || !ready || connection == null) throw TransportError.DeliveryUncertain
        return connection
    }

    private suspend fun waitForReady() {
        val id = UUID.randomUUID()
        val deadline = scope.launch {
            delay(readyWait)
            stopWaiting(id)
        }
        try {
            suspendCancellableCoroutine { waiter ->
                // Checked here rather than before the call, so a hello that landed in between
                // cannot leave this request waiting for nothing.
                if (ready || !active) waiter.resume(Unit) else waiting[id] = waiter
                waiter.invokeOnCancellation { scope.launch { waiting.remove(id) } }
            }
        } finally {
            deadline.cancel()
        }
    }

    private fun stopWaiting(id: UUID) {
        waiting.remove(id)?.resume(Unit)
    }

    private fun releaseWaiting() {
        val held = waiting.values.toList()
        waiting.clear()
        for (waiter in held) waiter.resume(Unit)
    }

    /** Append a frame to the write chain. Each write awaits the previous one, so call order is the order the gateway sees. */
    private fun enqueue(text: String, connection: WebSocketConnection): Deferred<Unit> {
        val previous = writes
        val write = scope.async {
            previous?.join()
            connection.send(text)
        }
        writes = write
        return write
    }

    /** A write that was cancelled with its socket is a write nobody can vouch for. */
    private suspend fun Deferred<Unit>.written() {
        try {
            await()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw TransportError.DeliveryUncertain
        }
    }

    private fun expire(id: String, generation: Int) {
        if (epoch != generation) return
        val waiter = pending.remove(id) ?: return
        waiter.resumeWithException(TransportError.RequestTimedOut)
        emit(GatewayEvent.RequestUncertain(id))
    }

    private fun failRequest(id: String, error: Throwable) {
        val waiter = pending.remove(id) ?: return
        waiter.resumeWithException(error)
        emit(GatewayEvent.RequestUncertain(id))
    }

    /** A caller that stopped waiting leaves its slot behind for nobody. */
    private fun discard(id: String, waiter: CancellableContinuation<JsonElement>) {
        if (pending[id] === waiter) pending.remove(id)
    }

    private fun failPending(error: Throwable) {
        for (id in pending.keys.toList()) {
            pending.remove(id)?.resumeWithException(error)
            emit(GatewayEvent.RequestUncertain(id))
        }
    }

    private suspend fun runLoop(generation: Int) {
        var attempt = 0
        var closeReason = SocketCloseReason.transient
        try {
            while (active && epoch == generation && currentCoroutineContext().isActive) {
                emit(GatewayEvent.State(if (attempt == 0) ConnectionState.connecting else ConnectionState.reconnecting))
                try {
                    val token = client.bearerToken() ?: throw TransportError.Unauthorized
                    val request = Request.Builder()
                        .url(client.endpoint.socketURL(path = "/ws/app").toString())
                        .header("Authorization", "Bearer $token")
                        .build()
                    val socket = factory.makeConnection(request)
                    if (!active || epoch != generation) {
                        socket.cancel()
                        break
                    }
                    connection = socket
                    socket.resume()
                    lastReceived = TimeSource.Monotonic.markNow()
                    connected = true
                    closeReason = SocketCloseReason.transient
                    startWatchdog(generation, socket)
                    while (active && epoch == generation && currentCoroutineContext().isActive) {
                        val data = socket.receive()
                        if (!active || epoch != generation) break
                        // A connection only counts as healthy once it has delivered a frame;
                        // otherwise a gateway that accepts and immediately closes would loop at the
                        // shortest backoff forever.
                        attempt = 0
                        lastReceived = TimeSource.Monotonic.markNow()
                        decode(data)?.let { handle(it, socket) }
                        // A connection that always has the next frame ready never suspends, and
                        // this socket's other work — a request waiting to be written — would never
                        // run between two frames, as it does between two awaits of an actor.
                        yield()
                    }
                } catch (error: Throwable) {
                    if (!active || epoch != generation || !currentCoroutineContext().isActive) break
                    closeReason = SocketCloseReason(code = connection?.closeCode())
                    when {
                        error is ProtocolFailure.UnsupportedVersion -> {
                            emit(GatewayEvent.Failure(TransportError.ProtocolMismatch(error.version)))
                            active = false
                            releaseWaiting()
                        }
                        error is TransportError.Unauthorized -> closeReason = SocketCloseReason.unauthorized
                        error is TransportError && closeReason == SocketCloseReason.transient ->
                            emit(GatewayEvent.Failure(error))
                    }
                }
                if (epoch != generation) break
                connected = false
                ready = false
                watchdog?.cancel()
                watchdog = null
                val old = connection
                connection = null
                writes?.cancel()
                writes = null
                failPending(TransportError.DeliveryUncertain)
                old?.cancel()
                if (!closeReason.shouldReconnect) {
                    // A replaced or refused connection must not be retried: one stops a reconnect
                    // war, the other cannot succeed.
                    active = false
                    releaseWaiting()
                    if (closeReason == SocketCloseReason.unauthorized) {
                        emit(GatewayEvent.State(ConnectionState.unauthorized))
                    }
                    emit(GatewayEvent.Closed(closeReason))
                    break
                }
                if (!active || epoch != generation || !currentCoroutineContext().isActive) break
                emit(GatewayEvent.State(ConnectionState.reconnecting))
                attempt += 1
                // Jitter keeps a fleet of apps from retrying in lockstep after a gateway restart.
                val backoff = min(2.0.pow(attempt - 1), 15.0)
                delay((backoff * Random.nextDouble(0.85, 1.15)).seconds)
            }
        } finally {
            if (epoch == generation) {
                worker = null
                connected = false
                ready = false
            }
            releaseWaiting()
        }
    }

    /**
     * One frame the app cannot read is one frame, not a broken connection.
     *
     * Only a transport failure ends a connection. Nothing validates frames against the schema at
     * runtime, so a device adapter that omits a required field on every event of a kind would
     * otherwise close the socket, fail every request in flight as `DeliveryUncertain` and
     * reconnect — for as long as that session ran. `ProtocolFailure.UnsupportedVersion` is not
     * raised here but in [handle], so it keeps its terminal behaviour.
     */
    private fun decode(data: ByteArray): AppFrame? = try {
        AppFrame(data = data)
    } catch (_: Exception) {
        report(data)
        null
    }

    /** Named once per kind, so an adapter emitting the same broken frame on every step of a turn is one line rather than a flood. */
    private fun report(undecodableData: ByteArray) {
        val kind = kind(undecodableData)
        if (!undecodable.add(kind)) return
        log.severe("dropped a frame this app could not read: $kind")
    }

    private suspend fun handle(frame: AppFrame, socket: WebSocketConnection) {
        when (frame) {
            is AppFrame.Hello -> {
                if (frame.hello.protocolVersion != RemoteProtocol.version) {
                    throw ProtocolFailure.UnsupportedVersion(frame.hello.protocolVersion)
                }
                ready = true
                releaseWaiting()
                emit(GatewayEvent.State(ConnectionState.connected))
                emit(GatewayEvent.Frame(frame))
            }
            AppFrame.Ping -> attempt { socket.send(GatewayRequest.pong().encoded().decodeToString()) }
            is AppFrame.Reply -> {
                val waiter = pending.remove(frame.id) ?: return
                frame.result.fold(onSuccess = { waiter.resume(it) }, onFailure = { waiter.resumeWithException(it) })
            }
            else -> emit(GatewayEvent.Frame(frame))
        }
    }

    private fun startWatchdog(generation: Int, socket: WebSocketConnection) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(5.seconds)
                if (!dropIfSilent(generation)) continue
                socket.cancel()
                return@launch
            }
        }
    }

    private fun dropIfSilent(generation: Int): Boolean {
        if (!active || !connected || epoch != generation) return false
        return lastReceived.elapsedNow() > silenceLimit
    }

    private fun emit(event: GatewayEvent) {
        continuation.trySend(event)
    }
}
