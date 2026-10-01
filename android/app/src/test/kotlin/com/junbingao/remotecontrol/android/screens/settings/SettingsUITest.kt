package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.screens.shell.Driving
import com.junbingao.remotecontrol.android.screens.shell.Phone
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.state.AppBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings tests of `ios/UITests/RemoteControlUITests.swift`, step for step, on the demo: the
 * header and the versions line, the group captions, the resume switch, polish, Transcribe and a
 * preference changed elsewhere. Each picture is taken where the iPhone's test takes its own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class SettingsUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun phoneWithAScreenLock() = Phone.hasAScreenLock()

    private fun launch(test: String, vararg extra: String, run: (DemoApp, Driving) -> Unit) =
        DemoApp(compose, test, DemoApp.launchArguments + extra).use { app -> run(app, Driving(compose, app)) }

    /** `docs/DESIGN.md` § "The Settings screen": the groups are headed in sentence case, and the groups the ruling took away are gone. */
    @Test
    fun settingsSectionHeadersAreSentenceCase() = launch("testSettingsSectionHeadersAreSentenceCase") { app, drive ->
        app.waitFor("tab.settings")
        app.tap("tab.settings")
        app.waitFor("settings.identity", 15_000)
        app.attach("30-settings-headers")

        // The last groups are below the fold on a phone, so they are scrolled to rather than assumed.
        for (header in listOf("Account", "While you're away", "Voice", "Reading", "Security")) {
            assertTrue("the group is headed $header", drive.scrollDown(toText = header))
            assertFalse("and not ${header.uppercase()}", drive.reads(header.uppercase()))
        }
        // The groups the ruling took away, with their rows now in the header and in the versions line.
        for (gone in listOf("About", "Notifications", "Timeline", "App lock")) {
            assertFalse("and there is no group called $gone", drive.reads(gone))
        }
    }

    /** English is the default whatever the phone is set to. */
    @Test
    @Config(qualifiers = "zh-rCN-${IPhone.QUALIFIERS}")
    fun interfaceLanguageDefaultsToEnglishOnAChinesePhone() = launch("testInterfaceLanguageDefaultsToEnglishOnAChinesePhone") { app, drive ->
        assertTrue("the list is headed in English on a phone set to Chinese", drive.waitFor(hasText("Sessions"), 20_000))
        assertFalse("and not in the system language", drive.reads("会话"))
        app.attach("47-default-english-on-chinese-phone")
    }

    /**
     * A sentence a store builds goes on saying what it said in the language it was built in. The
     * sentence under a settings row is built where it is drawn and its group holds the language, so
     * it follows the preference on the screen that changes it rather than waiting for the screen to
     * be left and entered again.
     */
    @Test
    fun aRowSentenceFollowsAChangeOfLanguage() = launch("testARowSentenceFollowsAChangeOfLanguage") { app, drive ->
        assertTrue("the Settings tab is there", drive.waitFor(hasTestTag("tab.settings"), 20_000))
        app.tap("tab.settings")

        // The row reads as one element, so it is found by what it says rather than by a tag.
        assertTrue(
            "the resume row explains itself in English to start with",
            drive.waitFor(hasText("a minute after the limit resets", substring = true), 15_000),
        )
        assertTrue("the interface language is a segmented control", drive.scrollDown(toTag = "settings.language"))
        app.tap("settings.language.zh-Hans")

        // Language sits in Reading, below the group the sentence is in, so reaching it scrolled that
        // row away, and a lazy list lets go of what it no longer shows. The screen was never left:
        // the row is scrolled back to.
        var reads = false
        repeat(8) {
            if (!reads) {
                reads = drive.exists(hasText("限制重置一分钟后", substring = true))
                if (!reads) drive.drag((-320).dp)
            }
        }
        assertTrue("and the sentence is in the new language without leaving the screen", reads)
        app.attach("ios-sentence-follows-language")
    }

    /** The resume switch the account owns, with the sentence that says what it does; the demo gateway carries preferences, so it is live. */
    @Test
    fun awayGroupOffersTheResumeSwitch() = launch("testAwayGroupOffersTheResumeSwitch") { app, drive ->
        drive.openSettingsTab()
        assertTrue("the away group holds the resume switch", drive.scrollDown(toTag = "settings.resumeAfterLimit"))
        assertTrue("under a group named for what it is", drive.reads("While you're away"))
        assertTrue(
            "and the sentence under it says what the device will do",
            drive.waitFor(hasText("a minute after the limit resets", substring = true), 10_000),
        )
        assertEquals("the demo account has it on, so the live switch can be read", "1", drive.value("settings.resumeAfterLimit"))
        app.attach("ios-round33-resume-settings")

        // It is the account's, not this phone's: the switch writes through the gateway, and what
        // comes back is what it draws.
        drive.turnOff("settings.resumeAfterLimit")
        // The switch takes one write at a time; the iPhone's next tap comes after the round trip.
        app.await("the gateway has the change") { !app.model.preferences.isWriting }
        drive.turnOn("settings.resumeAfterLimit")
    }

    /**
     * `docs/DESIGN.md` § "Settings are the account's, not the device's": a preference turned on
     * somewhere else moves the control in place while the screen is open. The demo's other device
     * turns dictation polish on a few seconds after Settings opens; nothing here is touched but the
     * scroll.
     */
    @Test
    fun aPreferenceChangedElsewhereMovesTheSwitchInPlace() =
        launch("testAPreferenceChangedElsewhereMovesTheSwitchInPlace", "--demo-preference-change") { app, drive ->
            drive.openSettingsTab()
            assertTrue("the Voice group holds the polish switch", drive.scrollDown(toTag = "settings.polish"))
            assertEquals("which this account has off to start with", "0", drive.value("settings.polish"))
            assertFalse("so the rows under it are not there either", drive.exists("settings.polishStrength"))

            // The account's other device turns it on. No tap, no reload.
            app.await("the switch follows the account without being touched", 30_000) { drive.value("settings.polish") == "1" }
            assertTrue("and the rows the switch reveals arrive with it", drive.waitFor(hasTestTag("settings.polishModel"), 10_000))
            assertTrue("both of them", drive.exists("settings.polishStrength"))
            app.attach("ios-round49-settings-sync")
        }

    /** The Voice group offers polish where the gateway has a model, off on a fresh install. */
    @Test
    fun voiceSettingsOfferPolish() = launch("testVoiceSettingsOfferPolish") { app, drive ->
        app.waitFor("tab.settings")
        app.tap("tab.settings")
        assertTrue("the Voice group offers dictation polish", drive.scrollDown(toTag = "settings.polish"))
        assertTrue("the demo gateway has a model, so the switch is live", drive.isEnabled("settings.polish"))
        assertEquals("off on a fresh install", "0", drive.value("settings.polish"))
        assertFalse("with nothing under it until it is on", drive.exists("settings.polishModel"))

        drive.turnOn("settings.polish")
        assertTrue("turning it on offers the gateway's models", drive.waitFor(hasTestTag("settings.polishModel"), 10_000))
        assertTrue("and how hard the model may work", drive.exists("settings.polishStrength"))
        app.attach("ios-polish-settings")
    }

    /** The screen opens on who is signed in and where, and closes on one line of versions. */
    @Test
    fun settingsHeaderAndVersionsLineNameTheAccountAndTheBuild() = launch("testSettingsHeaderAndVersionsLineNameTheAccountAndTheBuild") { app, drive ->
        app.waitFor("tab.settings")
        app.tap("tab.settings")
        app.waitFor("settings.identity", 15_000)
        // The connection settles a moment after the screen is up.
        app.await("the header reads the connection") { drive.label("settings.identity").contains(L10n.string("Connected")) }
        for (part in listOf("admin", "Admin", "Demo", "Connected")) {
            assertTrue("the header reads $part to anyone who cannot see the dot", drive.label("settings.identity").contains(part))
        }
        app.attach("ios-round43-settings")

        assertTrue("the versions line closes the screen", drive.scrollDown(toTag = "settings.versions"))
        assertTrue("and names this build", drive.label("settings.versions").contains(AppBuild.shipped))
        assertTrue("and the protocol both ends speak", drive.label("settings.versions").contains("v${RemoteProtocol.version}"))
        assertTrue("with Diagnostics beside it", drive.exists("settings.diagnostics"))
        app.attach("ios-round43-settings-2")
    }
}
