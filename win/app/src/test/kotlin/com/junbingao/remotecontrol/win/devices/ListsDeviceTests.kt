package com.junbingao.remotecontrol.win.devices

import com.junbingao.remotecontrol.core.protocol.Device
import com.junbingao.remotecontrol.core.protocol.DevicePlatform
import com.junbingao.remotecontrol.core.protocol.DeviceUpdateState
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.sessions.ListsSessionLayoutTests
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Mac's "Lists: devices" — `web/tests/DevicesPage.test.tsx` and `DeviceUpdate.test.tsx`, on
 * the rules the device row and the device page read: the order, the counts, the platform as a
 * word, the client line that speaks only while something is happening (A36), and why a retry
 * cannot be sent.
 */
class ListsDeviceTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private fun device(id: String, name: String, online: Boolean = true, platform: DevicePlatform = DevicePlatform.macos,
                       state: DeviceUpdateState = DeviceUpdateState.idle, message: String? = null) =
        Device(deviceID = id, name = name, platform = platform, hostname = name, arch = "arm64", clientVersion = "1.10.0",
               updateState = state, updateMessage = message, online = online, lastSeen = 0, createdAt = 0)

    @Test
    fun listsTheDevicesByNameAsTheWebStoreKeepsThem() {
        val devices = listOf(device("b", "mac-studio-office"), device("a", "ci-runner-01"), device("c", "Beta", online = false))
        assertEquals(listOf("Beta", "ci-runner-01", "mac-studio-office"), DeviceOrder.byName(devices).map { it.name })
        assertEquals(listOf("ci-runner-01", "mac-studio-office"), DeviceOrder.online(devices).map { it.name })
    }

    /** The demo's three machines, in the order the Mac and the browser list them: a hyphen sorts before a letter. */
    @Test
    fun sortsAHyphenBeforeALetterAsTheBrowserDoes() {
        val devices = listOf(device("a", "macbook-air"), device("b", "mac-studio-office"), device("c", "ci-runner-01"),
                             device("d", "Mac-Mini"), device("e", "mac-mini"))
        assertEquals(listOf("ci-runner-01", "mac-mini", "Mac-Mini", "mac-studio-office", "macbook-air"), DeviceOrder.byName(devices).map { it.name })
    }

    @Test
    fun countsTheSessionsNobodyArchivedByHand() {
        val sessions = listOf(
            ListsSessionLayoutTests.session("1", device = "a"),
            ListsSessionLayoutTests.session("2", device = "a", control = SessionControl.none),
            ListsSessionLayoutTests.session("3", device = "a", archived = true),
            ListsSessionLayoutTests.session("4", device = "b"),
        )
        assertEquals(mapOf("a" to 2, "b" to 1), DeviceOrder.sessionCounts(sessions))
    }

    @Test
    fun writesThePlatformsTheWayTheirMakersDoAndAnyOtherAsSent() {
        assertEquals("macOS", S.platformLabel("macos"))
        assertEquals("Linux", S.platformLabel("linux"))
        assertEquals("freebsd", S.platformLabel("freebsd"))
    }

    @Test
    fun saysNothingAboutAClientWhileNothingIsHappening() {
        assertNull(DeviceUpdateWords.notice(device("a", "a"), localError = null))
    }

    @Test
    fun saysUpdatingWhileAnUpdateRuns() {
        assertEquals(DeviceUpdateWords.Notice("Updating…", failed = false), DeviceUpdateWords.notice(device("a", "a", state = DeviceUpdateState.updating), localError = null))
    }

    @Test
    fun saysWhyAnUpdateFailed() {
        val failed = device("a", "a", state = DeviceUpdateState.failed, message = "the device did not come back")
        assertEquals(DeviceUpdateWords.Notice("Update failed · the device did not come back", failed = true), DeviceUpdateWords.notice(failed, localError = null))
    }

    @Test
    fun showsTheDevicesOwnWordsWhenARetryIsRefused() {
        assertEquals(
            DeviceUpdateWords.Notice("Update failed · a session is running", failed = true),
            DeviceUpdateWords.notice(device("a", "a"), localError = "a session is running"),
        )
    }

    @Test
    fun blocksARetryWhileTheDeviceIsOfflineOrTheGatewayServesNoWheel() {
        assertEquals("This device is offline.", DeviceUpdateWords.retryBlocked(device("a", "a", online = false), servedBuild = "abc"))
        assertEquals("This gateway is not serving a client build.", DeviceUpdateWords.retryBlocked(device("a", "a"), servedBuild = null))
        assertNull(DeviceUpdateWords.retryBlocked(device("a", "a"), servedBuild = "abc"))
    }

    @Test
    fun confirmsARetryWithTheVersionItInstallsOrTheGatewaysClient() {
        assertEquals("Update ci-runner-01 to 1.10.0? Its service restarts; sessions it drives are stopped.", S.devices.updateBody("ci-runner-01", "1.10.0"))
        assertEquals(
            "Update ci-runner-01 to the gateway's client? Its service restarts; sessions it drives are stopped.",
            S.devices.updateBody("ci-runner-01", null),
        )
    }

    @Test
    fun speaksChineseOnTheRowToo() {
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        val notice = DeviceUpdateWords.notice(device("a", "a", state = DeviceUpdateState.failed, message = "boom"), localError = null)
        assertEquals("更新失败 · boom", notice?.text)
        assertEquals("此设备已离线。", DeviceUpdateWords.retryBlocked(device("a", "a", online = false), servedBuild = null))
    }
}
