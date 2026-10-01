package com.junbingao.remotecontrol.win.settings

import androidx.compose.runtime.mutableStateOf
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.state.ConnectionPhase
import com.junbingao.remotecontrol.core.state.DotTone
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.app.LaunchOptions
import com.junbingao.remotecontrol.win.app.ModelHarness
import com.junbingao.remotecontrol.win.app.signIn
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `web/tests/SettingsPage.test.tsx`'s rules that live outside a view: the header's dot and host, and what the password dialog says. */
class SettingsHeaderTests {
    @Test
    fun theDotTakesItsToneFromTheSocketAlone() {
        // The web's `open` is a socket up, whether or not its hello has landed.
        assertEquals(DotTone.working, IdentityDot.tone(ConnectionPhase.Connected))
        assertEquals(DotTone.working, IdentityDot.tone(ConnectionPhase.Syncing))
        assertEquals(DotTone.waiting, IdentityDot.tone(ConnectionPhase.Connecting))
        assertEquals(DotTone.waiting, IdentityDot.tone(ConnectionPhase.Reconnecting))
        assertEquals(DotTone.off, IdentityDot.tone(ConnectionPhase.SignedOut))
        // The ruling's red, which a browser never reaches and this app does.
        assertEquals(DotTone.failed, IdentityDot.tone(ConnectionPhase.Superseded))
        assertEquals(DotTone.failed, IdentityDot.tone(ConnectionPhase.Incompatible(gatewayVersion = 2)))
        assertEquals(DotTone.failed, IdentityDot.tone(ConnectionPhase.Expired))
    }

    @Test
    fun aLongHostLosesItsMiddleAndKeepsItsPort() {
        assertEquals("rc.example." to "com:8443", MiddleTruncatedHost.split("rc.example.com:8443"))
        assertEquals("127.0." to "0.1:5173", MiddleTruncatedHost.split("127.0.0.1:5173"))
        assertEquals("" to "demo", MiddleTruncatedHost.split("demo"))
    }

    @Test
    fun aDialogIsOpenExactlyWhileItHoldsAForm() {
        val form = mutableStateOf<ChangePasswordForm?>(null)
        assertFalse(form.settingsModalOpen)
        form.value = ChangePasswordForm()
        assertTrue(form.settingsModalOpen)
        form.settingsModalOpen = false
        assertNull(form.value)
    }
}

class SettingsWordsTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    @Test
    fun theDotsWordIsItsLabelAlone() {
        assertEquals("Connected", IdentityDot.word(ConnectionPhase.Connected))
        assertEquals("Connecting", IdentityDot.word(ConnectionPhase.Reconnecting))
        assertEquals("Offline", IdentityDot.word(ConnectionPhase.SignedOut))
        assertEquals("Refused", IdentityDot.word(ConnectionPhase.Superseded))
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        assertEquals("已连接", IdentityDot.word(ConnectionPhase.Connected))
        assertEquals("已拒绝", IdentityDot.word(ConnectionPhase.Superseded))
    }

    @Test
    fun aPasswordRefusalIsWordedFromItsStatus() {
        assertEquals("That is not your current password.", ChangePasswordForm.errorText(TransportError.Unauthorized))
        assertEquals(S.account.notAllowed, ChangePasswordForm.errorText(TransportError.Http(status = 403, code = "forbidden")))
        assertEquals(S.account.rules, ChangePasswordForm.errorText(TransportError.Http(status = 400, code = "bad_request")))
        assertEquals(S.errors.generic, ChangePasswordForm.errorText(TransportError.Http(status = 500, code = null)))
        assertEquals(S.errors.generic, ChangePasswordForm.errorText(SocketTimeoutException()))
    }

    @Test
    fun thePasswordDialogWaitsForBothFields() {
        val form = ChangePasswordForm()
        assertFalse(form.ready)
        form.current = "old"
        form.next = "short"
        assertFalse(form.ready)
        form.next = "long enough"
        assertTrue(form.ready)
        form.current = ""
        assertFalse(form.ready)
    }

    @Test
    fun aMemberChangesItsOwnPasswordAndIsToldWhenTheOldOneIsWrong() = ModelHarness(LaunchOptions(demoAccount = true, ephemeral = true)).use { harness ->
        harness.run {
            restoreOrPrompt()
            signIn(origin = "https://demo.remote-control.invalid", username = DemoFixtures.memberUsername, password = "devdevdev")
            assertTrue(isSignedIn && !connection.isAdmin)
            val refused = ChangePasswordForm()
            refused.current = "short"
            refused.next = "another-password"
            assertFalse(refused.submit(on = connection))
            assertEquals("That is not your current password.", refused.error)
            assertFalse(refused.busy)
            val taken = ChangePasswordForm()
            taken.current = "devdevdev"
            taken.next = "another-password"
            assertTrue(taken.submit(on = connection))
            signOut()
        }
    }
}
