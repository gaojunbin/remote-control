package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.screens.shell.Driving
import com.junbingao.remotecontrol.android.screens.shell.ForeignScreen
import com.junbingao.remotecontrol.android.screens.shell.Phone
import com.junbingao.remotecontrol.android.strings.L10n
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
 * The two tests that cross from Settings into the conversation: the whole interface in Chinese, and
 * Transcribe deciding whether a dictation language is offered, here and in the composer (A44).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class SettingsLanguageUITest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun phoneWithAScreenLock() = Phone.hasAScreenLock()

    /** The whole interface in Chinese: the list, the settings, the composer. Nothing the device reported is translated with it. */
    @Test
    fun chineseInterfaceIsUsedEverywhere() = DemoApp(
        compose,
        "testChineseInterfaceIsUsedEverywhere",
        DemoApp.launchArguments + "--language=zh-Hans",
        variant = Variant(L10n.chinese, dark = false),
    ).use { app ->
        val drive = Driving(compose, app)
        assertTrue("the list is headed 会话", drive.waitFor(hasText("会话"), 20_000))
        assertFalse("and no longer in English", drive.reads("Sessions"))
        app.attach("48-sessions-chinese")

        app.tap("tab.settings")
        assertTrue("Settings is headed 账户", drive.waitFor(hasText("账户"), 15_000))
        app.attach("49-settings-chinese")
        for (header in listOf("你不在时", "语音", "阅读", "安全")) {
            assertTrue("the section is headed $header", drive.scrollDown(toText = header))
        }
        app.attach("50-settings-language-chinese")

        ForeignScreen.requiresTheComposer()
        app.tap("tab.sessions")
        openLiveSession(app)
        assertTrue("the composer is on screen", drive.waitFor(hasTestTag("composer.prompt"), 15_000))
        assertTrue("the send button names itself in Chinese", drive.reads("发送") || drive.label("composer.send").contains("发送"))
        assertTrue("the model card is still there", drive.exists("composer.modelCard"))
        assertEquals(
            "and the device's own model and effort labels are not translated",
            "Sonnet 4.5，思考强度 High",
            drive.value("composer.modelCard"),
        )
        assertEquals("while the language is named in the app's own words", "中文", drive.value("composer.language"))
        app.attach("51-chat-chinese")
    }

    /**
     * Amendment A44: the phone's recogniser is told a language and the gateway's provider detects
     * one, so a language is offered exactly where the phone listens — in Settings and in the
     * composer alike — and there it is the recogniser's list, Chinese first, with no Automatic.
     */
    @Test
    fun transcribeDecidesWhetherALanguageIsOffered() = DemoApp(compose, "testTranscribeDecidesWhetherALanguageIsOffered").use { app ->
        val drive = Driving(compose, app)
        drive.openSettingsTab()
        assertTrue("the Voice group starts with Transcribe", drive.scrollDown(toTag = "settings.voiceBackend"))
        assertTrue(
            "a fresh install transcribes on this phone, so a language is offered",
            drive.waitFor(hasTestTag("settings.voiceLanguage"), 10_000),
        )
        assertTrue("Chinese until another is picked", drive.label("settings.voiceLanguage").contains("Chinese"))
        app.attach("ios-a44-settings-on-phone")

        app.tap("settings.voiceBackend")
        assertTrue("Transcribe offers the gateway", drive.waitFor(hasText("Gateway"), 10_000))
        compose.onNode(hasText("Gateway"), useUnmergedTree = true).performClick()
        assertTrue("whose provider detects the language, so the row goes", drive.waitForAbsence(hasTestTag("settings.voiceLanguage"), 10_000))
        app.await("and Transcribe says why in its own sentence") {
            drive.label("settings.voiceBackend").contains("which recognises the language itself")
        }
        app.attach("ios-a44-settings-gateway")

        ForeignScreen.requiresTheComposer()
        app.tap("tab.sessions")
        openLiveSession(app)
        assertTrue("the composer is up", drive.waitFor(hasTestTag("composer.modelCard"), 15_000))
        assertFalse("with no language beside the microphone either", drive.exists("composer.language"))
        app.attach("ios-a44-composer-gateway")

        app.back()
        drive.openSettingsTab()
        assertTrue("Transcribe is where it was", drive.scrollDown(toTag = "settings.voiceBackend"))
        app.tap("settings.voiceBackend")
        val phone = L10n.string("On this iPhone")
        assertTrue(drive.waitFor(hasText(phone), 10_000))
        compose.onNode(hasText(phone), useUnmergedTree = true).performClick()
        assertTrue("back on the phone, the language is back", drive.waitFor(hasTestTag("settings.voiceLanguage"), 10_000))

        app.tap("tab.sessions")
        openLiveSession(app)
        assertTrue("and in the composer", drive.waitFor(hasTestTag("composer.language"), 15_000))
        assertEquals("Dictation language", drive.label("composer.language"))
        assertEquals("Chinese", drive.value("composer.language"))
        assertTrue("before the model card", drive.frame("composer.language").center.x < drive.frame("composer.modelCard").center.x)
        app.attach("ios-a44-composer-on-phone")
        app.tap("composer.language")
        for (name in listOf("Chinese", "English", "Japanese", "German", "French", "Spanish")) {
            assertTrue("the recogniser offers $name", drive.waitFor(hasText(name), 5_000))
        }
        assertFalse("and nothing it cannot do", drive.reads("Automatic"))
        app.attach("ios-a44-language-menu")
        compose.onNode(hasText("English"), useUnmergedTree = true).performClick()
        app.await("a language picked is drawn at once") { drive.value("composer.language") == "English" }
    }

    /** `openLiveSession()`: the live demo session, from the list. */
    private fun openLiveSession(app: DemoApp) {
        app.waitFor("session.demo-session-auth", 20_000)
        app.tap("session.demo-session-auth")
    }
}
