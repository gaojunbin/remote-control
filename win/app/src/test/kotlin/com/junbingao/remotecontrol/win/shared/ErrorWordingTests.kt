package com.junbingao.remotecontrol.win.shared

import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.state.DotTone
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.transport.TransportError
import com.junbingao.remotecontrol.win.login.LoginErrorText
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import org.junit.jupiter.api.BeforeEach
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Mac's `ErrorWordingTests`, case for case. */
class ErrorWordingTests {
    @BeforeEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    @Test
    fun aReplyCodeDecidesTheSentence() {
        assertEquals("That device is offline.", ErrorText.text(GatewayErrorBody(code = GatewayErrorCode.deviceOffline, message = "x")))
        assertEquals("Name is too long", ErrorText.text(GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "Name is too long")))
        assertEquals("F", ErrorText.text(GatewayErrorBody(code = GatewayErrorCode.internalError, message = "internal"), fallback = "F"))
        assertEquals("The device did not answer in time.", ErrorText.text(TransportError.RequestTimedOut))
        assertEquals("F", ErrorText.text(TransportError.DeliveryUncertain, fallback = "F"))
    }

    @Test
    fun aConflictFromTheDeviceKeepsItsOwnWords() {
        val typing = GatewayErrorBody(code = GatewayErrorCode.conflict, message = "Somebody is typing in the terminal")
        assertEquals("Somebody is typing in the terminal", ErrorText.refusal(typing, fallback = "F"))
        assertEquals(S.errors.conflictTerminal, ErrorText.text(typing))
    }

    /** `web/tests/shared-control.test.tsx`, A40: a busy terminal in the device's own words, every other code in the app's. */
    @Test
    fun aBusyTerminalIsShownInTheDevicesOwnWords() {
        val busy = "the terminal is busy; try again in a moment"
        assertEquals(busy, ErrorText.refusal(GatewayErrorBody(code = GatewayErrorCode.conflict, message = busy), fallback = S.errors.setFailed))
        assertEquals(S.errors.timeout, ErrorText.refusal(GatewayErrorBody(code = GatewayErrorCode.timeout, message = "gone"), fallback = "fallback"))
        // A conflict with nothing to say still gets one.
        assertEquals(S.errors.conflictTerminal, ErrorText.refusal(GatewayErrorBody(code = GatewayErrorCode.conflict, message = ""), fallback = "fallback"))
    }

    /** `web/tests/shared-control.test.tsx`, A42: Stop refused over an open prompt. */
    @Test
    fun anOpenPromptKeepsTheDevicesWordsRatherThanTheCannedOnes() {
        val refused = GatewayErrorBody(code = GatewayErrorCode.conflict, message = "answer the prompt first")
        assertEquals("answer the prompt first", ErrorText.refusal(refused, fallback = S.errors.stopFailed))
        assertNotEquals(S.errors.conflictTerminal, ErrorText.refusal(refused, fallback = S.errors.stopFailed))
    }

    @Test
    fun aQueuedMessageAlreadyGoneSaysSo() {
        assertEquals(S.composer.alreadySent, ErrorText.queueRemove(GatewayErrorBody(code = GatewayErrorCode.notFound, message = "")))
        assertNotEquals(S.errors.notFound, ErrorText.queueRemove(GatewayErrorBody(code = GatewayErrorCode.notFound, message = "")))
        assertEquals(S.errors.queueRemoveFailed, ErrorText.queueRemove(TransportError.DeliveryUncertain))
    }

    /** `web/tests/queued-edit.test.tsx`: every other refusal keeps its own words. */
    @Test
    fun everyOtherQueueRefusalKeepsItsOwnWords() {
        assertEquals(S.errors.deviceOffline, ErrorText.queueRemove(GatewayErrorBody(code = GatewayErrorCode.deviceOffline, message = "")))
        assertEquals(S.errors.queueRemoveFailed, ErrorText.queueRemove(GatewayErrorBody(code = GatewayErrorCode.internalError, message = "")))
    }

    @Test
    fun accountRoutesAreWordedFromTheirCode() {
        val taken = "That username is taken."
        assertEquals(taken, AccountErrors.userErrorText(TransportError.Http(status = 409, code = "conflict"), conflict = taken))
        assertEquals(S.account.rules, AccountErrors.userErrorText(TransportError.Http(status = 400, code = "bad_request"), conflict = taken))
        assertEquals(S.account.gone, AccountErrors.userErrorText(TransportError.Http(status = 404, code = "not_found"), conflict = taken))
        assertEquals(S.errors.generic, AccountErrors.userErrorText(TransportError.NotConnected, conflict = taken))
    }

    @Test
    fun signInRefusalsReadAsTheWebsDo() {
        assertEquals("Wrong username or password.", LoginErrorText.signIn(TransportError.Unauthorized))
        assertEquals("This account is disabled.", LoginErrorText.signIn(TransportError.Http(status = 403, code = "forbidden")))
        assertEquals(S.login.rateLimited, LoginErrorText.signIn(TransportError.Http(status = 429, code = null)))
        assertEquals("Cannot reach the gateway.", LoginErrorText.signIn(ConnectException("Connection refused")))
        assertEquals("That username is taken.", LoginErrorText.register(TransportError.Http(status = 409, code = "conflict")))
        assertEquals(S.account.rules, LoginErrorText.register(TransportError.Http(status = 400, code = "bad_request")))
        assertEquals("Registration is closed.", LoginErrorText.register(TransportError.Http(status = 403, code = "forbidden")))
        assertTrue(LoginErrorText.closesRegistration(TransportError.Http(status = 403, code = "forbidden")))
    }
}

/** `web/tests/dotTone.test.ts`: the whole table, state by control, online and off. */
class DotToneTests {
    private val table: Map<String, Map<String, DotTone>> = mapOf(
        "starting" to mapOf("remote" to DotTone.working, "terminal" to DotTone.working, "shared" to DotTone.working, "none" to DotTone.working),
        "running" to mapOf("remote" to DotTone.working, "terminal" to DotTone.working, "shared" to DotTone.working, "none" to DotTone.working),
        "needs_approval" to mapOf("remote" to DotTone.waiting, "terminal" to DotTone.waiting, "shared" to DotTone.waiting, "none" to DotTone.waiting),
        "needs_input" to mapOf("remote" to DotTone.waiting, "terminal" to DotTone.waiting, "shared" to DotTone.waiting, "none" to DotTone.waiting),
        "idle" to mapOf("remote" to DotTone.live, "terminal" to DotTone.live, "shared" to DotTone.live, "none" to DotTone.off),
        "readonly" to mapOf("remote" to DotTone.live, "terminal" to DotTone.live, "shared" to DotTone.live, "none" to DotTone.off),
        "stopped" to mapOf("remote" to DotTone.off, "terminal" to DotTone.off, "shared" to DotTone.off, "none" to DotTone.off),
        "error" to mapOf("remote" to DotTone.failed, "terminal" to DotTone.failed, "shared" to DotTone.failed, "none" to DotTone.failed),
    )

    @Test
    fun everyStateAndControlOnAnOnlineDevice() {
        for ((state, controls) in table) {
            for ((control, tone) in controls) {
                assertEquals(tone, DotTone.of(state = SessionState(state), control = SessionControl(control), online = true), "$state/$control")
                assertEquals(DotTone.off, DotTone.of(state = SessionState(state), control = SessionControl(control), online = false))
            }
        }
    }
}
