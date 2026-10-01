package com.junbingao.remotecontrol.android.screens.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.harness.DemoApp
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.screens.shell.Driving
import com.junbingao.remotecontrol.android.screens.shell.Phone
import com.junbingao.remotecontrol.core.state.AppBuild
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Diagnostics, from the versions line: the report this app writes, which names the app and the
 * build and none of the account's own values, and in a debug build the way in to the primitives'
 * gallery, which the Settings stack then shows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class DiagnosticsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun phoneWithAScreenLock() = Phone.hasAScreenLock()

    @Test
    fun theReportIsReadBeforeItIsSharedAndTheGalleryIsBehindIt() = DemoApp(compose, "diagnostics").use { app ->
        val drive = Driving(compose, app)
        drive.openSettingsTab()
        assertTrue(drive.scrollDown(toTag = "settings.diagnostics"))
        app.tap("settings.diagnostics")
        assertTrue("the sheet shows the report", drive.waitFor(hasTestTag("diagnostics.report"), 10_000))
        val report = app.node("diagnostics.report").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            .orEmpty().joinToString("") { it.text }
        assertTrue("the report names the app that wrote it", report.startsWith("Remote Control for Android"))
        assertTrue("and this build", report.contains("App: ${AppBuild.version}"))
        assertTrue("on this platform", report.contains("Platform: Android"))
        assertFalse("and never the account", report.contains("admin"))
        assertTrue("it can be shared", drive.exists("diagnostics.share"))

        app.tap("diagnostics.gallery")
        assertTrue("a debug build opens the gallery on the Settings stack", drive.waitFor(hasTestTag("gallery.type"), 10_000))
        assertTrue("and the sheet has gone", drive.waitForAbsence(hasTestTag("diagnostics.report"), 10_000))
        app.back()
        assertTrue("Back returns to Settings", drive.waitFor(hasTestTag("settings.list"), 10_000))
    }
}
