package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.FixtureSource
import com.junbingao.remotecontrol.core.protocol.CommandsResult
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.core.protocol.decode
import com.junbingao.remotecontrol.core.protocol.get
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.transport.DeviceListResponse
import com.junbingao.remotecontrol.core.transport.GatewayConfig
import com.junbingao.remotecontrol.core.transport.GatewayEndpoint
import kotlin.test.Test

/**
 * `ios/Verification/ProtocolChecks.swift`, the lines that read a store's type from the frozen
 * fixtures: what a device's row says about its client (`DeviceUpdate`), the link a host prints
 * (`PairingClaimLink`) and how a command list is sectioned (`CommandSection`). Everything else those
 * checks hold is the wire's, in `protocol/ProtocolChecks.kt` and its two companions.
 */
class ProtocolChecks {
    /** Amendment A36: a device's client, as its row describes it. */
    @Test
    fun deviceUpdates() {
        val checks = CheckRunner("protocol")
        val served = checkNotNull(FixtureSource.json("http/config.response.json").decode<GatewayConfig>().servedBuild) {
            "http/config.response.json names the served client build"
        }
        val devices = FixtureSource.json("http/devices.list.response.json").decode<DeviceListResponse>().devices
        checks.equal(devices.size, 2, "the device list decodes both devices")
        checks.expect(DeviceUpdate.notice(devices[0]) == null, "so its row says nothing at all about the client")
        checks.expect(!DeviceUpdate.canRetry(devices[0]), "and offers no update to retry")

        // A machine on an older build is the gateway's business, not a person's: nothing is said and
        // nothing is offered until an update of its own fails (A36).
        val behind = devices[0].copy(clientBuild = null)
        checks.expect(DeviceUpdate.notice(behind) == null, "a device on another build, or on none, is still the gateway's to update")
        checks.expect(!DeviceUpdate.canRetry(behind), "and is offered nothing either")

        val stranded = devices[0].copy(updateState = DeviceUpdateState.failed, updateMessage = "the device did not come back")
        checks.equal(DeviceUpdate.notice(stranded), DeviceUpdate.Notice.Failed("the device did not come back"),
                     "an update the gateway gave up on is said in the device's own words")
        checks.expect(DeviceUpdate.canRetry(stranded), "and is the one state Retry is offered in")
        checks.expect(DeviceUpdate.block(stranded, servedBuild = served) == null, "which a served wheel and a live socket allow")
        checks.equal(DeviceUpdate.block(stranded, servedBuild = null), DeviceUpdate.Block.noServedBuild,
                     "a gateway serving no wheel has nothing to retry with")
        checks.equal(DeviceUpdate.block(devices[1], servedBuild = served), DeviceUpdate.Block.offline,
                     "and an offline device is not asked to update")
        checks.assertAll()
    }

    /** Amendment A23: the link a host prints. */
    @Test
    fun claimTokens() {
        val checks = CheckRunner("protocol")
        val request = FixtureSource.json("http/devices.pairing.request.response.json")
        val claimURL = checkNotNull(request["claim_url"]?.stringValue) { "http/devices.pairing.request.response.json exists" }
        val token = request["token"]?.stringValue
        val gateway = GatewayEndpoint("https://rc.example.com")
        checks.equal(PairingClaimLink(payload = claimURL, gateway = gateway)?.token, token, "the printed link carries the claim token")
        checks.expect(PairingClaimLink(payload = claimURL, gateway = GatewayEndpoint("https://other.example.com")) == null,
                      "and a link for another gateway is not ours to claim")
        checks.expect(PairingClaimLink(payload = "https://rc.example.com/pair", gateway = gateway) == null,
                      "a link with no token is refused")
        checks.assertAll()
    }

    /** Amendment A27: the worked list, sectioned as the panel draws it. */
    @Test
    fun commands() {
        val checks = CheckRunner("protocol")
        val result = checkNotNull(FixtureSource.json("app/reply.session.commands.json")["result"]).decode<CommandsResult>()
        checks.equal(CommandSection.build(result.commands).map { it.title }, listOf("Built-in", "Prompts", "Skills"),
                     "groups section the list in the order they first appear")
        checks.assertAll()
    }
}
