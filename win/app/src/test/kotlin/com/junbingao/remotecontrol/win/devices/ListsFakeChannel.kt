package com.junbingao.remotecontrol.win.devices

import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.state.GatewayChannel
import com.junbingao.remotecontrol.core.transport.GatewayEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement

/**
 * A gateway channel for the lists' tests: it answers each request type from the table it is given
 * — a result or a refusal — and keeps what it was asked.
 */
internal class ListsFakeChannel(private val replies: Map<String, Result<JsonElement>>) : GatewayChannel {
    override val events: Flow<GatewayEvent> = emptyFlow()
    private val log = mutableListOf<GatewayRequest>()

    val asked: List<GatewayRequest> get() = synchronized(log) { log.toList() }

    override suspend fun connect() {}

    override suspend fun disconnect() {}

    override suspend fun request(request: GatewayRequest): JsonElement {
        synchronized(log) { log += request }
        val reply = replies[request.type] ?: throw GatewayErrorBody(code = GatewayErrorCode.notFound, message = "no reply for ${request.type}")
        return reply.getOrThrow()
    }
}
