package com.junbingao.remotecontrol.core.transport

import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One live WebSocket. The demo gateway and the tests provide their own. */
interface WebSocketConnection {
    suspend fun resume()
    suspend fun send(text: String)
    suspend fun send(binary: ByteArray)
    suspend fun receive(): ByteArray

    /** The close code the peer sent, once the socket has closed. It decides whether reconnecting is the right response or a loop. */
    suspend fun closeCode(): Int?
    suspend fun cancel()
}

interface WebSocketFactory {
    suspend fun makeConnection(request: Request): WebSocketConnection
}

/** Sockets over OkHttp, the platform networking both apps share. */
class OkHttpWebSocketFactory(private val client: OkHttpClient = socketClient) : WebSocketFactory {
    override suspend fun makeConnection(request: Request): WebSocketConnection = OkHttpWebSocket(client, request)

    private companion object {
        /**
         * No cookie jar, no cache, no authenticator: the bearer header on the upgrade request is
         * the only credential in play. A socket this app closes sends its close frame and is gone
         * a second later whatever the peer does, as a cancelled `URLSession` task is.
         */
        val socketClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .webSocketCloseTimeout(1, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * OkHttp opens a socket as soon as it is asked for, so asking waits for [resume]. What arrives is
 * queued for [receive], which throws once the peer has closed or the connection failed — after
 * whatever arrived before that has been read.
 */
private class OkHttpWebSocket(private val client: OkHttpClient, private val request: Request) :
    WebSocketListener(), WebSocketConnection {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var peerCloseCode: Int? = null

    override suspend fun resume() {
        if (socket == null) socket = client.newWebSocket(request, this)
    }

    override suspend fun send(text: String) {
        if (socket?.send(text) != true) throw IOException("The socket is not open.")
    }

    override suspend fun send(binary: ByteArray) {
        if (socket?.send(binary.toByteString()) != true) throw IOException("The socket is not open.")
    }

    override suspend fun receive(): ByteArray = incoming.receive()

    override suspend fun closeCode(): Int? = peerCloseCode

    override suspend fun cancel() {
        socket?.let { if (!it.close(GOING_AWAY, null)) it.cancel() }
        incoming.close(IOException("The socket was cancelled."))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        incoming.trySend(text.encodeToByteArray())
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        incoming.trySend(bytes.toByteArray())
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        peerCloseCode = code
        webSocket.close(NORMAL, null)
        incoming.close(IOException("The gateway closed the socket ($code)."))
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (peerCloseCode == null) peerCloseCode = code
        incoming.close(IOException("The gateway closed the socket ($code)."))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        incoming.close(t)
    }

    private companion object {
        const val NORMAL = 1000
        const val GOING_AWAY = 1001
    }
}
