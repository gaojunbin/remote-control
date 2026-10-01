package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.ui.input.key.Key
import com.junbingao.remotecontrol.core.demo.DemoFixtures
import com.junbingao.remotecontrol.core.protocol.Session
import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.core.protocol.SessionState
import com.junbingao.remotecontrol.core.protocol.SharedSetting
import com.junbingao.remotecontrol.core.state.L10n
import com.junbingao.remotecontrol.core.transport.STTEvent
import com.junbingao.remotecontrol.win.shared.Attach
import com.junbingao.remotecontrol.win.shared.LabelPair
import com.junbingao.remotecontrol.win.voice.GatewaySpeechStream
import com.junbingao.remotecontrol.win.voice.SpeechEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Composer.test.tsx`'s keyboard cases and `CommandMenu.test.tsx`'s: Enter sends, Shift+Enter breaks
 * the line, the Enter that confirms an input method's composition is the input method's, and while
 * the slash panel is open the arrows, Tab, Enter and Esc belong to it.
 */
class ComposerKeysTests {
    private fun action(key: ComposerKey, shift: Boolean = false, marked: Boolean = false, panel: ComposerKeys.Panel? = null) =
        ComposerKeys.action(key, shift = shift, hasMarkedText = marked, panel = panel)

    @Test
    fun enterSendsAndShiftEnterBreaksTheLine() {
        assertEquals(ComposerKeyAction.Submit, action(ComposerKey.enter))
        assertEquals(ComposerKeyAction.Pass, action(ComposerKey.enter, shift = true))
    }

    @Test
    fun theEnterThatConfirmsACompositionIsTheInputMethods() {
        assertEquals(ComposerKeyAction.Pass, action(ComposerKey.enter, marked = true))
        val panel = ComposerKeys.Panel(rows = 3, highlight = 0, takingChangesField = true)
        for (key in listOf(ComposerKey.enter, ComposerKey.tab, ComposerKey.up, ComposerKey.down, ComposerKey.escape)) {
            assertEquals(ComposerKeyAction.Pass, action(key, marked = true, panel = panel))
        }
    }

    @Test
    fun withoutThePanelTheOtherKeysAreTheFields() {
        for (key in listOf(ComposerKey.up, ComposerKey.down, ComposerKey.escape, ComposerKey.tab)) assertEquals(ComposerKeyAction.Pass, action(key))
    }

    @Test
    fun theArrowsMoveTheHighlightAndWrap() {
        val panel = ComposerKeys.Panel(rows = 3, highlight = 0, takingChangesField = true)
        assertEquals(ComposerKeyAction.Highlight(1), action(ComposerKey.down, panel = panel))
        assertEquals(ComposerKeyAction.Highlight(2), action(ComposerKey.up, panel = panel))
        assertEquals(ComposerKeyAction.Highlight(0), action(ComposerKey.down, panel = ComposerKeys.Panel(rows = 3, highlight = 2, takingChangesField = true)))
    }

    @Test
    fun tabTakesTheRowAndEscPutsThePanelAway() {
        val panel = ComposerKeys.Panel(rows = 2, highlight = 1, takingChangesField = true)
        assertEquals(ComposerKeyAction.TakeRow, action(ComposerKey.tab, panel = panel))
        assertEquals(ComposerKeyAction.Pass, action(ComposerKey.tab, shift = true, panel = panel))
        assertEquals(ComposerKeyAction.DismissPanel, action(ComposerKey.escape, panel = panel))
    }

    @Test
    fun enterTakesTheRowAndTheSecondEnterRunsIt() {
        assertEquals(ComposerKeyAction.TakeRow, action(ComposerKey.enter, panel = ComposerKeys.Panel(rows = 2, highlight = 0, takingChangesField = true)))
        assertEquals(ComposerKeyAction.Submit, action(ComposerKey.enter, panel = ComposerKeys.Panel(rows = 2, highlight = 0, takingChangesField = false)))
    }

    @Test
    fun returnAndTheKeypadEnterAreBothEnter() {
        assertEquals(ComposerKey.enter, ComposerKey(Key.Enter))
        assertEquals(ComposerKey.enter, ComposerKey(Key.NumPadEnter))
        assertNull(ComposerKey(Key.A))
    }
}

/** `web/tests/Composer.test.tsx` § the disabled states and the control row, read off the rules the composer draws from. */
class ComposerGatesTests {
    private fun session(state: SessionState = SessionState.idle, control: SessionControl = SessionControl.remote, agent: String = "claude"): Session =
        Session(sessionID = "s", deviceID = "d", agent = agent, title = "t", cwd = "/", state = state, control = control)

    @Test
    fun aTerminalSessionIsDisabledWhateverItsStateSays() {
        val gates = ComposerGates(session(state = SessionState.running, control = SessionControl.terminal), DemoFixtures.claude, deviceOnline = true)
        assertTrue(gates.disabled && gates.terminalControlled)
        assertTrue(SharedSetting.allCases.all { !gates.canSet(it) })
    }

    @Test
    fun anOfflineDeviceDisablesTheComposer() {
        assertTrue(ComposerGates(session(), DemoFixtures.claude, deviceOnline = false).disabled)
    }

    @Test
    fun runningMeansEveryStateATurnIsIn() {
        for (state in listOf(SessionState.running, SessionState.needsApproval, SessionState.needsInput, SessionState.starting)) {
            assertTrue(ComposerGates(session(state = state), null, deviceOnline = true).running)
        }
        assertFalse(ComposerGates(session(state = SessionState.error), null, deviceOnline = true).running)
    }

    @Test
    fun aSharedClaudeSessionTypesTheModelAndEffortOnlyAndTakesNoFiles() {
        val gates = ComposerGates(session(control = SessionControl.shared), DemoFixtures.claude, deviceOnline = true)
        assertFalse(gates.showAttach)
        assertEquals(Attach.canSetShared(DemoFixtures.claude, SharedSetting.model), gates.canSet(SharedSetting.model))
        assertFalse(gates.canSet(SharedSetting.permissionMode))
    }

    @Test
    fun aSharedCodexSessionCarriesEverything() {
        val gates = ComposerGates(session(control = SessionControl.shared, agent = "codex"), DemoFixtures.codex, deviceOnline = true)
        assertTrue(gates.showAttach && gates.canInterrupt)
        assertTrue(SharedSetting.allCases.all(gates::canSet))
    }

    @Test
    fun aSessionTheDeviceDrivesKeepsEveryPicker() {
        val gates = ComposerGates(session(), DemoFixtures.claude, deviceOnline = true)
        assertTrue(SharedSetting.allCases.all(gates::canSet))
        assertTrue(gates.canInterrupt && gates.showAttach)
    }

    @Test
    fun anIdTheAgentDoesNotListIsShownAsItArrived() {
        val modes = DemoFixtures.claude.permissionModes
        assertEquals("Auto-accept edits", ComposerLabels.label(modes, "acceptEdits"))
        assertEquals("auto", ComposerLabels.label(modes, "auto"))
        assertNull(ComposerLabels.label(modes, null))
        assertNull(ComposerLabels.label(modes, ""))
        assertEquals("Opus 4.6 High", ComposerLabels.line(LabelPair(model = "Opus 4.6", effort = "High")))
        assertEquals("Opus 4.6", ComposerLabels.line(LabelPair(model = "Opus 4.6", effort = null)))
    }
}

/** The adapter over the core's socket reports as the web's socket does. */
class VoiceGatewayStreamTests {
    @Test
    fun transcriptsPassThroughAndTheLanguageIsDropped() {
        assertEquals(SpeechEvent.Partial("run it"), GatewaySpeechStream.event(STTEvent.Partial("run it")))
        assertEquals(SpeechEvent.Final("run it"), GatewaySpeechStream.event(STTEvent.Final(text = "run it", language = "en")))
        assertEquals(SpeechEvent.Closed, GatewaySpeechStream.event(STTEvent.Closed))
    }

    @Test
    fun theGatewaysOwnWordsAreKeptAndTheSocketsOwnAreNot() {
        assertEquals(SpeechEvent.Failed("backend unavailable"), GatewaySpeechStream.event(STTEvent.Failed("backend unavailable")))
        assertEquals(SpeechEvent.Failed(null), GatewaySpeechStream.event(STTEvent.Failed("Transcription failed.")))
        assertEquals(SpeechEvent.Failed(null), GatewaySpeechStream.event(STTEvent.Failed(L10n.string("The transcription connection dropped."))))
    }

    /** `useVoice` has the final timeout, and it keeps the words without an error. */
    @Test
    fun theSocketsOwnFinalTimeoutIsAClose() {
        assertEquals(SpeechEvent.Closed, GatewaySpeechStream.event(STTEvent.Failed(L10n.string("The gateway did not return a transcript."))))
    }
}
