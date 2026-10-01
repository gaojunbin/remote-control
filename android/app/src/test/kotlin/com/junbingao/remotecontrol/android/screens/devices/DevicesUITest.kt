package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.screens.sessions.ListsDriver
import com.junbingao.remotecontrol.core.state.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The iPhone's UI tests of the Devices screen (`ios/UITests/RemoteControlUITests.swift`), on the
 * demo through the same steps: the rows, the platform filter, the swipe in rule 20's order, a tap
 * that opens nothing, Revoke and a retried update.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class DevicesUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun devices(test: String, body: (DemoApp, ListsDriver) -> Unit) = DemoApp(compose, test).use { app ->
        app.tap("tab.devices")
        app.waitFor("devices.add")
        body(app, ListsDriver(compose, app))
    }

    private fun row(deviceID: String) = "device.$deviceID"

    /** `RealGatewaySmokeTests.testDevicesTabListsTheEnrolledMachine`, on the demo's machines. */
    @Test
    fun devicesTabListsTheEnrolledMachine() = devices("testDevicesTabListsTheEnrolledMachine") { app, lists ->
        assertTrue("the devices screen appears", lists.hasText("Devices"))
        assertTrue("adding a device is offered", app.exists("devices.add"))
        app.waitFor(row(Demo.studio))
        assertTrue("the enrolled machine is listed", lists.label(row(Demo.studio)).contains("mac-studio-office"))
        assertTrue("the device row names the agents it found", lists.label(row(Demo.studio)).contains("Claude Code"))
        app.attach("04-devices")
    }

    @Test
    fun deviceRowNamesTheMachineOnceAndDrawsItsAgentsAsLogos() = devices("testDeviceRowNamesTheMachineOnceAndDrawsItsAgentsAsLogos") { app, lists ->
        app.waitFor(row(Demo.studio))
        val label = lists.label(row(Demo.studio))
        assertTrue("the row is headed by the machine's name", label.contains("mac-studio-office"))
        assertTrue("and reads state and platform as words beside the dot", label.contains("online · macOS"))
        assertFalse("the hostname no longer repeats the name one line down", label.contains("mac-studio.local"))
        assertFalse("and the chip nobody chooses a machine by is gone", label.contains("arm64"))
        assertFalse("the raw platform id is never on screen", label.contains("macos"))
        for (agent in listOf("Claude Code", "Codex", "Grok Build")) {
            assertTrue("$agent's logo is read out under its own name", label.contains(agent))
        }
        // Amendment A36: the gateway keeps every machine on the wheel it serves, so the row states
        // no client version and no build, and never offers one.
        assertFalse("the row states no client version", label.contains(AppBuild.version))
        assertFalse("nor the word client", label.contains("client"))
        assertFalse("nor the build hash", label.contains("3f2b4a9c"))
        assertFalse("and never that an update is available", label.contains("Update available"))

        val runner = lists.label(row(Demo.ci))
        assertTrue("which says Linux, not linux", runner.contains("offline · Linux"))
        assertFalse("and carries no architecture either", runner.contains("x86_64"))
        assertFalse("an outdated machine names no version either", runner.contains("1.3.0"))
        assertFalse("and is not called out of date", runner.contains("Update available"))
        assertFalse("no row on the screen says an update is available", lists.onScreen("Update available"))
        app.attach("59-device-rows")
    }

    /** The Devices screen filters by platform the way Sessions filters by agent (owner's ruling, 2026-09-18): the same control, top right. */
    @Test
    fun platformFilterNarrowsTheDevicesToOnePlatform() = devices("testPlatformFilterNarrowsTheDevicesToOnePlatform") { app, lists ->
        app.waitFor(row(Demo.studio))
        assertTrue("and so is the Linux runner", app.exists(row(Demo.ci)))
        app.tap("devices.platformFilter")
        app.waitFor("devices.platformFilter.linux")
        assertTrue("both of them", app.exists("devices.platformFilter.macos"))
        app.tap("devices.platformFilter.linux")

        app.waitForAbsence(row(Demo.studio))
        assertTrue("the Linux runner stays", app.exists(row(Demo.ci)))
        assertEquals("and the button says what the list is narrowed to", "Linux", lists.state("devices.platformFilter"))
        app.attach("58-device-platform-filter")

        app.tap("devices.platformFilter")
        app.tap("devices.platformFilter.all")
        app.waitFor(row(Demo.studio))
    }

    /** Amendment A38, rule 20: one swipe, in one order on both apps — Rename · Retry update (only while one has failed) · Show quota · Revoke. */
    @Test
    fun deviceRowSwipeHoldsTheMenuInRuleTwentysOrder() = devices("testDeviceRowSwipeHoldsTheMenuInRuleTwentysOrder") { app, lists ->
        app.waitFor(row(Demo.studio))
        lists.swipeLeft(row(Demo.studio))
        lists.waitShown("device.rename")
        assertTrue("Show quota", lists.isShown("device.showQuota"))
        assertTrue("and Revoke", lists.isShown("device.revoke"))
        assertFalse("with nothing to retry on a machine whose update never failed", lists.isShown("device.retryUpdate"))
        assertTrue("read left to right the row says Rename, then Show quota", lists.left("device.rename") < lists.left("device.showQuota"))
        assertTrue("and Revoke nearest the edge", lists.left("device.showQuota") < lists.left("device.revoke"))
        app.attach("60-device-swipe-actions")

        lists.tapShown("device.rename")
        lists.waitText("Rename device")
        app.tap("alert.cancel")
        app.waitForAbsence("alert.cancel")

        // The one machine the gateway gave up on carries Retry update between Rename and Show
        // quota, and it is the only row that does.
        lists.swipeLeft(row(Demo.laptop))
        lists.waitShown("device.retryUpdate")
        assertTrue("after Rename", lists.left("device.rename") < lists.left("device.retryUpdate"))
        assertTrue("before Show quota", lists.left("device.retryUpdate") < lists.left("device.showQuota"))
        assertTrue("and before Revoke", lists.left("device.showQuota") < lists.left("device.revoke"))
        app.attach("60b-device-swipe-retry")
    }

    /** The other half of rule 20: a machine that cannot be opened says why where it stands, and nothing is pushed. */
    @Test
    fun aDeviceWithNoTerminalSaysSoInsteadOfOpeningOne() = devices("testADeviceWithNoTerminalSaysSoInsteadOfOpeningOne") { app, lists ->
        app.tap(row(Demo.laptop))
        app.waitFor("device.terminalRefusal")
        app.attach("91-device-tap-refused")
        assertEquals("saying which of the two reasons it is", "This device does not offer a terminal.", lists.text("device.terminalRefusal"))
        assertFalse("and nothing is opened", app.exists("terminal.close"))

        app.tap(row(Demo.ci))
        app.await("the offline reason, in the one place") { lists.text("device.terminalRefusal") == "This device is offline." }
        assertFalse("with nothing opened either", app.exists("terminal.close"))
    }

    /** The word for taking a machine's token away is Revoke on both apps, and the alert says what it costs before it acts. */
    @Test
    fun revokingADeviceIsConfirmedByThatName() = devices("testRevokingADeviceIsConfirmedByThatName") { app, lists ->
        app.waitFor(row(Demo.laptop))
        lists.swipeLeft(row(Demo.laptop))
        lists.tapShown("device.revoke")
        lists.waitText("Revoke device")
        assertTrue("and says what the machine loses", lists.onScreen("token stops working"))
        assertEquals("with the confirm named for the act", "Revoke device", lists.label("device.revoke.confirm"))
        app.attach("70-device-revoke-confirm")
        app.tap("alert.cancel")
    }

    /**
     * Amendment A36: the gateway updates every machine by itself, so the only row that says
     * anything is the one whose update failed — and the only action offered there is Retry update,
     * which confirms what it costs and then carries the row through the update.
     */
    @Test
    fun failedUpdateIsRetriedFromTheRowAndRunsToCompletion() = devices("testFailedUpdateIsRetriedFromTheRowAndRunsToCompletion") { app, lists ->
        app.waitFor(row(Demo.laptop))
        lists.waitText("Update failed · the device did not come back")
        assertFalse("without the version it is stuck on", lists.label(row(Demo.laptop)).contains("1.3.0"))
        app.attach("61-device-update-failed")

        lists.swipeLeft(row(Demo.laptop))
        lists.tapShown("device.retryUpdate")
        lists.waitText("Update device")
        assertTrue("and names the version it would land on", lists.onScreen("to ${AppBuild.version}?"))
        assertTrue("and says what it costs the device", lists.onScreen("service restarts"))
        app.attach("62-device-update-confirm")
        app.tap("device.update.confirm")

        lists.waitText("Updating…", 15_000)
        app.attach("63-device-updating")

        app.await("the row falls silent once the device is back", 30_000) {
            val label = lists.label(row(Demo.laptop))
            !label.contains("Updating…") && !label.contains("Update failed")
        }
        val settled = lists.label(row(Demo.laptop))
        assertFalse("no version on the row it came back to", settled.contains(AppBuild.version))
        assertFalse("and never the build hash", settled.contains("3f2b4a9c"))
        assertFalse("with nothing left to retry", app.exists("device.retryUpdate"))
        app.attach("64-device-updated")
    }
}

/** The demo device ids, which are the row identifiers. */
internal object Demo {
    const val studio = "demo-mac-studio"
    const val laptop = "demo-macbook-air"
    const val ci = "demo-ci-runner"
}
