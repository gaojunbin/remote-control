import RCCore
import Testing
@testable import RCMac

/// `Composer.test.tsx`'s keyboard cases and `CommandMenu.test.tsx`'s: Enter
/// sends, Shift+Enter breaks the line, the Enter that confirms an input
/// method's composition is the input method's, and while the slash panel is
/// open the arrows, Tab, Enter and Esc belong to it.
@Suite("Composer keys")
struct ComposerKeysTests {
    private func action(_ key: ComposerKey, shift: Bool = false, marked: Bool = false,
                        panel: ComposerKeys.Panel? = nil) -> ComposerKeyAction {
        ComposerKeys.action(for: key, shift: shift, hasMarkedText: marked, panel: panel)
    }

    @Test func enterSendsAndShiftEnterBreaksTheLine() {
        #expect(action(.enter) == .submit)
        #expect(action(.enter, shift: true) == .pass)
    }

    @Test func theEnterThatConfirmsACompositionIsTheInputMethods() {
        #expect(action(.enter, marked: true) == .pass)
        let panel = ComposerKeys.Panel(rows: 3, highlight: 0, takingChangesField: true)
        for key in [ComposerKey.enter, .tab, .up, .down, .escape] {
            #expect(action(key, marked: true, panel: panel) == .pass)
        }
    }

    @Test func withoutThePanelTheOtherKeysAreTheFields() {
        for key in [ComposerKey.up, .down, .escape, .tab] { #expect(action(key) == .pass) }
    }

    @Test func theArrowsMoveTheHighlightAndWrap() {
        let panel = ComposerKeys.Panel(rows: 3, highlight: 0, takingChangesField: true)
        #expect(action(.down, panel: panel) == .highlight(1))
        #expect(action(.up, panel: panel) == .highlight(2))
        #expect(action(.down, panel: .init(rows: 3, highlight: 2, takingChangesField: true)) == .highlight(0))
    }

    @Test func tabTakesTheRowAndEscPutsThePanelAway() {
        let panel = ComposerKeys.Panel(rows: 2, highlight: 1, takingChangesField: true)
        #expect(action(.tab, panel: panel) == .takeRow)
        #expect(action(.tab, shift: true, panel: panel) == .pass)
        #expect(action(.escape, panel: panel) == .dismissPanel)
    }

    @Test func enterTakesTheRowAndTheSecondEnterRunsIt() {
        #expect(action(.enter, panel: .init(rows: 2, highlight: 0, takingChangesField: true)) == .takeRow)
        #expect(action(.enter, panel: .init(rows: 2, highlight: 0, takingChangesField: false)) == .submit)
    }

    @Test func returnAndTheKeypadEnterAreBothEnter() {
        #expect(ComposerKey(keyCode: 36) == .enter)
        #expect(ComposerKey(keyCode: 76) == .enter)
        #expect(ComposerKey(keyCode: 0) == nil)
    }
}

/// `web/tests/Composer.test.tsx` § the disabled states and the control row,
/// read off the rules the composer draws from.
@Suite("Composer gates")
struct ComposerGatesTests {
    private func session(state: SessionState = .idle, control: SessionControl = .remote,
                         agent: String = "claude") -> Session {
        Session(sessionID: "s", deviceID: "d", agent: agent, title: "t", cwd: "/", state: state, control: control)
    }

    @Test func aTerminalSessionIsDisabledWhateverItsStateSays() {
        let gates = ComposerGates(session: session(state: .running, control: .terminal),
                                  agent: DemoFixtures.claude, deviceOnline: true)
        #expect(gates.disabled && gates.terminalControlled)
        #expect(SharedSetting.allCases.allSatisfy { !gates.canSet($0) })
    }

    @Test func anOfflineDeviceDisablesTheComposer() {
        #expect(ComposerGates(session: session(), agent: DemoFixtures.claude, deviceOnline: false).disabled)
    }

    @Test func runningMeansEveryStateATurnIsIn() {
        for state in [SessionState.running, .needsApproval, .needsInput, .starting] {
            #expect(ComposerGates(session: session(state: state), agent: nil, deviceOnline: true).running)
        }
        #expect(!ComposerGates(session: session(state: .error), agent: nil, deviceOnline: true).running)
    }

    @Test func aSharedClaudeSessionTypesTheModelAndEffortOnlyAndTakesNoFiles() {
        let gates = ComposerGates(session: session(control: .shared), agent: DemoFixtures.claude, deviceOnline: true)
        #expect(!gates.showAttach)
        #expect(gates.canSet(.model) == Attach.canSetShared(DemoFixtures.claude, .model))
        #expect(!gates.canSet(.permissionMode))
    }

    @Test func aSharedCodexSessionCarriesEverything() {
        let gates = ComposerGates(session: session(control: .shared, agent: "codex"),
                                  agent: DemoFixtures.codex, deviceOnline: true)
        #expect(gates.showAttach && gates.canInterrupt)
        #expect(SharedSetting.allCases.allSatisfy(gates.canSet))
    }

    @Test func aSessionTheDeviceDrivesKeepsEveryPicker() {
        let gates = ComposerGates(session: session(), agent: DemoFixtures.claude, deviceOnline: true)
        #expect(SharedSetting.allCases.allSatisfy(gates.canSet))
        #expect(gates.canInterrupt && gates.showAttach)
    }

    @Test func anIdTheAgentDoesNotListIsShownAsItArrived() {
        let modes = DemoFixtures.claude.permissionModes
        #expect(ComposerLabels.label(modes, "acceptEdits") == "Auto-accept edits")
        #expect(ComposerLabels.label(modes, "auto") == "auto")
        #expect(ComposerLabels.label(modes, nil) == nil)
        #expect(ComposerLabels.label(modes, "") == nil)
        #expect(ComposerLabels.line(LabelPair(model: "Opus 4.6", effort: "High")) == "Opus 4.6 High")
        #expect(ComposerLabels.line(LabelPair(model: "Opus 4.6", effort: nil)) == "Opus 4.6")
    }
}

/// The adapter over RCCore's socket reports as the web's socket does.
@Suite("Gateway speech stream")
struct VoiceGatewayStreamTests {
    @Test func transcriptsPassThroughAndTheLanguageIsDropped() {
        #expect(GatewaySpeechStream.event(for: .partial("run it")) == .partial("run it"))
        #expect(GatewaySpeechStream.event(for: .final(text: "run it", language: "en")) == .final("run it"))
        #expect(GatewaySpeechStream.event(for: .closed) == .closed)
    }

    @Test func theGatewaysOwnWordsAreKeptAndTheSocketsOwnAreNot() {
        #expect(GatewaySpeechStream.event(for: .failed("backend unavailable")) == .failed("backend unavailable"))
        #expect(GatewaySpeechStream.event(for: .failed("Transcription failed.")) == .failed(nil))
        #expect(GatewaySpeechStream.event(for: .failed(L10n.string("The transcription connection dropped.")))
                == .failed(nil))
    }

    /// `useVoice` has the final timeout, and it keeps the words without an error.
    @Test func theSocketsOwnFinalTimeoutIsAClose() {
        #expect(GatewaySpeechStream.event(for: .failed(L10n.string("The gateway did not return a transcript.")))
                == .closed)
    }
}
