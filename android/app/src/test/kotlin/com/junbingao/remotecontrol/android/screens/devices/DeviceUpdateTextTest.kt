package com.junbingao.remotecontrol.android.screens.devices

import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.state.DeviceUpdate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ios/VerificationUI` § "Amendment A36: a device keeps itself current": the only two things an app
 * says about a client, the confirmation that names what a retry installs, and the reasons a retry
 * cannot act — over the demo's own machines.
 */
class DeviceUpdateTextTest {
    @After
    fun englishAgain() = L10n.use(L10n.english)

    @Test
    fun theLineFollowsAnUpdateAndSaysWhyOneFailed() {
        assertEquals("Updating…", DeviceUpdateText.line(DeviceUpdate.Notice.Updating))
        assertTrue(DeviceUpdateText.line(DeviceUpdate.Notice.Failed("no wheel")).contains("no wheel"))
        assertTrue(DeviceUpdate.Notice.Failed("no wheel").isFailure)
        assertFalse(DeviceUpdate.Notice.Updating.isFailure)
    }

    @Test
    fun theConfirmationNamesTheMachineAndTheVersion() {
        assertEquals(
            "Update macbook-air to ${DemoFixtures.servedClientVersion}? Its service restarts; sessions it drives are stopped.",
            DeviceUpdateText.confirmation("macbook-air", DemoFixtures.servedClientVersion),
        )
        assertEquals(
            "and falls back to the gateway's client where there is no version",
            "Update macbook-air to the gateway's client? Its service restarts; sessions it drives are stopped.",
            DeviceUpdateText.confirmation("macbook-air", null),
        )
    }

    @Test
    fun aRetryThatCannotActSaysWhy() {
        assertEquals("This device is offline.", DeviceUpdateText.reason(DeviceUpdate.Block.offline))
        assertEquals("This gateway is not serving a client build.", DeviceUpdateText.reason(DeviceUpdate.Block.noServedBuild))
    }

    @Test
    fun noRowInTheDemoStatesAClientVersionOrABuild() {
        for (device in DemoFixtures.devices) {
            val line = DeviceUpdate.notice(device)?.let(DeviceUpdateText::line) ?: ""
            assertFalse("${device.name}'s row never states the client version it runs", device.clientVersion.isNotEmpty() && line.contains(device.clientVersion))
            assertFalse("nor any part of a build hash", line.contains(DemoFixtures.servedBuild.take(8)))
        }
        val laptop = DemoFixtures.devices.first { it.deviceID == DemoFixtures.laptopDeviceID }
        assertEquals("Update failed · ${DemoFixtures.updateFailure}", DeviceUpdate.notice(laptop)?.let(DeviceUpdateText::line))
    }

    @Test
    fun theWordsFollowTheInterfaceLanguage() {
        val english = DeviceUpdateText.line(DeviceUpdate.Notice.Updating)
        L10n.use(L10n.chinese)
        val chinese = DeviceUpdateText.line(DeviceUpdate.Notice.Updating)
        assertTrue("the catalogue's own words, not the English key", chinese != english && chinese.isNotEmpty())
    }
}
