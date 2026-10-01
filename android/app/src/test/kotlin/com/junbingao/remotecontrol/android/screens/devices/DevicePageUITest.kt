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
 * The iPhone's UI tests of a machine's page (amendment A33), reached from the row's swipe as Show
 * quota since A38: the facts, the notice and its Retry, how each agent is signed in and what is
 * left of its quota.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class DevicePageUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** Amendment A38, rule 20: the row's own tap opens a terminal, so the machine's page is reached from the row's menu as Show quota. */
    private fun page(test: String, deviceID: String, body: (DemoApp, ListsDriver) -> Unit) = DemoApp(compose, test).use { app ->
        val lists = ListsDriver(compose, app)
        app.tap("tab.devices")
        app.waitFor("device.$deviceID")
        lists.swipeLeft("device.$deviceID")
        lists.tapShown("device.showQuota")
        body(app, lists)
    }

    /**
     * The machine's own page says no more about its client than its row does (`docs/DESIGN.md`
     * § "A device keeps itself current"): no version, no build, and the same notice with a retry
     * beside it where one failed.
     */
    @Test
    fun devicePageStatesNoClientVersionAndCarriesTheRetry() = page("testDevicePageStatesNoClientVersionAndCarriesTheRetry", Demo.laptop) { app, lists ->
        app.waitFor("device.page")
        lists.waitText("macbook-air.local · arm64")
        app.waitFor("device.updateNotice")
        assertTrue("in the same words", lists.text("device.updateNotice").contains("the device did not come back"))
        assertTrue("with Retry update beside it", app.exists("device.retryUpdate"))
        assertFalse("the page states no client version", lists.onScreen("client "))
        assertFalse("not even the one the machine is stuck on", lists.onScreen("1.3.0"))
        assertFalse("and no build hash", lists.onScreen("3f2b4a9c"))
        // The iPhone's picture is of the answered page: its queries outlast the scripted device's
        // three seconds, where these are immediate.
        app.waitFor("device.quota.error")
        app.attach("65-device-page-update-failed")

        app.tap("device.retryUpdate")
        lists.waitText("Update device")
        assertTrue("the same confirmation the row shows", lists.onScreen("to ${AppBuild.version}?"))
        app.tap("alert.cancel")
    }

    @Test
    fun devicePageShowsHowEachAgentIsSignedInAndWhatIsLeft() = page("testDevicePageShowsHowEachAgentIsSignedInAndWhatIsLeft", Demo.studio) { app, lists ->
        // The windows are read on request, so the meters say they are coming. This is the first
        // thing the page does, so it is the first thing looked at: the scripted device answers
        // three seconds later.
        app.waitFor("device.quota.checking", 15_000)
        app.attach("80-device-page-checking")

        assertTrue("the row's Show quota opens the machine's page", app.exists("device.page"))
        assertTrue("the page keeps the hostname and the architecture the row no longer shows", lists.onScreen("mac-studio.local · arm64"))
        assertFalse("a machine being kept current says nothing about its client", app.exists("device.updateNotice"))
        assertFalse("no version on the page either", lists.onScreen("client "))
        assertFalse("and no build hash", lists.onScreen("3f2b4a9c"))
        for (agent in listOf("claude", "codex", "grok", "pi")) {
            assertTrue("every agent the machine found has a card", app.exists("device.agent.$agent"))
        }
        assertFalse("the three row actions are not repeated on the page", lists.isShown("device.revoke"))

        app.waitFor("device.quota.meters", 20_000)
        val meters = lists.allText("device.quota.meters")
        assertTrue("a window is named by its length", meters.contains("5-hour"))
        assertTrue("with the share it has spent", meters.contains("%"))
        assertTrue("and a weekly window confined to one model says which", lists.onScreen("7-day · Fable"))
        assertTrue("an account names its vendor, its plan, its tier and its email", lists.onScreen("Anthropic account · Max · Max 5x · me@example.com"))
        assertTrue("a vendor with no window to report still says how it is signed in", lists.onScreen("xAI account"))
        assertTrue("and a key names its vendor and the host it is sent to", lists.onScreen("OpenAI API key · api.relay.example"))
        app.attach("81-device-page-quota")
    }

    /** A machine that is not there to ask keeps the credentials it last reported and says so where the meters go. */
    @Test
    fun offlineDevicePageKeepsItsAccountsAndSaysWhyThereAreNoMeters() = page("testOfflineDevicePageKeepsItsAccountsAndSaysWhyThereAreNoMeters", Demo.ci) { app, lists ->
        app.waitFor("device.page")
        lists.waitText("OpenAI account")
        app.waitFor("device.quota.offline")
        assertFalse("with no meter drawn from a stale figure", app.exists("device.quota.meters"))
        app.attach("82-device-page-offline")
    }

    /**
     * The two shapes that are not a meter: a window the device could not read, which says why in
     * the device's own words, and an agent installed and signed in nowhere.
     */
    @Test
    fun devicePageSaysWhyAQuotaIsMissingAndWhenNothingIsSignedIn() = page("testDevicePageSaysWhyAQuotaIsMissingAndWhenNothingIsSignedIn", Demo.laptop) { app, lists ->
        app.waitFor("device.page")
        app.waitFor("device.quota.error", 20_000)
        assertTrue("in the device's own words", lists.text("device.quota.error").contains("expired"))
        assertFalse("and draws no meter beside it", app.exists("device.quota.meters"))
        assertEquals("an agent signed in nowhere says so", "Not signed in", lists.text("device.agent.grok.signIn"))
        app.attach("84-device-page-no-quota")
    }
}
