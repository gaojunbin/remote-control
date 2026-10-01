package com.junbingao.remotecontrol.android.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.state.AppBuild
import com.junbingao.remotecontrol.core.state.AppVersion
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.MemoryUserDefaults
import com.junbingao.remotecontrol.core.state.SettingsStore
import com.junbingao.remotecontrol.core.transport.SessionLink
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import com.junbingao.remotecontrol.core.state.L10n as CoreL10n

/** `ios/VerificationUI`'s checks of the app model, on the demo gateway and the test's clock. */
@RunWith(AndroidJUnit4::class)
class AppModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val harness by lazy { AppModelHarness(context, folder.root) }

    @After
    fun englishAgain() {
        L10n.use(L10n.english)
        CoreL10n.use(InterfaceLanguage.en)
    }

    // Three tabs, one order, one landing rule

    @Test
    fun anAccountWithAMachineLandsOnTheConversationAndANewOneWhereItsFirstJobIs() {
        assertEquals(AppModel.Tab.sessions, AppModel.landingTab(hasDevices = true))
        assertEquals(AppModel.Tab.devices, AppModel.landingTab(hasDevices = false))
    }

    @Test
    fun theLandingRuleWaitsForTheFirstListAndRunsOnce() = runTest {
        val model = harness.model(this)
        model.tab = AppModel.Tab.settings
        model.decideLandingTab()
        assertEquals("the rule does not run until the device list has arrived", AppModel.Tab.settings, model.tab)
        model.perform { enterDemo() }
        settle { model.connection.hasSnapshot }
        model.decideLandingTab()
        assertEquals("and runs on the first list that does arrive", AppModel.Tab.sessions, model.tab)
        model.tab = AppModel.Tab.devices
        model.decideLandingTab()
        assertEquals("the landing rule runs once per sign-in and no more", AppModel.Tab.devices, model.tab)
    }

    // The interface language

    @Test
    fun englishIsTheDefaultAndALaunchCanPinAnother() = runTest {
        val defaults = MemoryUserDefaults()
        val first = harness.model(this, defaults = defaults)
        assertEquals("English is the default whatever the phone is set to", InterfaceLanguage.en, first.settings.language)
        first.settings.language = InterfaceLanguage.zhHans
        assertEquals("the app's own words follow at once", "设置", L10n.string("Settings"))
        assertEquals("the choice outlives the launch that made it", InterfaceLanguage.zhHans, SettingsStore(defaults).language)
        assertEquals("a reset returns the app to English", InterfaceLanguage.en,
                     harness.model(this, listOf("--reset-state"), defaults).settings.language)
        assertEquals("and a test can launch straight into the language it is about to read", InterfaceLanguage.zhHans,
                     harness.model(this, listOf("--reset-state", "--language=zh-Hans"), defaults).settings.language)
    }

    // Launch shows the app, never the sign-in form, when there is an account

    @Test
    fun aStoredGatewayIsSomethingToComeBackTo() = runTest {
        val defaults = MemoryUserDefaults()
        SettingsStore(defaults).remember(origin = "https://rc.example.com", username = "me")
        val resuming = harness.model(this, defaults = defaults)
        assertTrue("a stored gateway means the app has something to come back to", resuming.isResuming)
        assertFalse("and nothing is signed in until the Keystore answers", resuming.isSignedIn)
        assertFalse("a fresh install has nothing to come back to", harness.model(this).isResuming)
        assertTrue("the demo is an account like any other", harness.model(this, listOf("--demo")).isResuming)
    }

    // Deep links

    @Test
    fun aDeepLinkOpensTheNamedSessionOnTheSessionsTab() = runTest {
        val model = harness.model(this, listOf("--demo"))
        settle { model.connection.hasSnapshot }
        model.tab = AppModel.Tab.settings
        model.handle(SessionLink(deviceID = DemoFixtures.macDeviceID, sessionID = DemoFixtures.approvalSessionID))
        settle { model.chat?.sessionID == DemoFixtures.approvalSessionID }
        assertEquals("a deep link opens the named session", DemoFixtures.approvalSessionID, model.chat?.sessionID)
        assertEquals("a deep link lands on the sessions tab", AppModel.Tab.sessions, model.tab)
        model.handle(SessionLink(deviceID = "nope", sessionID = "nope"))
        assertNotNull("a link to an unknown session says so instead of opening nothing", model.toast)
    }

    @Test
    fun aLinkThatArrivesBeforeTheAccountWaitsForIt() = runTest {
        val model = harness.model(this)
        model.handle(SessionLink(deviceID = DemoFixtures.macDeviceID, sessionID = DemoFixtures.liveSessionID))
        assertNull("nothing opens while there is no account", model.chat)
        model.perform { enterDemo() }
        settle { model.connection.hasSnapshot }
        model.applyPendingLink()
        settle { model.chat != null }
        assertEquals(DemoFixtures.liveSessionID, model.chat?.sessionID)
    }

    // A notification opens its session in place

    @Test
    fun aNotificationReplacesTheConversationItFoundOpen() = runTest {
        val model = harness.model(this, listOf("--demo"))
        settle { model.connection.hasSnapshot }
        val held = model.connection.sessions.first { it.sessionID == DemoFixtures.liveSessionID }
        val opened = model.connection.sessions.first { it.sessionID == DemoFixtures.approvalSessionID }
        model.perform { open(held) }
        settle { model.chat?.key == held.id }
        assertEquals("a session opened from the list is the one thing on the stack", listOf(held.id), model.path)
        model.handle(SessionLink(deviceID = opened.deviceID, sessionID = opened.sessionID))
        settle { model.chat?.key == opened.id }
        assertEquals("a notification replaces the conversation it found open", listOf(opened.id), model.path)
        model.perform { closeChat(key = held.id) }
        drain()
        assertEquals("and the conversation it replaced does not close it on the way out", opened.id, model.chat?.key)
        model.perform { closeChat(key = opened.id) }
        settle { model.chat == null }
        assertNull("while the conversation on screen closes when it names itself", model.chat)
    }

    // A31 and A46: an app older than its gateway

    @Test
    fun aGatewayThatNeedsANewerAppSaysWhichOne() = runTest {
        val model = harness.model(this, listOf("--demo", "--demo-update-required"))
        settle { model.connection.updateRequired != null }
        val requirement = model.connection.updateRequired
        assertNotNull("below the minimum the app is told to update", requirement)
        assertEquals(AppVersion(AppBuild.version), requirement?.current)
        assertTrue(requirement!!.minimum > requirement.current)
    }

    // Sign out

    @Test
    fun signingOutLeavesNothingOpen() = runTest {
        val model = harness.model(this, listOf("--demo"))
        settle { model.connection.hasSnapshot }
        model.perform { open(connection.sessions.first { it.sessionID == DemoFixtures.liveSessionID }) }
        settle { model.chat != null }
        model.perform { signOut() }
        settle { !model.isSignedIn }
        assertFalse(model.isSignedIn)
        assertNull(model.chat)
        assertEquals(emptyList<String>(), model.path)
    }

    // `--reset-state` forgets every draft

    @Test
    fun aResetForgetsEveryDraft() = runTest {
        harness.drafts.setDraft("left over", account = "demo|", key = "a/b")
        harness.model(this, listOf("--reset-state"))
        drain()
        assertEquals("", harness.drafts.draft(account = "demo|", key = "a/b"))
    }

    // A draft survives the conversation that held it

    @Test
    fun aDraftIsKeptWhenItsConversationCloses() = runTest {
        val model = harness.model(this, listOf("--demo"))
        settle { model.connection.hasSnapshot }
        val session = model.connection.sessions.first { it.sessionID == DemoFixtures.liveSessionID }
        model.perform { open(session) }
        settle { model.chat != null }
        model.chat!!.draft = "half a thought"
        model.perform { closeChat() }
        settle { model.chat == null }
        model.perform { open(session) }
        settle { model.chat != null }
        assertEquals("half a thought", model.chat?.draft)
    }
}
