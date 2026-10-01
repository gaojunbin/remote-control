package com.junbingao.remotecontrol.android.system

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.widestLine
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.strings.L10n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Two buttons side by side or stacked, decided on the words the app's alerts carry: the iPhone
 * stacks Delete account over Cancel and sets every other pair side by side
 * (`79-users-delete-confirm`, `70-device-revoke-confirm`, `62-device-update-confirm`,
 * `61-session-close-dialog`), and every pair in Chinese fits.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = IPhone.QUALIFIERS)
class AlertButtonsLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun englishAgain() = L10n.use(L10n.english)

    private val pairs = listOf("Delete account", "Revoke device", "Update", "Save", "Set password", "Close", "Sign out")

    private fun sideBySide(language: String): Map<String, Boolean> {
        L10n.use(language)
        val decided = mutableMapOf<String, Boolean>()
        compose.setContent {
            CompositionLocalProvider(LocalSafeArea provides SafeArea.iPhone17) {
                for (title in pairs) {
                    decided[title] = AlertMetrics.sideBySide(widestLine(listOf(L10n.string("Cancel"), L10n.string(title)), SystemFont.body))
                }
            }
        }
        compose.waitForIdle()
        return decided
    }

    @Test
    fun englishStacksOnlyDeleteAccount() {
        assertEquals(pairs.associateWith { it != "Delete account" }, sideBySide(L10n.english))
    }

    @Test
    fun chineseSetsEveryPairSideBySide() {
        assertEquals(pairs.associateWith { true }, sideBySide(L10n.chinese))
    }
}
