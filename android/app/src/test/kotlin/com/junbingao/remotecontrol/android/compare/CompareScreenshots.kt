package com.junbingao.remotecontrol.android.compare

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.junbingao.remotecontrol.android.harness.IPhoneFrame
import com.junbingao.remotecontrol.android.harness.IPhoneScreenshotTest
import com.junbingao.remotecontrol.android.harness.ShellAt
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.harness.capture
import com.junbingao.remotecontrol.android.harness.picture
import com.junbingao.remotecontrol.android.harness.swipeOpen
import com.junbingao.remotecontrol.android.shell.AppModel
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
        ShellAt(AppModel.Tab.devices) { if (it is TabRoot) DevicesReplica() }
    }

    @Test
    fun devicesChinese() = compose.picture("compare", "devices", chinese) {
        ShellAt(AppModel.Tab.devices) { if (it is TabRoot) DevicesReplica() }
    }

    @Test
    fun devicesDark() = compose.picture("compare", "devices", Variant(L10n.english, dark = true)) {
        ShellAt(AppModel.Tab.devices) { if (it is TabRoot) DevicesReplica() }
    }

    @Test
    fun revokeAlert() = compose.picture("compare", "revoke-alert", english) {
        ShellAt(AppModel.Tab.devices) { if (it is TabRoot) DevicesReplica(revoking = true) }
    }

    @Test
    fun addDeviceSheet() = compose.picture("compare", "add-device-sheet", english) {
        ShellAt(AppModel.Tab.devices) { if (it is TabRoot) DevicesReplica(adding = true) }
    }

    @Test
    fun users() = compose.picture("compare", "users", english) {
        ShellAt(AppModel.Tab.settings, path = listOf("users")) { if (it == "users") UsersReplica() }
    }

    @Test
    fun deleteAlert() = compose.picture("compare", "delete-alert", english) {
        ShellAt(AppModel.Tab.settings, path = listOf("users")) { if (it == "users") UsersReplica(deleting = true) }
    }

    @Test
    fun settings() = compose.picture("compare", "settings", english) {
        ShellAt(AppModel.Tab.settings) { if (it is TabRoot) SettingsReplica() }
    }

    @Test
    fun chatBar() = compose.picture("compare", "chat-bar", english) {
        ShellAt(AppModel.Tab.sessions, path = listOf("chat")) { if (it == "chat") ChatBarReplica() }
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
        compose.capture("compare", name, english)
    }

    @Test
    fun swipe() {
        L10n.use(L10n.english)
        compose.setContent {
            IPhoneFrame(english) { ShellAt(AppModel.Tab.devices) { if (it is TabRoot) DevicesReplica() } }
        }
        compose.onNodeWithTag("device.mac-studio-office").performTouchInput { swipeOpen() }
        compose.waitForIdle()
        compose.capture("compare", "swipe", english)
    }
}
