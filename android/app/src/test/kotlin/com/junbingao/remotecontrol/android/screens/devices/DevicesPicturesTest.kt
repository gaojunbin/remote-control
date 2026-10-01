package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.screens.sessions.ListsDriver
import com.junbingao.remotecontrol.android.screens.sessions.Pictures
import com.junbingao.remotecontrol.android.strings.L10n
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Devices screen, a machine's page, pairing and the terminal in Chinese and dark ([Pictures]). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class DevicesPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun row(deviceID: String) = "device.$deviceID"

    private fun devices(test: String, steps: (DemoApp, ListsDriver) -> Unit) = Pictures.each(compose, test) { app, lists ->
        app.tap("tab.devices")
        app.waitFor(row(Demo.studio))
        steps(app, lists)
    }

    @Test
    fun theRows() = devices("testDeviceRowNamesTheMachineOnceAndDrawsItsAgentsAsLogos") { app, _ ->
        app.attach("59-device-rows")
    }

    @Test
    fun aRowsSwipe() = devices("testDeviceRowSwipeHoldsTheMenuInRuleTwentysOrder") { app, lists ->
        lists.swipeLeft(row(Demo.laptop))
        lists.waitShown("device.retryUpdate")
        app.attach("60b-device-swipe-retry")
    }

    @Test
    fun revoking() = devices("testRevokingADeviceIsConfirmedByThatName") { app, lists ->
        lists.swipeLeft(row(Demo.laptop))
        lists.tapShown("device.revoke")
        app.waitFor("device.revoke.confirm")
        app.attach("70-device-revoke-confirm")
        app.tap("alert.cancel")
    }

    @Test
    fun aRefusedTap() = devices("testADeviceWithNoTerminalSaysSoInsteadOfOpeningOne") { app, _ ->
        app.tap(row(Demo.laptop))
        app.waitFor("device.terminalRefusal")
        app.attach("91-device-tap-refused")
    }

    @Test
    fun aMachinesPage() = devices("testDevicePageShowsHowEachAgentIsSignedInAndWhatIsLeft") { app, lists ->
        lists.swipeLeft(row(Demo.studio))
        lists.tapShown("device.showQuota")
        app.waitFor("device.quota.meters", 20_000)
        app.attach("81-device-page-quota")
    }

    @Test
    fun addingADevice() = devices("testDevicesTabShowsPairingSheet") { app, _ ->
        app.tap("devices.add")
        app.waitFor("pairing.command")
        app.attach("05-add-device")
        app.tap("pairing.cancel")
    }

    @Test
    fun scanning() = devices("testScanningAPrintedCodePairsTheHost") { app, _ ->
        app.tap("devices.add")
        app.tap("pairing.scan")
        // The strip says nothing until the camera is looking, and then that it is.
        app.waitFor("scan.status", 10_000)
        app.attach("65-scan-overlay")
    }

    @Test
    fun aTerminal() = devices("testTappingADeviceOpensATerminalOnIt") { app, lists ->
        app.tap(row(Demo.studio))
        app.await("the shell is up", 20_000) { lists.text("terminal.status") == L10n.string("Connected") }
        // The demo's shell greets a moment after it opens.
        lists.pause(500)
        app.attach("ios-round42-terminal")
        app.tap("terminal.close")
    }
}
