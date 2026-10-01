package com.junbingao.remotecontrol.android.launch

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.core.transport.SessionLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The iPhone's launch arguments, as `adb shell am start --esa args …` hands them over. */
@RunWith(AndroidJUnit4::class)
class LaunchOptionsTest {
    @Test
    fun anArrayExtraCarriesTheArguments() {
        val intent = Intent().putExtra(LaunchOptions.EXTRA, arrayOf("--demo", "--reset-state", "--language=zh-Hans"))
        val options = LaunchOptions.from(intent, debug = true)
        assertTrue(options.demo)
        assertTrue(options.resetState)
        assertEquals("zh-Hans", options.language)
        assertFalse(options.uiTesting)
    }

    @Test
    fun aStringExtraIsSplitOnSpacesAndCommas() {
        val options = LaunchOptions.from(Intent().putExtra(LaunchOptions.EXTRA, "--demo,--demo-queue --ui-testing"), debug = true)
        assertEquals(listOf("--demo", "--demo-queue", "--ui-testing"), options.arguments)
        assertTrue(options.demoQueue)
    }

    @Test
    fun noExtraIsNoArguments() {
        assertEquals(emptyList<String>(), LaunchOptions.from(null, debug = true).arguments)
        assertEquals(emptyList<String>(), LaunchOptions.from(Intent(), debug = true).arguments)
    }

    @Test
    fun everyIPhoneArgumentHasItsMeaning() {
        val all = LaunchOptions(
            listOf(
                "--demo", "--demo-account", "--demo-queue", "--demo-update-required", "--demo-preference-change",
                "--registration-open", "--reset-state", "--ui-testing", "--voice-preview", "--field-scroll-probe",
            ),
            debug = true,
        )
        assertTrue(all.demo && all.demoAccount && all.demoQueue && all.demoUpdateRequired && all.demoPreferenceChange)
        assertTrue(all.registrationOpen && all.resetState && all.uiTesting && all.voicePreview && all.fieldScrollProbe)
    }

    @Test
    fun aReleaseNeverTakesScriptedSpeechOrTheProbe() {
        val release = LaunchOptions(listOf("--voice-preview", "--field-scroll-probe", "--voice-level=0.5", "--gallery"), debug = false)
        assertFalse(release.voicePreview)
        assertFalse(release.fieldScrollProbe)
        assertNull(release.voiceLevel)
        assertFalse(release.gallery)
    }

    @Test
    fun theVoiceLevelIsANumberBetweenZeroAndOne() {
        assertEquals(0.5, LaunchOptions(listOf("--voice-level=0.5"), debug = true).voiceLevel!!, 1e-9)
        assertEquals(1.0, LaunchOptions(listOf("--voice-level=7"), debug = true).voiceLevel!!, 1e-9)
        assertNull(LaunchOptions(listOf("--voice-level=loud"), debug = true).voiceLevel)
        assertNull(LaunchOptions(listOf("--voice-level=NaN"), debug = true).voiceLevel)
        assertEquals("long", LaunchOptions(listOf("--voice-transcript=long"), debug = true).voiceTranscript)
    }

    @Test
    fun anIntentsDataIsTheConversationItAsksFor() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("remotecontrol://session?device=mac-studio-office&id=sess-01"))
        assertEquals(SessionLink(deviceID = "mac-studio-office", sessionID = "sess-01"), LaunchOptions.link(intent))
        assertNull(LaunchOptions.link(Intent()))
    }

    @Test
    fun aLinkTheAppWritesIsTheOneItReads() {
        val link = SessionLink(deviceID = "dev/1", sessionID = "a b&c")
        assertEquals(link, LaunchOptions.link(Intent(Intent.ACTION_VIEW, Uri.parse(link.url.toString()))))
    }

    @Test
    fun anythingElseIsNoLink() {
        for (other in listOf("https://session?device=a&id=b", "remotecontrol://device?device=a&id=b",
                             "remotecontrol://session?device=a", "remotecontrol://session?device=&id=b", "not a uri at all")) {
            assertNull(other, LaunchOptions.link(Intent(Intent.ACTION_VIEW, Uri.parse(other))))
        }
    }
}
