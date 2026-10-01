package com.junbingao.remotecontrol.win.devices

import com.junbingao.remotecontrol.core.protocol.AccountMethod
import com.junbingao.remotecontrol.core.protocol.AgentAccount
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentLimit
import com.junbingao.remotecontrol.core.protocol.AgentsResult
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.JSONValue
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.stringValue
import com.junbingao.remotecontrol.core.state.DotTone
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.SessionClose
import com.junbingao.remotecontrol.core.state.SessionListLayout
import com.junbingao.remotecontrol.win.devices.page.DeviceQuota
import com.junbingao.remotecontrol.win.sessions.ListsSessionLayoutTests
import com.junbingao.remotecontrol.win.sessions.SessionLegend
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Mac's "Lists: device quota" — `web/tests/DevicePage.test.tsx` § "what stands where the
 * meters go", on the page's own request: Checking… until the reply, the accounts it brought by
 * agent, and the one line that stands in for the meters.
 */
class ListsQuotaTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private val account = AgentAccount(provider = "anthropic", method = AccountMethod.account, plan = "max",
                                       limits = listOf(AgentLimit(windowMinutes = 300, usedPercent = 16.0)))

    @Test
    fun asksAnOfflineDeviceNothingAndSaysTheQuotaIsUnavailable() = runTest {
        val quota = DeviceQuota()
        val channel = ListsFakeChannel(emptyMap())
        quota.check(deviceID = "a", online = false, channel = channel)
        assertEquals(DeviceQuota.Status.offline, quota.status)
        assertTrue(channel.asked.isEmpty())
    }

    @Test
    fun keepsTheFreshAccountsByAgent() = runTest {
        val reply = AgentsResult(agents = listOf(AgentInfo(agent = "claude", available = true, accounts = listOf(account)),
                                                 AgentInfo(agent = "grok", available = true)))
        val channel = ListsFakeChannel(mapOf("device.agents" to Result.success(JSONValue.encode(reply))))
        val quota = DeviceQuota()
        quota.check(deviceID = "a", online = true, channel = channel)
        assertEquals(DeviceQuota.Status.ready, quota.status)
        assertEquals(16.0, quota.accounts["claude"]?.first()?.limits?.first()?.usedPercent)
        assertNull(quota.accounts["grok"])
        assertEquals("a", channel.asked.first().body["device_id"]?.stringValue)
    }

    @Test
    fun readsDeviceOfflineAsOfflineAndAnyOtherRefusalInTheDevicesWords() = runTest {
        val quota = DeviceQuota()
        quota.check(deviceID = "a", online = true, channel = ListsFakeChannel(mapOf(
            "device.agents" to Result.failure(GatewayErrorBody(code = GatewayErrorCode.deviceOffline, message = "the device is offline")),
        )))
        assertTrue(quota.status == DeviceQuota.Status.offline && quota.error == null)
        quota.check(deviceID = "a", online = true, channel = ListsFakeChannel(mapOf(
            "device.agents" to Result.failure(GatewayErrorBody(code = GatewayErrorCode.internalError, message = "codex app-server exited")),
        )))
        assertTrue(quota.status == DeviceQuota.Status.failed && quota.error == "codex app-server exited")
    }

    @Test
    fun refreshIsAnotherAttempt() {
        val quota = DeviceQuota()
        quota.refresh()
        quota.refresh()
        assertEquals(2, quota.attempt)
    }
}

/** The Mac's "Lists: dot legend" — `web/tests/SessionsPage.test.tsx` § "the dot legend": the four colours in order, in the interface language. */
class ListsLegendTests {
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    @Test
    fun readsTheFourColoursInOrderInBothLanguages() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
        assertEquals(listOf(DotTone.working, DotTone.live, DotTone.off, DotTone.failed), SessionLegend.entries.map { it.tone })
        assertEquals(listOf("Working", "For you", "Not running", "Error"), SessionLegend.entries.map { it.label })
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        assertEquals(listOf("运行中", "等你处理", "未运行", "出错"), SessionLegend.entries.map { it.label })
    }

    @Test
    fun asksBeforeClosingOnlyAWorkingSessionAndOffersCloseOnlyOnARemoteOne() {
        val running = ListsSessionLayoutTests.session("r", state = SessionState.running)
        val idle = ListsSessionLayoutTests.session("i", state = SessionState.idle)
        assertTrue(SessionClose.asksFirst(running, online = true))
        assertFalse(SessionClose.asksFirst(idle, online = true))
        assertFalse(SessionClose.asksFirst(running, online = false))
        assertTrue(SessionListLayout.offersClose(idle))
        assertFalse(SessionListLayout.offersClose(ListsSessionLayoutTests.session("t", control = SessionControl.terminal)))
        assertFalse(SessionListLayout.offersClose(ListsSessionLayoutTests.session("a", archived = true)))
    }
}
