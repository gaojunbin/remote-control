import XCTest
import UIKit

/// A smoke test against the offline demo. It never reaches the network and
/// never touches the microphone: dictation is driven by a scripted platform
/// that only exists behind an explicit launch argument.
final class RemoteControlUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--demo", "--reset-state", "--voice-preview"]
    }

    func testSessionsAndChat() {
        app.launch()

        // The sessions list paints from the demo hello.
        let sessions = app.staticTexts["Sessions"]
        XCTAssertTrue(sessions.waitForExistence(timeout: 20), "the sessions screen appears")

        let summary = app.staticTexts["sessions.summary"]
        XCTAssertTrue(summary.waitForExistence(timeout: 10), "the device summary is shown")

        let newSession = app.buttons["sessions.new"]
        XCTAssertTrue(newSession.exists, "the new-session button is prominent and labelled")

        attach(name: "01-sessions")

        // Opening a session shows the transcript and the composer.
        let row = app.buttons["session.demo-session-auth"]
        XCTAssertTrue(row.waitForExistence(timeout: 10), "the live demo session is listed")
        row.tap()

        let composer = app.textViews["composer.prompt"].firstMatch
        let composerField = composer.exists ? composer : app.textFields["composer.prompt"].firstMatch
        XCTAssertTrue(composerField.waitForExistence(timeout: 15), "the composer is on screen")
        XCTAssertTrue(app.buttons["composer.send"].exists, "the send button is a separate control")

        attach(name: "02-chat")

        // A message goes out and the draft is cleared.
        composerField.tap()
        composerField.typeText("run the suite again")
        let send = app.buttons["composer.send"]
        XCTAssertTrue(send.isEnabled, "send is enabled once there is a draft")
        send.tap()

        attach(name: "03-sent")
    }

    /// Amendment A12: the message is on screen the moment Send is tapped, under
    /// the request id the device will echo, and the device's own event replaces
    /// it in place rather than adding a second copy.
    ///
    /// The scripted device takes three seconds over its echo under
    /// `--ui-testing`, so the state between the two is looked at rather than
    /// raced; nothing about the app's own timing depends on that.
    func testSentMessageAppearsBeforeTheDeviceConfirmsIt() {
        app.launch()

        let row = app.buttons["session.demo-session-toolchain"]
        XCTAssertTrue(scrollDown(to: row), "a session on a reachable machine with no turn running")
        row.tap()

        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the composer is on screen")
        field.tap()
        field.typeText("and one more thing")

        let sending = app.descendants(matching: .any)["chat.message.sending"]
        app.buttons["composer.send"].tap()

        // Not "eventually": the bubble is drawn from the app's own state, so it
        // is there before the device has been heard from at all.
        XCTAssertTrue(sending.waitForExistence(timeout: 2),
                      "the message is in the transcript while the device is still being asked")
        XCTAssertTrue(sending.label.contains("and one more thing"), "with the words that were typed")
        XCTAssertTrue(sending.label.contains("sending"), "and a word saying it is on its way")
        XCTAssertEqual(promptField().value as? String ?? "", "",
                       "the field is clear, so the next message can be typed at once")
        attach(name: "40-send-pending")

        // The device's event arrives under the same id and takes the row over.
        XCTAssertTrue(sending.waitForNonExistence(timeout: 15), "the echo retires the pending state")
        let confirmed = app.descendants(matching: .any)["chat.message"]
        XCTAssertTrue(confirmed.waitForExistence(timeout: 10), "and the message is still there")
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "chat.message")
            .allElementsBoundByIndex.filter { $0.label.contains("and one more thing") }.count, 1,
                       "exactly once: the echo replaced the bubble rather than adding one")
        attach(name: "41-send-confirmed")
    }

    /// The field owns a row of its own and grows with the draft; everything
    /// else — the icons, the session's chips and Send — shares the one row
    /// underneath it.
    func testComposerFieldOwnsItsRowAndGrows() {
        app.launch()
        openLiveSession()

        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the message field is on screen")
        let attachments = app.buttons["composer.attach"]
        let voice = app.buttons["composer.voice"]
        let model = app.buttons["composer.model"]
        let send = app.buttons["composer.send"]
        XCTAssertTrue(attachments.exists, "attachments are on the row below the field")
        XCTAssertTrue(voice.exists, "and so is dictation")
        XCTAssertTrue(model.exists, "and so are the session's chips")
        XCTAssertTrue(app.buttons["composer.permissions"].exists)
        XCTAssertTrue(send.exists, "with Send at the other end of that row")
        XCTAssertGreaterThan(field.frame.width, attachments.frame.width * 4,
                             "the field takes the whole width rather than sharing it")
        XCTAssertGreaterThan(attachments.frame.minY, field.frame.maxY - 1,
                             "the controls sit under the field, not beside it")
        XCTAssertLessThan(attachments.frame.minX, model.frame.minX,
                          "the icons lead, then the chips")
        XCTAssertLessThan(model.frame.maxX, send.frame.minX,
                          "and Send is pinned past all of them at the trailing edge")

        // One row means one row: nothing the composer draws sits below Send.
        for control in [attachments, voice, model, send] {
            XCTAssertLessThan(abs(control.frame.midY - send.frame.midY), 12,
                              "every control shares the one row under the field")
        }
        XCTAssertLessThan(app.frame.maxX - send.frame.maxX, 24,
                          "Send sits against the trailing margin, not floating inside the row")

        attach(name: "20-composer-one-line")

        // SwiftUI swaps the field's element type once it can wrap, so both
        // samples are taken with the keyboard up and the text view in place.
        field.tap()
        field.typeText("one")
        let oneLine = promptField().frame.height
        field.typeText("\ntwo\nthree\nfour\nfive")
        let grown = promptField()
        XCTAssertTrue(grown.waitForExistence(timeout: 10))
        attach(name: "21-composer-five-lines")
        XCTAssertEqual((grown.value as? String ?? "").filter(\.isNewline).count, 4,
                       "Return inserts a newline rather than sending")
        // `frame` is re-queried on every access, so each height is captured
        // before the next keystroke changes it.
        let fiveLines = grown.frame.height
        XCTAssertGreaterThan(fiveLines, oneLine * 2, "five lines of draft make the field grow")

        // Eight lines is where growing stops and the text scrolls inside.
        grown.typeText("\nsix\nseven\neight")
        let eightLines = promptField().frame.height
        XCTAssertGreaterThan(eightLines, fiveLines, "it is still growing at eight")
        promptField().typeText("\nnine\nten\neleven\ntwelve")
        XCTAssertEqual(promptField().frame.height, eightLines, accuracy: 1,
                       "past the cap the field stops growing and scrolls instead")
        attach(name: "24-composer-capped")
    }

    /// `docs/DESIGN.md` § "The composer": dictation offers one button, Done. It
    /// stands where Send stands, keeps the transcript in the message field, and
    /// leaves sending to the ordinary Send button. There is no Cancel: a
    /// dictation nobody wants is edited or cleared like any other draft.
    func testDictationOffersOnlyDoneAndFillsTheField() {
        app.launch()
        openLiveSession()
        XCTAssertTrue(promptField().waitForExistence(timeout: 15))

        let send = app.buttons["composer.send"]
        let sendFrame = send.frame
        app.buttons["composer.voice"].tap()

        let done = app.buttons["voice.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 15), "Done stops listening")
        XCTAssertTrue(waitFor { done.isEnabled }, "and is live once the microphone is")
        XCTAssertFalse(app.buttons["voice.cancel"].exists, "and it is the only way out")
        XCTAssertFalse(app.buttons["voice.stop"].exists, "nothing stops and sends in one tap")
        XCTAssertFalse(send.exists, "Send is not offered while listening")
        XCTAssertFalse(app.buttons["composer.attach"].exists, "and neither is anything else")
        XCTAssertFalse(app.buttons["composer.voice"].exists)
        XCTAssertFalse(app.buttons["composer.model"].exists,
                       "the chips go with the row dictation replaced")
        XCTAssertTrue(app.descendants(matching: .any)["voice.status"].exists,
                      "one quiet line says what dictation is doing")

        // Done takes the slot and the height Send had: it is the one primary in
        // the row while listening.
        XCTAssertLessThan(abs(done.frame.maxX - sendFrame.maxX), 2,
                          "Done stands where Send stands, against the trailing edge")
        XCTAssertLessThan(abs(done.frame.midY - sendFrame.midY), 12, "on the same row")
        XCTAssertEqual(done.frame.height, sendFrame.height, accuracy: 2, "at Send's size")
        attach(name: "22-voice-listening")

        done.tap()
        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        let dictated = field.value as? String ?? ""
        XCTAssertTrue(dictated.contains("auth suite"),
                      "the transcript lands in the message field, not in a panel")
        XCTAssertTrue(app.buttons["composer.send"].isEnabled,
                      "and sending it is the ordinary, separate tap")
        attach(name: "23-voice-done")

        // A second dictation adds to the draft rather than replacing it, and
        // ending it is the same one button.
        app.buttons["composer.voice"].tap()
        let again = app.buttons["voice.done"]
        XCTAssertTrue(again.waitForExistence(timeout: 15))
        XCTAssertTrue(waitFor { again.isEnabled })
        again.tap()
        let kept = promptField()
        XCTAssertTrue(kept.waitForExistence(timeout: 15))
        XCTAssertTrue((kept.value as? String ?? "").hasPrefix(dictated),
                      "the draft the second dictation started from is still there")
    }

    // MARK: - Reading position and the keyboard

    /// A tap that lands anywhere but the message field puts the keyboard away,
    /// and the draft it was holding survives.
    func testTappingOutsideTheFieldPutsTheKeyboardAway() {
        app.launch()
        openLiveSession()

        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        field.tap()
        field.typeText("half a thought")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10), "the keyboard is up")
        attach(name: "25-keyboard-up")

        // A quarter of the way down the transcript is content, not a control.
        transcript().coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).tap()
        XCTAssertTrue(waitForNoKeyboard(), "a tap outside the field puts the keyboard away")
        XCTAssertEqual(promptField().value as? String, "half a thought",
                       "and the draft it was holding survives")
        attach(name: "26-keyboard-dismissed")
    }

    /// A tap on a control still acts on the first tap, keyboard up or not: the
    /// gesture that dismisses the keyboard cancels no touches.
    func testToolCardOpensOnTheFirstTapWhileTheKeyboardIsUp() {
        app.launch()
        // Simple is the reading default and draws no tool call at all, so the
        // card this is about exists only at Detailed.
        chooseDetailedTranscript()
        openLiveSession()
        XCTAssertTrue(promptField().waitForExistence(timeout: 15))
        XCTAssertTrue(waitForLiveTurn(), "the scripted turn finishes and the transcript settles")

        promptField().tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10), "the keyboard is up")

        let card = app.buttons["chat.tool.tool-5"]
        XCTAssertTrue(card.waitForExistence(timeout: 10), "the last tool call is on screen")
        card.tap()
        XCTAssertTrue(text(containing: "100 passed in 52.4s").waitForExistence(timeout: 10),
                      "one tap opened the card rather than only dismissing the keyboard")
        attach(name: "29-tool-card-keyboard-up")
    }

    /// Scrolling away from the foot of the transcript offers the way back down,
    /// and taking it returns to the newest message.
    func testJumpToLatestAppearsWhenTheReaderLeavesTheBottom() {
        app.launch()
        // At Simple the demo transcript is shorter than the screen and there is
        // nowhere to scroll away to; Detailed is the level with a tail.
        chooseDetailedTranscript()
        openLiveSession()
        XCTAssertTrue(promptField().waitForExistence(timeout: 15))
        XCTAssertTrue(waitForLiveTurn(), "the scripted turn finishes and the transcript settles")

        let jump = app.buttons["chat.jumpToLatest"]
        XCTAssertFalse(jump.exists, "a conversation that opens at its newest message offers nothing")

        let view = transcript()
        view.swipeDown()
        view.swipeDown()
        XCTAssertTrue(jump.waitForExistence(timeout: 10), "leaving the bottom offers the way back")
        XCTAssertEqual(jump.label, "Jump to latest")
        attach(name: "27-jump-to-latest")

        jump.tap()
        XCTAssertTrue(jump.waitForNonExistence(timeout: 10),
                      "tapping it returns to the bottom and takes the button with it")
        XCTAssertTrue(text(containing: "source of the flake").firstMatch.isHittable,
                      "and the newest message is what is on screen")
        attach(name: "28-jump-tapped")
    }

    private func sessionList() -> XCUIElement {
        let list = app.collectionViews.firstMatch
        return list.exists ? list : app.scrollViews.firstMatch
    }

    private func transcript() -> XCUIElement {
        let view = app.scrollViews["chat.transcript"]
        return view.exists ? view : app.scrollViews.firstMatch
    }

    /// The demo plays one scripted turn on opening the live session. Waiting
    /// for its last words keeps a scroll assertion off a moving transcript.
    private func waitForLiveTurn(timeout: TimeInterval = 30) -> Bool {
        text(containing: "source of the flake").firstMatch.waitForExistence(timeout: timeout)
    }

    private func text(containing fragment: String) -> XCUIElement {
        app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", fragment)).firstMatch
    }

    private func waitForNoKeyboard(timeout: TimeInterval = 10) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if app.keyboards.count == 0 { return true }
            usleep(200_000)
        }
        return app.keyboards.count == 0
    }

    private func openLiveSession() {
        let row = app.buttons["session.demo-session-auth"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the live demo session is listed")
        row.tap()
    }

    /// Thinking, tool calls and the task list are drawn at Detailed and nowhere
    /// else, and Simple is what a fresh install reads at. A test about any of
    /// them changes the preference through the control that owns it.
    private func chooseDetailedTranscript() {
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 20), "the Settings tab is there")
        settings.tap()

        let picker = app.descendants(matching: .any)["settings.timelineDetail"]
        XCTAssertTrue(scrollDown(to: picker), "the timeline detail preference is in Settings")
        picker.tap()
        let detailed = app.buttons["Detailed"].exists
            ? app.buttons["Detailed"] : app.staticTexts["Detailed"]
        XCTAssertTrue(detailed.waitForExistence(timeout: 10), "and offers Detailed")
        detailed.tap()

        app.tabBars.buttons["Sessions"].tap()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 15),
                      "and the list is back")
    }

    /// Polls a condition the runner has no expectation for, such as a control
    /// becoming enabled without its identifier or label changing.
    private func waitFor(timeout: TimeInterval = 10, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            usleep(200_000)
        }
        return condition()
    }

    /// SwiftUI renders a growing field as a text view once it wraps, so the
    /// identifier is looked up in both collections.
    private func promptField() -> XCUIElement {
        let view = app.textViews["composer.prompt"].firstMatch
        return view.exists ? view : app.textFields["composer.prompt"].firstMatch
    }

    /// Amendment A10: an attached terminal session takes a message from here and
    /// its relayed permission request is answered from the app. Amendment A19:
    /// while the device holds the message it is a queue entry and nothing else,
    /// and the bubble appears when the CLI takes it.
    func testSharedSessionDeliversAndApproves() {
        app.launch()
        openSharedSession()

        // Amendment A20: the attached CLI asks a question the moment this opens,
        // and the composer answers it rather than sending anything.
        answerTheSharedQuestion(with: "Remote control for your terminal agents")

        let composerField = promptField()
        XCTAssertTrue(composerField.waitForExistence(timeout: 15),
                      "the composer is enabled on an attached session")
        XCTAssertFalse(app.buttons["chat.takeover"].exists,
                       "an attached session never offers a takeover")

        // The header already says the terminal owns this session, so the
        // composer adds nothing: a control this attachment cannot drive is
        // absent rather than dimmed under a caption explaining why.
        XCTAssertFalse(app.descendants(matching: .any)["composer.terminalNote"].exists,
                       "nothing is printed above the field")
        XCTAssertFalse(app.buttons["composer.attach"].exists,
                       "a channel cannot hand bytes to a live CLI, so there is no attach button")
        XCTAssertFalse(app.buttons["composer.model"].exists,
                       "and the settings live in the terminal, so no picker is offered")
        XCTAssertFalse(app.buttons["composer.permissions"].exists)
        XCTAssertTrue(app.buttons["composer.send"].exists, "what is left still sends")

        // Amendment A17: the values themselves are shown where the pickers
        // would be, so the phone can say which model that terminal is running.
        for field in ["model", "permissionMode", "effort"] {
            XCTAssertTrue(app.descendants(matching: .any)["composer.readonly.\(field)"].exists,
                          "the \(field) the terminal chose is shown")
        }

        attach(name: "06-shared-idle")

        composerField.tap()
        composerField.typeText("mention the iOS app too")
        app.buttons["composer.send"].tap()

        // Amendment A19: nothing is drawn for a message the device is only
        // holding. It is one entry in the queue until the CLI takes it.
        let queue = app.buttons["composer.queue"]
        XCTAssertTrue(queue.waitForExistence(timeout: 10), "the held message is in the queue")
        XCTAssertTrue(queue.label.contains("1"), "exactly one of them")
        XCTAssertFalse(app.descendants(matching: .any)["chat.message.sending"].exists,
                       "and no bubble claims it reached the terminal")
        attach(name: "07-shared-queued")

        let delivered = app.descendants(matching: .any)["chat.message.delivered"]
        XCTAssertTrue(delivered.waitForExistence(timeout: 15),
                      "the block arrives when the CLI takes the message")
        XCTAssertTrue(delivered.label.contains("mention the iOS app too"), "with what was typed")
        XCTAssertTrue(queue.waitForNonExistence(timeout: 10), "and leaves the queue")
        attach(name: "08-shared-delivered")

        let approval = app.buttons["approval.primary"]
        XCTAssertTrue(approval.waitForExistence(timeout: 15), "the relayed request is answerable here")
        attach(name: "09-shared-approval")
        approval.tap()
        XCTAssertTrue(approval.waitForNonExistence(timeout: 15), "answering resolves the request")
        attach(name: "10-shared-answered")
    }

    /// Amendment A20: the terminal shows Claude's own dialog and this card at
    /// the same moment, and whichever is answered first wins. The message field
    /// is the free-text answer, the button reads Answer, and an earlier question
    /// the terminal answered says so.
    func testQuestionIsAnsweredFromTheComposer() {
        app.launch()
        openSharedSession()

        // The question the person at the terminal got to first is in the
        // transcript, naming where its answer came from.
        let resolved = app.descendants(matching: .any)["question.resolution"]
        XCTAssertTrue(resolved.waitForExistence(timeout: 15),
                      "a question answered elsewhere says so")
        XCTAssertEqual(resolved.label, "answered in the terminal")
        attach(name: "32-question-answered-in-terminal")

        // The live one turns the composer into an answer.
        let card = app.descendants(matching: .any)["chat.question"]
        XCTAssertTrue(card.waitForExistence(timeout: 15), "the attached CLI asks its question here too")
        let send = app.buttons["composer.send"]
        XCTAssertTrue(waitFor { send.label == "Answer" },
                      "and the one primary in the row answers it rather than sending")
        XCTAssertEqual(app.descendants(matching: .any)["chat.status"].label,
                       "Waiting for your answer",
                       "with the status line saying what is expected")

        let field = promptField()
        field.tap()
        field.typeText("Remote control for your terminal agents")
        attach(name: "33-composer-answer")
        send.tap()

        XCTAssertTrue(waitFor(timeout: 15) { send.label == "Send" },
                      "answering gives the composer back")
        XCTAssertEqual(promptField().value as? String ?? "", "",
                       "and the draft went with the answer")
        XCTAssertTrue(app.buttons["question.submit"].waitForNonExistence(timeout: 10),
                      "the card is no longer answerable")
        attach(name: "34-question-answered-here")
    }

    private func openSharedSession() {
        let row = app.buttons["session.demo-session-shared"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the attached demo session is listed")
        row.tap()
    }

    /// The demo's attached session asks a question shortly after it opens, and
    /// the composer is an answer until it is dealt with.
    private func answerTheSharedQuestion(with text: String) {
        let send = app.buttons["composer.send"]
        XCTAssertTrue(waitFor(timeout: 20) { send.label == "Answer" },
                      "the attached CLI asks a question when the session opens")
        let field = promptField()
        field.tap()
        field.typeText(text)
        send.tap()
        XCTAssertTrue(waitFor(timeout: 15) { send.label == "Send" },
                      "and answering it gives the composer back")
    }

    /// Amendment A11: a Codex thread shared through the app-server daemon. The
    /// attachment carries the settings, the attachments and an interrupt, so
    /// nothing is dimmed, and the daemon's four decisions all reach the card.
    func testSharedCodexSessionKeepsEveryControl() {
        app.launch()

        let row = app.buttons["session.demo-session-typecheck"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the shared Codex thread is listed")
        row.tap()

        let composer = app.textViews["composer.prompt"].firstMatch
        let composerField = composer.exists ? composer : app.textFields["composer.prompt"].firstMatch
        XCTAssertTrue(composerField.waitForExistence(timeout: 15), "the composer is enabled")

        XCTAssertFalse(app.descendants(matching: .any)["composer.terminalNote"].exists,
                       "the header names the attachment; the composer repeats nothing")
        XCTAssertTrue(app.buttons["composer.attach"].exists,
                      "shared_attachments keeps the attach button")
        XCTAssertTrue(app.buttons["composer.model"].exists, "and shared_settings keeps the chips")
        XCTAssertTrue(app.buttons["composer.effort"].exists,
                      "effort among them, because the daemon retunes the live thread")
        XCTAssertFalse(app.descendants(matching: .any)["composer.readonly.model"].exists,
                       "and A17 shows nothing where the picker is live")
        XCTAssertTrue(app.buttons["chat.stop"].exists, "shared_interrupt offers Stop while it runs")
        // The field's placeholder is its accessibility name: UIKit has no
        // placeholder on a text view for the runner to read as one.
        XCTAssertEqual(composerField.label, "Message · will steer the turn",
                       "a steering agent joins the running turn instead of queueing behind it")

        // The daemon's four decisions all render, stacked, with Allow primary.
        let allow = app.buttons["approval.primary"]
        XCTAssertTrue(allow.waitForExistence(timeout: 15), "the request is answerable here")
        XCTAssertTrue(app.buttons["Allow for this session"].exists, "the session-wide option is offered")
        XCTAssertTrue(app.buttons["Always allow commands like this"].exists,
                      "and the execpolicy amendment")
        XCTAssertTrue(app.buttons["approval.danger"].exists, "with Deny kept apart from Allow")
        attach(name: "12-codex-shared")

        // The settings sheet opens, because the daemon retunes the live thread.
        app.buttons["composer.model"].tap()
        let picker = app.descendants(matching: .any)["session.model"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10),
                      "shared_settings reopens the session settings sheet")
        attach(name: "13-codex-settings")
        app.buttons["Done"].firstMatch.tap()

        XCTAssertTrue(allow.waitForExistence(timeout: 10), "the card is still there after the sheet")
        allow.tap()
        XCTAssertTrue(allow.waitForNonExistence(timeout: 15), "answering resolves the request")
        attach(name: "14-codex-answered")
    }

    /// Amendment A10: a terminal session the device cannot attach says what the
    /// machine is missing instead of pretending the composer will work.
    func testTerminalSessionExplainsHowToAttach() {
        app.launch()

        let row = app.buttons["session.demo-session-rename"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the unattachable terminal session is listed")
        row.tap()

        let hint = app.descendants(matching: .any)["chat.attachHint"]
        XCTAssertTrue(hint.waitForExistence(timeout: 15), "the session says how to make it controllable")
        attach(name: "11-attach-hint")
    }

    /// Every tone a status dot can take is on the sessions list at once: a turn
    /// under way, a session blocked on the user, one that is alive and quiet,
    /// one whose agent stopped on an error, and one nothing owns any more on a
    /// machine that is no longer there.
    func testSessionsListShowsEveryStatusTone() {
        app.launch()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20))

        // The grey one sits in the Archive of the machine whose CLI exited. That
        // Archive's open or closed state is remembered per machine, so this
        // opens it only if it is shut and leaves it as it found it.
        let archive = app.buttons["sessions.archive.demo-ci-runner"]
        let archived = app.buttons["session.demo-session-otlp"]
        var opened = false
        if !scrollDown(to: archived) {
            XCTAssertTrue(scrollDown(to: archive), "the offline machine carries its own Archive")
            archive.tap()
            opened = true
            XCTAssertTrue(scrollDown(to: archived),
                          "the list carries a session nothing owns, on a machine that is gone")
        }
        XCTAssertTrue(app.buttons["session.demo-session-toolchain"].exists,
                      "and one whose agent stopped on an error")

        // The machine in between holds nothing this is about, and the five
        // together are taller than the screen, so its group is folded away for
        // the duration and put back at the end.
        let middle = app.buttons["sessions.device.demo-macbook-air"]
        XCTAssertTrue(scrollDown(to: middle), "the middle machine has a header to fold")
        middle.tap()
        XCTAssertTrue(app.buttons["session.demo-session-rename"].waitForNonExistence(timeout: 10),
                      "one tap folds it away")

        for _ in 0..<8 { app.swipeDown() }
        for (id, what) in [("demo-session-vite", "a request waiting for the user"),
                           ("demo-session-auth", "a running turn"),
                           ("demo-session-shared", "an attached session that is quiet")] {
            XCTAssertTrue(app.buttons["session.\(id)"].waitForExistence(timeout: 10),
                          "the list carries \(what)")
        }

        // One measured drag to lift the first row towards the top of the list,
        // then short ones until every row is reachable. The five rows and the
        // two strips above and below them — the connection banner and the
        // device summary — are together taller than this screen, so the old pair
        // of pixel margins could not both hold once the rows grew; what "in
        // frame together" means is that the reader can see and reach all five,
        // and a fling would land anywhere.
        let list = sessionList()
        let tones = [("demo-session-vite", "amber, waiting on the user"),
                     ("demo-session-auth", "blue, a turn running"),
                     ("demo-session-shared", "quiet, attached to a terminal"),
                     ("demo-session-toolchain", "red, stopped on an error"),
                     ("demo-session-otlp", "grey, owned by nothing")]
        let rows = tones.map { app.buttons["session.\($0.0)"] }
        drag(list, by: rows[0].frame.minY - list.frame.minY - 94)
        for _ in 0..<6 where !rows.allSatisfy({ $0.isHittable }) { drag(list, by: 24) }

        for (row, tone) in zip(rows, tones) {
            XCTAssertTrue(row.isHittable, "the \(tone.1) row is in frame with the rest")
        }
        attach(name: "30-status-tones")

        XCTAssertTrue(scrollDown(to: middle), "the folded machine is still there")
        middle.tap()
        if opened {
            XCTAssertTrue(scrollDown(to: archive), "the Archive header is still reachable")
            archive.tap()
        }
    }

    /// A machine's finished sessions sit in its own collapsed Archive, and a
    /// search reaches inside it without opening it by hand.
    func testDeviceArchiveOpensOnTapAndOnSearch() {
        app.launch()

        let archived = app.buttons["session.demo-session-otlp"]
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20))
        XCTAssertFalse(archived.exists, "a session whose CLI exited is not among the live rows")

        // The list is lazy, so the group has to be scrolled into view first.
        let archive = app.buttons["sessions.archive.demo-ci-runner"]
        XCTAssertTrue(scrollDown(to: archive), "the machine carries its own Archive")
        XCTAssertTrue(archive.label.contains("1"), "and its header counts what is inside")

        archive.tap()
        XCTAssertTrue(scrollDown(to: archived), "one tap opens it")
        // The list ends here, so one more swipe settles it at the foot with the
        // whole Archive in view.
        app.swipeUp()
        attach(name: "15-archive-open")

        XCTAssertTrue(scrollDown(to: archive), "the header is still reachable")
        archive.tap()
        XCTAssertTrue(archived.waitForNonExistence(timeout: 10), "and another closes it")

        // A search finds it wherever it is, without a second tap. The search
        // bar hides itself while the list is scrolled away from the top.
        for _ in 0..<6 where !app.searchFields.firstMatch.exists { app.swipeDown() }
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the list is searchable")
        field.tap()
        field.typeText("OTLP")
        XCTAssertTrue(archived.waitForExistence(timeout: 10),
                      "a match inside the Archive opens it on its own")
        attach(name: "16-archive-search")
    }

    /// A device header folds its whole group away and brings it back.
    func testDeviceGroupCollapses() {
        app.launch()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20))

        let header = app.buttons["sessions.device.demo-mac-studio"]
        XCTAssertTrue(scrollDown(to: header), "the busiest machine heads the list")
        let row = app.buttons["session.demo-session-auth"]
        XCTAssertTrue(row.exists, "with its live sessions under it")

        header.tap()
        XCTAssertTrue(row.waitForNonExistence(timeout: 10), "one tap folds the machine away")
        attach(name: "17-device-collapsed")

        XCTAssertTrue(scrollDown(to: header), "the header stays put")
        header.tap()
        XCTAssertTrue(row.waitForExistence(timeout: 10), "and another brings the group back")
    }

    /// The agent filter narrows the list to one agent and drops any machine
    /// left with nothing to show.
    func testAgentFilterNarrowsTheListToOneAgent() {
        app.launch()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20))

        let claudeRow = app.buttons["session.demo-session-auth"]
        let codexRow = app.buttons["session.demo-session-vite"]
        XCTAssertTrue(claudeRow.waitForExistence(timeout: 10), "both agents are listed to start with")
        XCTAssertTrue(codexRow.exists)

        app.buttons["sessions.agentFilter"].tap()
        let codexChoice = app.buttons["sessions.agentFilter.codex"]
        XCTAssertTrue(codexChoice.waitForExistence(timeout: 10), "the filter names the agents present")
        codexChoice.tap()

        XCTAssertTrue(claudeRow.waitForNonExistence(timeout: 10), "the other agent's rows go")
        XCTAssertTrue(codexRow.exists, "the chosen agent's rows stay")
        XCTAssertFalse(app.buttons["sessions.device.demo-macbook-air"].exists,
                       "and a machine left with nothing disappears with them")
        attach(name: "18-agent-filter")

        app.buttons["sessions.agentFilter"].tap()
        app.buttons["sessions.agentFilter.all"].tap()
        XCTAssertTrue(claudeRow.waitForExistence(timeout: 10), "All brings the rest back")
    }

    /// Moves a scrolling view up by a measured distance, slowly enough that it
    /// stops where it was put rather than carrying on under its own momentum.
    private func drag(_ view: XCUIElement, by distance: CGFloat) {
        guard distance > 0 else { return }
        let start = view.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: view.frame.width / 2, dy: view.frame.height * 0.6))
        start.press(forDuration: 0.2, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -distance)),
                    withVelocity: .slow, thenHoldForDuration: 0.4)
    }

    /// Scrolls the list until the element is on screen and can be tapped, so a
    /// lazy row at the foot of the page is never a matter of swipe distance.
    private func scrollDown(to element: XCUIElement, swipes: Int = 6) -> Bool {
        for _ in 0..<swipes {
            if element.exists && element.isHittable { return true }
            app.swipeUp()
        }
        return element.exists && element.isHittable
    }

    func testNewSessionSheetOffersDeviceAndAgent() {
        app.launch()
        let newSession = app.buttons["sessions.new"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 20))
        newSession.tap()

        XCTAssertTrue(app.buttons["newsession.start"].waitForExistence(timeout: 10),
                      "the sheet offers an explicit start action")
        XCTAssertTrue(app.otherElements["newsession.agent"].exists || app.segmentedControls.firstMatch.exists,
                      "an agent picker is present")
        XCTAssertFalse(app.textViews["newsession.prompt"].exists,
                       "and the sheet no longer asks for a first message")
        attach(name: "04-new-session")
    }

    func testDevicesTabShowsPairingSheet() {
        app.launch()
        let devices = app.tabBars.buttons["Devices"]
        XCTAssertTrue(devices.waitForExistence(timeout: 20))
        devices.tap()

        let add = app.buttons["devices.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), "adding a device is a labelled button")
        add.tap()

        XCTAssertTrue(app.staticTexts["pairing.command"].waitForExistence(timeout: 10),
                      "the install one-liner is shown")
        XCTAssertTrue(app.buttons["pairing.copy"].exists, "the command can be copied")
        attach(name: "05-add-device")
    }

    /// `docs/DESIGN.md` § "Surfaces, rows and controls": nothing is re-cased.
    /// A re-cased header carries the transformed text in its accessibility
    /// label, so reading the label reads what is really on the screen.
    func testSettingsSectionHeadersAreSentenceCase() {
        app.launch()
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 20))
        settings.tap()

        XCTAssertTrue(app.staticTexts["Signed in as"].waitForExistence(timeout: 15),
                      "the account rows are drawn")
        attach(name: "30-settings-headers")

        for header in ["Account", "Notifications", "Voice", "Timeline"] {
            XCTAssertTrue(app.staticTexts[header].exists, "the section is headed \(header)")
            XCTAssertFalse(app.staticTexts[header.uppercased()].exists,
                           "and not \(header.uppercased())")
        }
    }

    /// `docs/DESIGN.md` § "The composer": once the field scrolls, the system
    /// scroll indicator runs down its trailing edge while the draft is being
    /// scrolled. The field's fill is flat, so a dark pixel in the strip along
    /// that edge is the indicator and nothing else.
    func testComposerFieldShowsItsScrollIndicator() {
        app.launch()
        openLiveSession()

        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the message field is on screen")
        field.tap()
        // Nine breaks puts the draft past `ComposerLayout.maximumLines`, so the
        // field has certainly stopped growing and moved its text instead.
        field.typeText("one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten")
        let capped = promptField()
        XCTAssertTrue(capped.waitForExistence(timeout: 10))
        let cappedHeight = capped.frame.height

        // Typing leaves the draft at its end, so the scroll that has somewhere
        // to go is the one back towards the first line.
        capped.swipeDown(velocity: .slow)
        let shot = XCUIScreen.main.screenshot()
        attach(name: "31-composer-scroll-indicator", screenshot: shot)
        XCTAssertEqual(promptField().frame.height, cappedHeight, accuracy: 1,
                       "scrolling the draft does not resize the field")
        let edge = capped.frame
        let strip = CGRect(x: edge.maxX - 12, y: edge.minY + 4, width: 11, height: edge.height - 8)
        XCTAssertLessThan(darkest(in: strip, of: shot), 0.9,
                          "the scroll indicator runs down the field's trailing edge")
    }

    private func attach(name: String, screenshot: XCUIScreenshot? = nil) {
        let attachment = XCTAttachment(screenshot: screenshot ?? XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// The darkest pixel in a rectangle of the screen, as a fraction of white.
    /// Returns white when the rectangle cannot be read, so a broken sample
    /// fails the assertion it feeds rather than passing it.
    private func darkest(in rect: CGRect, of screenshot: XCUIScreenshot) -> CGFloat {
        guard let screen = screenshot.image.cgImage, app.frame.width > 0 else { return 1 }
        let scale = CGFloat(screen.width) / app.frame.width
        let box = CGRect(x: rect.minX * scale, y: rect.minY * scale,
                         width: rect.width * scale, height: rect.height * scale)
        guard let crop = screen.cropping(to: box), crop.width > 0, crop.height > 0 else { return 1 }
        var pixels = [UInt8](repeating: 0, count: crop.width * crop.height * 4)
        guard let context = CGContext(data: &pixels, width: crop.width, height: crop.height,
                                      bitsPerComponent: 8, bytesPerRow: crop.width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return 1 }
        context.draw(crop, in: CGRect(x: 0, y: 0, width: crop.width, height: crop.height))
        return stride(from: 0, to: pixels.count, by: 4).reduce(CGFloat(1)) { darkest, index in
            let luma = (0.3 * CGFloat(pixels[index]) + 0.6 * CGFloat(pixels[index + 1])
                        + 0.1 * CGFloat(pixels[index + 2])) / 255
            return min(darkest, luma)
        }
    }
}


