package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.screens.sessions.ListsDriver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The iPhone's UI tests of Add device: the sheet, and the scan flow (amendment A23) with the camera
 * replaced by the stand-in the demo injects — the JVM has none, as a simulator has none.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class PairingUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun addDevice(test: String, body: (DemoApp, ListsDriver) -> Unit) = DemoApp(compose, test).use { app ->
        app.tap("tab.devices")
        app.tap("devices.add")
        body(app, ListsDriver(compose, app))
    }

    @Test
    fun devicesTabShowsPairingSheet() = addDevice("testDevicesTabShowsPairingSheet") { app, lists ->
        app.waitFor("pairing.command")
        assertTrue("the command can be copied", app.exists("pairing.copy"))
        // `docs/DESIGN.md` § "Add device": the installer tells macOS from Linux itself, so the sheet
        // asks nobody to choose one.
        assertFalse("and no platform is asked for", app.exists("pairing.platform"))
        app.attach("05-add-device")

        // Cancel leaves at once. The code is given back behind the closed sheet, so the
        // "Requesting a code" placeholder is never drawn on the way out (owner's report, 2026-09-18).
        app.tap("pairing.cancel")
        app.waitForAbsence("pairing.command", 5_000)
        assertFalse("and nothing about requesting a code was left on screen", lists.hasText("Requesting a code"))
        app.waitFor("devices.add", 5_000)
    }

    /** Amendment A23: the whole scan flow, with the camera replaced by the stand-in the demo injects. */
    @Test
    fun scanningAPrintedCodePairsTheHost() = addDevice("testScanningAPrintedCodePairsTheHost") { app, lists ->
        app.tap("pairing.scan")

        lists.waitText("Pair with Remote Control", 10_000)
        assertTrue("step one is the one-liner the host runs", lists.text("scan.command").contains("install.sh | sh"))
        assertTrue("which can be copied", app.exists("scan.copy"))
        app.await("the strip says what the camera is doing") { lists.text("scan.status").contains("Hold steady") }
        app.attach("65-scan-overlay")

        app.tap("scan.simulate")
        app.waitFor("pairing.steps", 15_000)
        lists.waitText("RC-9M27-TB4K", 10_000)
        app.attach("66-scan-claimed")
        lists.waitText("Device online", 20_000)
        app.attach("67-scan-progress")
    }
}
