package com.junbingao.remotecontrol.android.compare

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.github.takahirom.roborazzi.captureRoboImage
import com.junbingao.remotecontrol.android.harness.IPhoneFrame
import com.junbingao.remotecontrol.android.harness.IPhoneScreenshotTest
import com.junbingao.remotecontrol.android.harness.ShellAt
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.harness.picture
import com.junbingao.remotecontrol.android.shell.AppTab
import com.junbingao.remotecontrol.android.shell.TabRoot
import com.junbingao.remotecontrol.android.strings.L10n
import org.junit.Test

/**
 * The replicas at the iPhone's size, for laying beside the iPhone's own pictures of the same
 * screens (`<scratchpad>/ref/ios`): the bars, the lists, the switch, the alert, the sheet and the
 * swipe, each where the iPhone puts it.
 */
class CompareScreenshots : IPhoneScreenshotTest() {
    private val english = Variant(L10n.english, dark = false)
    private val chinese = Variant(L10n.chinese, dark = false)

    @Test
    fun devices() = compose.picture("compare", "devices", english) {
        ShellAt(AppTab.devices) { if (it is TabRoot) DevicesReplica() }
    }

    @Test
    fun devicesChinese() = compose.picture("compare", "devices", chinese) {
        ShellAt(AppTab.devices) { if (it is TabRoot) DevicesReplica() }
    }

    @Test
    fun devicesDark() = compose.picture("compare", "devices", Variant(L10n.english, dark = true)) {
        ShellAt(AppTab.devices) { if (it is TabRoot) DevicesReplica() }
    }

    @Test
    fun revokeAlert() = compose.picture("compare", "revoke-alert", english) {
        ShellAt(AppTab.devices) { if (it is TabRoot) DevicesReplica(revoking = true) }
    }

    @Test
    fun addDeviceSheet() = compose.picture("compare", "add-device-sheet", english) {
        ShellAt(AppTab.devices) { if (it is TabRoot) DevicesReplica(adding = true) }
    }

    @Test
    fun users() = compose.picture("compare", "users", english) {
        ShellAt(AppTab.settings, path = listOf("users")) { if (it == "users") UsersReplica() }
    }

    @Test
    fun deleteAlert() = compose.picture("compare", "delete-alert", english) {
        ShellAt(AppTab.settings, path = listOf("users")) { if (it == "users") UsersReplica(deleting = true) }
    }

    @Test
    fun settings() = compose.picture("compare", "settings", english) {
        ShellAt(AppTab.settings) { if (it is TabRoot) SettingsReplica() }
    }

    @Test
    fun chatBar() = compose.picture("compare", "chat-bar", english) {
        ShellAt(AppTab.sessions, path = listOf("chat")) { if (it == "chat") ChatBarReplica() }
    }

    @Test
    fun archiveSearch() = compose.picture("compare", "archive-search", english) { ArchiveSearchReplica() }

    @Test
    fun attachMenu() = menu("composer.attach", "attach-menu")

    @Test
    fun languageMenu() = menu("composer.language", "language-menu")

    @Test
    fun permissionMenu() = menu("composer.permissions", "permission-menu")

    private fun menu(tag: String, name: String) {
        L10n.use(L10n.english)
        compose.setContent { IPhoneFrame(english) { ComposerMenuReplica() } }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/compare/$name-${english.suffix}.png")
    }

    @Test
    fun swipe() {
        L10n.use(L10n.english)
        compose.setContent {
            IPhoneFrame(english) { ShellAt(AppTab.devices) { if (it is TabRoot) DevicesReplica() } }
        }
        compose.onNodeWithTag("device.mac-studio-office").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/compare/swipe-${english.suffix}.png")
    }
}
