import XCTest
import UIKit
import RCCore

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

        let newSession = app.buttons["sessions.new"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 10),
                      "the new-session button is prominent and labelled")

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
        let model = app.buttons["composer.modelCard"]
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
        XCTAssertFalse(app.buttons["composer.modelCard"].exists,
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
        // Polish is off, so the field holds what will be sent the moment the
        // transcript is final: the slot is Send and nothing is spinning in it.
        XCTAssertFalse(app.descendants(matching: .any)["composer.working"].exists,
                       "with no spinner left in the slot Done stood in")
        XCTAssertFalse(app.buttons["voice.done"].exists, "and no Done either")
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

    /// `docs/DESIGN.md` § "The composer" → **While dictation runs, the field
    /// follows the words**. A dictation longer than the field's eight lines used
    /// to leave it on its first screen until Done, so nothing that had just been
    /// recognised could be read. Now the last line stays in view while the words
    /// arrive, and Done leaves the field where they ended.
    func testALongDictationKeepsItsLastLineInView() {
        app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--demo", "--reset-state", "--voice-preview",
                               "--voice-transcript=long", "--field-scroll-probe"]
        app.launch()
        openLiveSession()
        XCTAssertTrue(promptField().waitForExistence(timeout: 15))

        // What eight lines are worth, typed the way the cap is measured
        // elsewhere and then taken back out, so dictation starts from an empty
        // draft and the height it settles at can be held against this one. The
        // first keystroke is typed by itself and waited for: the rest of a
        // burst sent while the keyboard is still coming up can be dropped, and
        // a baseline one line short would pass for a field that grew too far.
        promptField().tap()
        promptField().typeText("1")
        XCTAssertTrue(waitFor { (promptField().value as? String ?? "") == "1" },
                      "the field has the keyboard and takes what is typed")
        promptField().typeText("\n2\n3\n4\n5\n6\n7\n8")
        XCTAssertEqual((promptField().value as? String ?? "").filter(\.isNewline).count, 7,
                       "eight lines stand in the field")
        let eightLines = promptField().frame.height
        promptField().typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 15))
        XCTAssertTrue(waitFor { (promptField().value as? String ?? "").isEmpty },
                      "the draft is empty again")
        transcript().coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).tap()
        XCTAssertTrue(waitForNoKeyboard(), "and the keyboard is down, as it is for dictation")

        app.buttons["composer.voice"].tap()
        let done = app.buttons["voice.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 15), "dictation is listening")
        XCTAssertTrue(waitFor(timeout: 20) {
            (promptField().value as? String ?? "").contains("tomorrow morning")
        }, "the scripted dictation reaches the draft, every partial of it")

        XCTAssertEqual(promptField().frame.height, eightLines, accuracy: 1,
                       "the field grew to eight lines and no further")
        XCTAssertTrue(waitFor { fieldShowsItsLastLine() },
                      "and it is scrolled to the words that just arrived, not held on the first")
        attach(name: "ios-round31-dictation-follows")

        done.tap()
        XCTAssertTrue(waitFor(timeout: 15) { !done.exists }, "Done ends the dictation")
        XCTAssertTrue(fieldShowsItsLastLine(),
                      "and leaves the field where the words ended")
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

    /// The way back down lands at the very end however far up the reader went.
    /// A `LazyVStack` guesses the height of the rows it has not laid out, so one
    /// scroll aimed at the tail arrives short of it; the jump keeps going until
    /// the geometry says the tail is on screen, and only then does the button go.
    func testJumpToLatestLandsAtTheTailFromFarUp() {
        app.launch()
        chooseDetailedTranscript()
        openLiveSession()
        XCTAssertTrue(promptField().waitForExistence(timeout: 15))
        XCTAssertTrue(waitForLiveTurn(), "the scripted turn finishes and the transcript settles")

        let last = growTranscript(messages: 15)
        // The transcript measures itself, and a keyboard takes half of it.
        transcript().coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)).tap()
        XCTAssertTrue(waitForNoKeyboard(), "the keyboard is away before the transcript is measured")

        let view = transcript()
        let jump = app.buttons["chat.jumpToLatest"]
        for _ in 0..<12 { view.swipeDown(velocity: .fast) }
        XCTAssertTrue(jump.waitForExistence(timeout: 10), "paging up offers the way back down")
        attach(name: "30-far-above-the-tail")

        jump.tap()
        XCTAssertTrue(jump.waitForNonExistence(timeout: 15),
                      "the button leaves only once the tail is really on screen")
        let newest = text(containing: last).firstMatch
        XCTAssertTrue(newest.waitForExistence(timeout: 10), "the newest message is drawn")
        XCTAssertTrue(newest.isHittable, "and one tap landed at the very end of the transcript")
        attach(name: "31-jump-from-far-up")
    }

    /// Sends `messages` messages and returns the text of the last one. The demo
    /// opens on about two screens, which is not far enough for a lazy list to
    /// guess wrong about the rows it has not laid out; a dozen more exchanges
    /// put the tail pages away, and every third one is long enough to wrap over
    /// several lines, so the rows below the reader are of very uneven height —
    /// which is what a guessed height gets wrong.
    @discardableResult
    private func growTranscript(messages: Int) -> String {
        let field = promptField()
        let send = app.buttons["composer.send"]
        let long = "and this one runs on, because a row that wraps over half a screen is what "
            + "a list guessing the height of what it has not laid out gets wrong"
        var text = ""
        for index in 1...messages {
            text = index.isMultiple(of: 3) ? "again \(index) \(long)" : "again \(index)"
            field.tap()
            field.typeText(text)
            XCTAssertTrue(waitFor { send.isEnabled }, "the draft reached the composer")
            send.tap()
        }
        XCTAssertTrue(self.text(containing: "again \(messages)").waitForExistence(timeout: 20),
                      "the last of the messages is in the transcript")
        return "again \(messages)"
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

        let detail = app.segmentedControls["settings.timelineDetail"]
        XCTAssertTrue(scrollDown(to: detail), "the timeline detail preference is in Settings")
        detail.buttons["Detailed"].tap()

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

    /// Where the message field is scrolled, as "<offset>/<end>" in points.
    /// `value` on a text view reports the whole draft whether the field is
    /// showing its first line or its last, so the position comes from the one
    /// extra element `--field-scroll-probe` puts beside the field.
    private func fieldScroll() -> (offset: Double, end: Double)? {
        let probe = app.descendants(matching: .any)["composer.prompt.scroll"].firstMatch
        guard probe.exists else { return nil }
        let numbers = probe.label.split(separator: "/").compactMap { Double($0) }
        guard numbers.count == 2 else { return nil }
        return (numbers[0], numbers[1])
    }

    /// Whether the last line of the draft is the one in view: there is more
    /// text than the field is tall, and the text has moved all the way down.
    private func fieldShowsItsLastLine() -> Bool {
        guard let scroll = fieldScroll(), scroll.end > 1 else { return false }
        return abs(scroll.offset - scroll.end) < 2
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
        XCTAssertFalse(app.buttons["composer.modelCard"].exists,
                       "and the settings live in the terminal, so no control is offered")
        XCTAssertFalse(app.buttons["composer.permissions"].exists)
        XCTAssertTrue(app.buttons["composer.send"].exists, "what is left still sends")

        // Amendment A17: the values themselves are shown where the controls
        // would be, so the phone can say which model that terminal is running.
        // Amendment A21: model and effort are one control, so one chip.
        for field in ["modelCard", "permissionMode"] {
            XCTAssertTrue(app.descendants(matching: .any)["composer.readonly.\(field)"].exists,
                          "the \(field) the terminal chose is shown")
        }
        // The terminal switches model on this session a moment after it opens
        // (A17), so the assertion is on the shape of the value rather than on
        // which model happened to be current when it was read.
        let shown = app.descendants(matching: .any)["composer.readonly.modelCard"].value as? String ?? ""
        XCTAssertTrue(shown.hasSuffix(" High"),
                      "the model and the effort read as one value, not as two chips")

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
        XCTAssertTrue(app.buttons["composer.modelCard"].exists, "and shared_settings keeps the chips")
        XCTAssertFalse(app.descendants(matching: .any)["composer.readonly.modelCard"].exists,
                       "and A17 shows nothing where the control is live")
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

        // `docs/DESIGN.md` § "The model card": after the card comes the
        // permission-mode picker, a plain list of the agent's modes with the
        // current one marked and nothing else. There is no settings sheet.
        let permissions = app.buttons["composer.permissions"]
        XCTAssertEqual(permissions.value as? String, "Ask when needed",
                       "the chip reads the mode the daemon is on")
        permissions.tap()
        let never = app.buttons["Never ask"]
        XCTAssertTrue(never.waitForExistence(timeout: 10),
                      "shared_settings opens the agent's own modes")
        XCTAssertTrue(app.buttons["Ask for everything"].exists, "every one it lists")
        attach(name: "13-codex-permissions")
        never.tap()
        XCTAssertTrue(waitFor { app.buttons["composer.permissions"].value as? String == "Never ask" },
                      "and choosing one is drawn at once")

        XCTAssertTrue(allow.waitForExistence(timeout: 10), "the card is still there after the menu")
        allow.tap()
        XCTAssertTrue(allow.waitForNonExistence(timeout: 15), "answering resolves the request")
        attach(name: "14-codex-answered")
    }

    /// Amendment A10: a terminal session the device cannot attach says what the
    /// machine is missing instead of pretending the composer will work.
    func testTerminalSessionExplainsHowToAttach() {
        app.launch()

        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20))
        // It is on the second machine, and four agents' worth of sessions on
        // the first one (A26) put that machine's group below the fold.
        let row = app.buttons["session.demo-session-rename"]
        XCTAssertTrue(scrollDown(to: row), "the unattachable terminal session is listed")
        row.tap()

        let hint = app.descendants(matching: .any)["chat.attachHint"]
        XCTAssertTrue(hint.waitForExistence(timeout: 15), "the session says how to make it controllable")
        // Claude does advertise `takeover`, so the line above the field names
        // the way out — and the field itself still says the short sentence,
        // which is the half that is the same on every agent.
        XCTAssertTrue(app.staticTexts["Controlled by the terminal · take over to send"].exists,
                      "the status line names the way out, because this agent has one")
        XCTAssertEqual(promptField().label, "Controlled by the terminal",
                       "and the field does not repeat the clause")
        attach(name: "11-attach-hint")
    }

    /// Every tone a status dot can take is on the sessions list: a turn under
    /// way, a session blocked on the user, one that is alive and quiet, one
    /// whose agent stopped on an error, and one nothing owns any more on a
    /// machine that is no longer there.
    ///
    /// The four on the live machine are in frame together, which is what the
    /// screenshot is for. The grey one is not: it lives in another machine's
    /// Archive, and since the demo grew to four agents (A26) the rows in
    /// between are taller than a phone. It is reached by scrolling instead.
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
        // then short ones until every row is reachable. The four rows and the
        // bar below them are together about as tall as this screen, so the
        // first row is taken right up under the search field and there is no
        // pixel margin left to spare; what "in frame together" means is that
        // the reader can see and reach all four, and a fling would land
        // anywhere.
        let list = sessionList()
        let tones = [("demo-session-vite", "pulsing amber, waiting on the user"),
                     ("demo-session-auth", "steady green, a turn running"),
                     ("demo-session-toolchain", "red, stopped on an error"),
                     ("demo-session-shared", "steady amber, attached to a terminal and quiet")]
        let rows = tones.map { app.buttons["session.\($0.0)"] }
        drag(list, by: rows[0].frame.minY - list.frame.minY - 52)
        for _ in 0..<6 where !rows.allSatisfy({ $0.isHittable }) { drag(list, by: 24) }

        for (row, tone) in zip(rows, tones) {
            XCTAssertTrue(row.isHittable, "the \(tone.1) row is in frame with the rest")
        }
        attach(name: "30-status-tones")

        // And the fifth, further down than a screen reaches.
        XCTAssertTrue(scrollDown(to: archived), "the grey row, owned by nothing, is still reachable")
        attach(name: "30-status-tone-grey")

        XCTAssertTrue(scrollDown(to: middle), "the folded machine is still there")
        middle.tap()
        if opened {
            XCTAssertTrue(scrollDown(to: archive), "the Archive header is still reachable")
            archive.tap()
        }
    }

    /// `docs/DESIGN.md` § "The session row says where it came from" and § "A
    /// legend, once, and quiet": a row's word is where the session came from,
    /// its state is the dot's colour alone, and one caption above the list says
    /// what the colours mean — on this screen and on no other.
    func testSessionRowsNameTheirOriginAndTheDotsAreExplainedOnce() {
        app.launch()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20))

        let remote = app.buttons["session.demo-session-auth"]
        XCTAssertTrue(remote.waitForExistence(timeout: 10), "a session this app started is listed")
        XCTAssertTrue(remote.label.contains("Remote Control"),
                      "and its row says where it came from — \(remote.label)")
        XCTAssertFalse(remote.label.contains("running"),
                       "rather than what it is doing, which the green dot already says")

        let terminal = app.buttons["session.demo-session-vite"]
        XCTAssertTrue(terminal.label.contains("Terminal"),
                      "a session a terminal started says so — \(terminal.label)")
        XCTAssertFalse(terminal.label.contains("needs approval"),
                       "and leaves the waiting to the amber dot")

        let legend = app.descendants(matching: .any).matching(identifier: "sessions.legend").firstMatch
        XCTAssertTrue(legend.waitForExistence(timeout: 10),
                      "the colours are explained above the list")
        XCTAssertEqual(legend.label, "Working, For you, Not running, Error",
                       "in four entries, read as one line")
        attach(name: "31-origin-and-legend")

        app.tabBars.buttons["Devices"].tap()
        XCTAssertTrue(app.buttons["devices.add"].waitForExistence(timeout: 15), "the Devices screen is up")
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "sessions.legend").firstMatch.exists,
                       "and carries no legend of its own, because nothing is explained twice")
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

    /// Amendment A39, `docs/DESIGN.md` § "Close, then the Archive": the row
    /// action is Close, not Archive. A working session is asked about first and
    /// Cancel really cancels; an idle one closes on the tap; either way the row
    /// lands in that machine's Archive, marked "Archived", and offers nothing
    /// further.
    func testClosingASessionAsksOnlyWhileTheAgentIsWorking() {
        app.launch()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20))

        // A running session this app drives. Its swipe carries one action.
        let live = app.buttons["session.demo-session-auth"]
        XCTAssertTrue(live.waitForExistence(timeout: 10), "the running remote session is listed")
        revealRowActions(of: live)
        let close = app.buttons["session.close.demo-session-auth"]
        XCTAssertTrue(close.waitForExistence(timeout: 10), "one swipe offers Close")
        XCTAssertEqual(close.label, "Close", "by that word, and no longer Archive")
        close.tap()

        // The agent is working, so the tap asks before it throws the turn away.
        let dialog = app.alerts["Close this session?"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 10), "a working session is asked about first")
        XCTAssertTrue(dialog.staticTexts["The agent is still working; what it has not finished is lost."]
            .exists, "over the sentence that says what is lost")
        XCTAssertTrue(dialog.buttons["Close"].exists, "with the same word the row used")
        XCTAssertTrue(dialog.buttons["Cancel"].exists, "and a way out")
        attach(name: "61-session-close-dialog")

        dialog.buttons["Cancel"].tap()
        XCTAssertTrue(dialog.waitForNonExistence(timeout: 10), "Cancel closes the dialog")
        XCTAssertTrue(live.exists, "and leaves the session where it was")
        XCTAssertFalse(live.label.contains("archived"), "unarchived, and still live")

        // The same swipe, answered this time.
        revealRowActions(of: live)
        XCTAssertTrue(close.waitForExistence(timeout: 10), "the action is still there")
        close.tap()
        XCTAssertTrue(dialog.waitForExistence(timeout: 10), "it asks again")
        dialog.buttons["Close"].tap()
        XCTAssertTrue(live.waitForNonExistence(timeout: 15),
                      "the row leaves the live rows for the machine's collapsed Archive")

        // An idle session the device drives has nothing to lose, so it goes on
        // the tap, with no dialog in between.
        let idle = app.buttons["session.demo-session-parser"]
        XCTAssertTrue(scrollDown(to: idle), "the idle session this app drives is listed")
        revealRowActions(of: idle)
        let closeIdle = app.buttons["session.close.demo-session-parser"]
        XCTAssertTrue(closeIdle.waitForExistence(timeout: 10), "its swipe offers Close as well")
        closeIdle.tap()
        XCTAssertFalse(app.alerts.firstMatch.waitForExistence(timeout: 3),
                       "and nothing is asked, because nothing is lost")
        XCTAssertTrue(idle.waitForNonExistence(timeout: 15), "the row goes straight to the Archive")

        // Both are in that machine's Archive, marked and finished with.
        let archive = app.buttons["sessions.archive.demo-mac-studio"]
        XCTAssertTrue(scrollDown(to: archive), "the machine's Archive has grown")
        archive.tap()
        XCTAssertTrue(scrollDown(to: live), "the session that was working is inside it")
        XCTAssertTrue(live.label.contains("archived"), "marked as filed by hand — \(live.label)")
        XCTAssertTrue(scrollDown(to: idle), "and so is the one that was idle")
        XCTAssertTrue(idle.label.contains("archived"), "with the same mark — \(idle.label)")
        attach(name: "62-session-closed-archive")

        revealRowActions(of: live)
        XCTAssertFalse(app.buttons["session.close.demo-session-auth"].exists,
                       "a row in the Archive offers nothing, not even unarchive")
    }

    /// Reveal a list row's trailing actions without crossing SwiftUI's
    /// full-swipe threshold, which would run the first action instead of
    /// offering it. A third of the row's width is enough to open them.
    private func revealRowActions(of row: XCUIElement) {
        let start = row.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5))
        start.press(forDuration: 0.1,
                    thenDragTo: row.coordinate(withNormalizedOffset: CGVector(dx: 0.58, dy: 0.5)),
                    withVelocity: .slow, thenHoldForDuration: 0.3)
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
    /// `docs/DESIGN.md` § "Agents", amendment A26: four agents on one machine.
    /// The segmented control carries each agent's logo rather than its name,
    /// because four names do not fit a phone; assistive technology still reads
    /// the name. And the form asks about nothing the agent does not have.
    func testNewSessionSheetMarksEveryAgentAndDrawsOnlyWhatItHas() {
        app.launch()
        let newSession = app.buttons["sessions.new"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 20))
        newSession.tap()
        XCTAssertTrue(app.buttons["newsession.start"].waitForExistence(timeout: 10),
                      "the sheet is up")

        let picker = app.segmentedControls.firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 10), "the agent control is a segmented control")
        XCTAssertEqual(picker.buttons.allElementsBoundByIndex.map { $0.label },
                       ["Claude Code", "Codex", "Grok Build", "pi"],
                       "one segment per agent the machine reported, each named to a screen reader")
        attach(name: "82-new-session-agents")

        // Amendment A26: pi's permission modes are the device's own, enforced
        // by the extension it loads, so the form asks about them like any other.
        app.buttons["pi"].firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any)["newsession.effort"].exists,
                      "pi offers the thinking levels it does have")
        XCTAssertTrue(app.descendants(matching: .any)["newsession.permissions"].exists,
                      "and the three permission modes the extension enforces (A26)")
        attach(name: "83-new-session-pi")
    }

    /// Amendment A26: pi's permission modes are the device's own, so the
    /// composer row carries the permission chip exactly as Codex's does.
    func testPiSessionDrawsItsPermissionChip() {
        app.launch()
        let row = app.buttons["session.demo-session-parser"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the pi demo session is listed")
        row.tap()

        let chip = app.buttons["composer.modelCard"]
        XCTAssertTrue(chip.waitForExistence(timeout: 15), "the model card is on the row")
        XCTAssertEqual(chip.label, "Model")
        XCTAssertEqual(chip.value as? String, "Claude Sonnet 4.5 Medium",
                       "reading the model and the thinking level pi is set to")
        let permissions = app.buttons["composer.permissions"]
        XCTAssertTrue(permissions.waitForExistence(timeout: 10),
                      "and the permission chip stands beside it (A26)")
        XCTAssertEqual(permissions.value as? String, "Ask when needed",
                       "reading the mode the extension is enforcing")
        attach(name: "85-pi-composer")

        // The levels pi does have are still a slider on the card.
        chip.tap()
        XCTAssertTrue(app.buttons["composer.model"].waitForExistence(timeout: 10), "the card opens")
        XCTAssertTrue(app.descendants(matching: .any)["composer.effort"].exists,
                      "with the thinking-level slider on it")
        attach(name: "86-pi-model-card")
    }

    /// Amendment A27: `/` opens the terminal's own menu over the keyboard, the
    /// letters after it filter the list, a tap writes the command into the
    /// field, and Send runs it. `docs/DESIGN.md` § "The composer".
    func testCommandPanelOpensFiltersAndRunsOnAPiSession() {
        app.launch()
        let row = app.buttons["session.demo-session-parser"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the pi demo session is listed")
        row.tap()

        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the composer is on screen")
        field.tap()
        field.typeText("/")

        let panel = app.descendants(matching: .any)["composer.commands"].firstMatch
        XCTAssertTrue(panel.waitForExistence(timeout: 10),
                      "the card is there for the first slash, not a round trip later")
        XCTAssertTrue(app.staticTexts["Prompts"].exists,
                      "and pi distinguishes more than one source, so the rows are sectioned")
        XCTAssertTrue(app.descendants(matching: .any)["command.release-notes"].firstMatch.exists,
                      "with this project's own prompt templates on it")
        attach(name: "90-commands-panel")

        // Letters after the slash filter the list by the name's prefix.
        field.typeText("com")
        let compact = app.descendants(matching: .any)["command.compact"].firstMatch
        XCTAssertTrue(compact.waitForExistence(timeout: 10), "the one match stays")
        XCTAssertTrue(app.descendants(matching: .any)["command.changelog"].firstMatch
            .waitForNonExistence(timeout: 10), "and everything else goes")
        attach(name: "91-commands-filtered")

        // Taking a row writes the command, with the space that shows where its
        // argument goes, and hands over to the hint line under the card.
        compact.tap()
        let hint = app.descendants(matching: .any)["composer.commandHint"].firstMatch
        XCTAssertTrue(hint.waitForExistence(timeout: 10), "the hint line takes over")
        XCTAssertTrue(app.descendants(matching: .any)["composer.commands"].firstMatch
            .waitForNonExistence(timeout: 10), "and the card closes")
        XCTAssertEqual(field.value as? String, "/compact ",
                       "the field holds the command and the space after it")
        attach(name: "92-command-hint")

        // Send runs it, and what it did comes back as a notice rather than as
        // something the agent said.
        app.buttons["composer.send"].tap()
        let notice = app.staticTexts
            .containing(NSPredicate(format: "label CONTAINS[c] %@", "compacted")).firstMatch
        XCTAssertTrue(notice.waitForExistence(timeout: 20), "the transcript reports the compaction")
        attach(name: "93-command-ran")
    }

    /// A command runs between turns, never inside one: the rows are dimmed, the
    /// card says why, and Send does not act until the turn is over.
    func testCommandPanelWaitsForTheRunningTurnOnACodexThread() {
        app.launch()
        let row = app.buttons["session.demo-session-typecheck"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the shared Codex thread is listed")
        row.tap()

        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the composer is on screen")
        field.tap()
        field.typeText("/usage")

        XCTAssertTrue(app.descendants(matching: .any)["composer.commands"].firstMatch
            .waitForExistence(timeout: 10), "the card opens on a running session too")
        XCTAssertTrue(app.staticTexts["Available when the turn finishes"].exists,
                      "and says once, under the rows, why nothing can be run yet")
        XCTAssertFalse(app.buttons["composer.send"].isEnabled, "Send does not act")
        attach(name: "94-commands-waiting")

        // Codex has one source, so nothing is sectioned.
        XCTAssertFalse(app.staticTexts["Built-in"].exists,
                       "one group draws no header at all")

        app.buttons["chat.stop"].tap()
        XCTAssertTrue(app.staticTexts["Available when the turn finishes"]
            .waitForNonExistence(timeout: 20), "the turn ends and the rows come back")
        XCTAssertTrue(app.buttons["composer.send"].isEnabled, "and Send acts again")

        app.buttons["composer.send"].tap()
        let card = app.buttons.containing(NSPredicate(format: "label BEGINSWITH %@", "/usage")).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20),
                      "information a terminal would have printed arrives as a tool call")
        card.tap()
        XCTAssertTrue(app.staticTexts
            .containing(NSPredicate(format: "label CONTAINS[c] %@", "5-hour window")).firstMatch
            .waitForExistence(timeout: 10), "and opens on what it printed")
        attach(name: "95-command-output")
    }

    /// An agent that lists no `commands` capability draws no panel, and nothing
    /// on the screen explains the difference: `/` is a character there.
    func testClaudeSessionDrawsNoCommandPanel() {
        app.launch()
        let row = app.buttons["session.demo-session-auth"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the Claude demo session is listed")
        row.tap()

        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the composer is on screen")
        field.tap()
        field.typeText("/compact")

        XCTAssertFalse(app.descendants(matching: .any)["composer.commands"].firstMatch
            .waitForExistence(timeout: 3), "a Claude session never opens the card")
        XCTAssertFalse(app.descendants(matching: .any)["composer.commandHint"].firstMatch.exists,
                       "and nothing under the field explains why")
        XCTAssertTrue(app.buttons["composer.send"].isEnabled,
                      "what was typed is an ordinary message")
        attach(name: "96-no-command-panel")
    }

    /// Amendment A25: Grok Build writes its own update log, so a session a
    /// terminal started is mirrored here — readable, not writable. Its summary
    /// carries the model and the level but never a permission mode, so one chip
    /// stands where two would (A17).
    ///
    /// Amendment A28: it is mirrored at all only because this machine leaves
    /// `[cli] use_leader` off. The hint under the status line says so, and names
    /// the command that would put its terminals in the leader.
    func testGrokTerminalSessionShowsWhatItsLogKnows() {
        app.launch()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20))
        // It runs on the second machine, under four agents' worth of rows on
        // the first one, so the list is scrolled to it.
        let row = app.buttons["session.demo-session-migrations"]
        XCTAssertTrue(scrollDown(to: row), "the Grok demo session is listed")
        row.tap()

        let card = app.descendants(matching: .any)["composer.readonly.modelCard"]
        XCTAssertTrue(card.waitForExistence(timeout: 15), "the model and level are shown, not offered")
        XCTAssertEqual(card.value as? String, "Grok 4.6 High")
        XCTAssertFalse(app.descendants(matching: .any)["composer.readonly.permissionMode"].exists,
                       "and the update log knows no permission mode, so no chip claims one")
        XCTAssertFalse(app.buttons["composer.modelCard"].exists,
                       "nothing on this row is a control")

        // `docs/DESIGN.md` § "The composer": the way out is named only where
        // the agent has one, and Grok Build advertises no `takeover`.
        XCTAssertEqual(promptField().label, "Controlled by the terminal",
                       "the field says who has this session and promises nothing else")
        XCTAssertFalse(app.staticTexts
            .containing(NSPredicate(format: "label CONTAINS[c] %@", "take over")).firstMatch.exists,
                       "and nothing on the screen invites a tap that would be refused")
        attach(name: "88-grok-terminal-composer")

        // Amendment A28: the hint is the only place the leader is named, and it
        // names the command rather than explaining the mechanism.
        let hint = app.descendants(matching: .any)["chat.attachHint"]
        XCTAssertTrue(hint.waitForExistence(timeout: 15),
                      "the session says what would make it controllable")
        XCTAssertTrue(app.staticTexts["Run rc-client grok setup on the device, then restart Grok"]
            .exists, "which is the setup command and a restart, in one line")
        attach(name: "89-grok-leader-hint")
    }

    /// Amendment A28: the same agent on a machine that is in the leader. The
    /// device joined the session the TUI is in, so the turn the terminal set
    /// off is stoppable here and the pickers are live — and the attachment
    /// button is gone, because a Grok prompt carries no images.
    func testSharedGrokSessionStopsAndRetunesButTakesNoAttachments() {
        app.launch()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20))
        let row = app.buttons["session.demo-session-retries"]
        XCTAssertTrue(scrollDown(to: row), "the Grok session on the leader is listed")
        row.tap()

        let composer = app.textViews["composer.prompt"].firstMatch
        let composerField = composer.exists ? composer : app.textFields["composer.prompt"].firstMatch
        XCTAssertTrue(composerField.waitForExistence(timeout: 15),
                      "the composer takes what is typed into a session the leader shares")

        XCTAssertTrue(app.buttons["chat.stop"].exists,
                      "shared_interrupt and the capability together offer Stop")
        XCTAssertTrue(app.buttons["composer.modelCard"].exists,
                      "shared_settings keeps the model card a control")
        XCTAssertTrue(app.buttons["composer.permissions"].exists, "and the permission chip with it")
        XCTAssertFalse(app.descendants(matching: .any)["composer.readonly.modelCard"].exists,
                       "so nothing is shown where a control stands (A17)")
        XCTAssertFalse(app.buttons["composer.attach"].exists,
                       "while shared_attachments is false, so there is no attach button")
        XCTAssertFalse(app.descendants(matching: .any)["chat.attachHint"].exists,
                       "an attached session explains nothing; it works")
        attach(name: "90-grok-shared-composer")

        // The card behind the chip is the agent's own: its models, its four
        // effort levels, and no speed tier, because Grok Build lists none.
        app.buttons["composer.modelCard"].tap()
        XCTAssertTrue(app.buttons["composer.model"].waitForExistence(timeout: 10),
                      "the card opens on a session the leader shares")
        XCTAssertTrue(app.descendants(matching: .any)["composer.effort"].firstMatch.exists,
                      "with the effort the leader would set for every client")
        XCTAssertFalse(app.buttons["composer.speed"].exists, "and no tier this agent never named")
        attach(name: "91-grok-shared-model")
    }

    private func scrollDown(to element: XCUIElement, swipes: Int = 6) -> Bool {
        for _ in 0..<swipes {
            if element.exists && element.isHittable { return true }
            app.swipeUp()
        }
        return element.exists && element.isHittable
    }

    /// Scroll a transcript back towards its start until an element is in the
    /// tree. A lazily laid-out row above the visible part does not exist until
    /// it is scrolled into view; each step first gives the rows time to load.
    private func scrollUp(to element: XCUIElement, in view: XCUIElement, swipes: Int = 8) -> Bool {
        for _ in 0..<swipes {
            if element.waitForExistence(timeout: 2) { return true }
            view.swipeDown()
        }
        return element.exists
    }

    /// `docs/DESIGN.md` § "Three tabs, one order, one landing rule": Devices,
    /// Sessions, Settings, in that order on both apps. The demo account has
    /// machines, so the app opens on the conversation.
    func testTabsReadDevicesSessionsSettingsAndLandOnSessions() {
        app.launch()
        let devices = app.tabBars.buttons["Devices"]
        XCTAssertTrue(devices.waitForExistence(timeout: 20), "the shell is up")
        let sessions = app.tabBars.buttons["Sessions"]
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertLessThan(devices.frame.minX, sessions.frame.minX, "Devices stands first")
        XCTAssertLessThan(sessions.frame.minX, settings.frame.minX,
                          "then Sessions, then Settings")
        XCTAssertTrue(sessions.isSelected,
                      "an account with a machine lands where the conversation is")
        XCTAssertTrue(app.buttons["sessions.new"].exists, "on the sessions list itself")
    }

    /// `docs/DESIGN.md` § "The three screens": on the phone the Sessions list
    /// ends the way the Devices list ends — one primary button in the bottom
    /// bar, exactly where Devices puts Add device — and the inventory summary
    /// is the list's own last row rather than a strip under it.
    func testSessionsListEndsWithTheNewSessionButtonInTheBottomBar() {
        app.launch()

        let newSession = app.buttons["sessions.new"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 20), "the button is on the Sessions list")
        let sessionsBar = newSession.frame

        let summary = app.staticTexts["sessions.summary"]
        XCTAssertTrue(scrollDown(to: summary), "the summary is a row inside the list")
        XCTAssertLessThan(summary.frame.maxY, sessionsBar.minY,
                          "and sits above the bar rather than in it")
        attach(name: "69-sessions-bottom-bar")

        app.tabBars.buttons["Devices"].tap()
        let addDevice = app.buttons["devices.add"]
        XCTAssertTrue(addDevice.waitForExistence(timeout: 15), "Devices puts its primary there too")
        XCTAssertEqual(sessionsBar.minY, addDevice.frame.minY, accuracy: 1,
                       "both bars stand at the same height")
        XCTAssertEqual(sessionsBar.height, addDevice.frame.height, accuracy: 1,
                       "and are drawn to the same size")
        XCTAssertEqual(sessionsBar.minX, addDevice.frame.minX, accuracy: 1,
                       "with the same page padding")
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

        // `docs/DESIGN.md` § "The composer": forms list Model, Effort,
        // Permissions in that order, with the speed switch after the three.
        // The sheet opens on Claude, which lists no tier, so the agent that has
        // one is chosen first — the lists all follow the agent picker.
        app.buttons["Codex"].firstMatch.tap()
        let rows = ["newsession.model", "newsession.effort",
                    "newsession.permissions", "newsession.speed"]
        for row in rows {
            XCTAssertTrue(scrollDown(to: app.descendants(matching: .any)[row]),
                          "the sheet offers \(row)")
        }
        attach(name: "52-new-session-settings")
        // Read the order from the accessibility hierarchy rather than from
        // coordinates: the rows were scrolled to one at a time, so their frames
        // belong to different scroll positions and cannot be compared.
        var seen: [String] = []
        for element in app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier IN %@", rows))
            .allElementsBoundByIndex where !seen.contains(element.identifier) {
            seen.append(element.identifier)
        }
        XCTAssertEqual(seen, rows.filter(seen.contains),
                       "and lists them in the order Model, Effort, Permissions, Speed")
        app.swipeDown(velocity: .fast)
        attach(name: "04-new-session")
    }

    /// Amendment A37 and rule 19: the browser can make a folder where a session
    /// will work. A name something already has is refused beside the name and
    /// left to be corrected; the folder that is made becomes the listing on
    /// screen, and the same Select picks it as the working directory.
    func testDirectoryPickerMakesAFolderAndPicksIt() {
        app.launch()
        let newSession = app.buttons["sessions.new"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 20))
        newSession.tap()

        let browse = app.buttons["newsession.browse"]
        XCTAssertTrue(scrollDown(to: browse), "the working directory offers the browser")
        browse.tap()

        let select = app.buttons["dirs.select"]
        XCTAssertTrue(select.waitForExistence(timeout: 10), "the picker is up")
        let newFolder = app.buttons["dirs.newFolder"]
        XCTAssertTrue(newFolder.exists, "and offers New folder where it shows a listing")
        newFolder.tap()

        // `gateway` is a directory the demo device already has.
        XCTAssertTrue(typeFolderName("gateway"), "the row asks for one name")
        app.buttons["dirs.create"].tap()
        XCTAssertTrue(app.staticTexts["A folder with that name already exists."]
            .waitForExistence(timeout: 10), "a clash is said beside the name")
        XCTAssertEqual(app.textFields["dirs.folderName"].value as? String, "gateway",
                       "and the name is kept for editing")
        attach(name: "53-directory-new-folder-clash")

        // Cancel takes the row away without making anything.
        app.buttons["dirs.cancelFolder"].tap()
        XCTAssertTrue(app.textFields["dirs.folderName"].waitForNonExistence(timeout: 5),
                      "Cancel leaves the listing as it was")

        newFolder.tap()
        XCTAssertTrue(typeFolderName("round-41"), "and the row can be asked for again")
        attach(name: "54-directory-new-folder")
        app.buttons["dirs.create"].tap()

        // The device answered with the new directory's listing, so the picker
        // now stands in it: its name is the title, and it holds nothing.
        XCTAssertTrue(app.navigationBars["round-41"].waitForExistence(timeout: 10),
                      "the picker stands in the folder it made")
        XCTAssertTrue(app.staticTexts["No subdirectories here."].exists, "which is empty")
        attach(name: "55-directory-picker-in-new-folder")
        select.tap()

        let cwd = app.textFields["newsession.cwd"]
        XCTAssertTrue(cwd.waitForExistence(timeout: 10), "the sheet is back")
        XCTAssertEqual(cwd.value as? String, "/Users/me/dev/round-41",
                       "and the working directory is the folder that was just made")
    }

    /// Type a folder name into the row the picker revealed. Returns false when
    /// the field never appeared, so the caller fails on that rather than on
    /// whatever it asserted next.
    private func typeFolderName(_ text: String) -> Bool {
        let field = app.textFields["dirs.folderName"]
        guard field.waitForExistence(timeout: 10) else { return false }
        field.tap()
        field.typeText(text)
        return true
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
        // `docs/DESIGN.md` § "Add device": the installer tells macOS from Linux
        // itself, so the sheet asks nobody to choose one.
        XCTAssertFalse(app.segmentedControls["pairing.platform"].exists,
                       "and no platform is asked for")
        attach(name: "05-add-device")

        // Cancel leaves at once. The code is given back behind the closed
        // sheet, so the "Requesting a code" placeholder is never drawn on the
        // way out (owner's report, 2026-09-18).
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["pairing.command"].waitForNonExistence(timeout: 5),
                      "the sheet is gone")
        XCTAssertFalse(app.staticTexts["Requesting a code"].exists,
                       "and nothing about requesting a code was left on screen")
        XCTAssertTrue(app.buttons["devices.add"].waitForExistence(timeout: 5),
                      "the Devices screen is back")
    }

    /// `docs/DESIGN.md` § "Surfaces, rows and controls": nothing is re-cased.
    /// A re-cased header carries the transformed text in its accessibility
    /// label, so reading the label reads what is really on the screen. The
    /// groups are the five § "The Settings screen" names, in its order.
    func testSettingsSectionHeadersAreSentenceCase() {
        app.launch()
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 20))
        settings.tap()

        XCTAssertTrue(app.descendants(matching: .any)["settings.identity"]
            .waitForExistence(timeout: 15), "the screen opens on who is signed in")
        attach(name: "30-settings-headers")

        // The last groups are below the fold on a phone, so they are scrolled
        // to rather than assumed.
        for header in ["Account", "While you're away", "Voice", "Reading", "Security"] {
            XCTAssertTrue(scrollDown(to: app.staticTexts[header]), "the group is headed \(header)")
            XCTAssertFalse(app.staticTexts[header.uppercased()].exists,
                           "and not \(header.uppercased())")
        }
        // The groups the ruling took away, with their rows now in the header
        // and in the versions line.
        for gone in ["About", "Notifications", "Timeline", "App lock"] {
            XCTAssertFalse(app.staticTexts[gone].exists, "and there is no group called \(gone)")
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

    /// `docs/DESIGN.md`: "Launch shows the app, never the sign-in form, when
    /// there is an account." The demo is an account, so the first screen is the
    /// sessions list and the gateway form is never drawn on the way to it.
    func testLaunchWithAnAccountNeverShowsTheSignInForm() {
        app.launch()
        XCTAssertFalse(app.buttons["login.connect"].exists,
                       "the form is not what a launch with an account draws first")
        let sessions = app.staticTexts["Sessions"]
        XCTAssertTrue(sessions.waitForExistence(timeout: 20), "the sessions screen is the first screen")
        XCTAssertFalse(app.buttons["login.connect"].exists, "and the form never appeared on the way")
        attach(name: "42-launch-with-account")
    }

    /// And the other half of the rule: with nothing stored the form is the
    /// answer, so it is what a fresh install opens on.
    func testLaunchWithNothingStoredShowsTheSignInForm() {
        app.launchArguments = ["--ui-testing", "--reset-state"]
        app.launch()
        XCTAssertTrue(app.buttons["login.connect"].waitForExistence(timeout: 20),
                      "a fresh install asks for a gateway")
        XCTAssertTrue(app.textFields["login.gateway"].exists)
        attach(name: "43-launch-without-account")
    }

    /// Amendment A21 and `docs/DESIGN.md` § "The composer": one chip for the
    /// model, the effort and the speed, opening a card that holds all three.
    /// The selection haptic on each stop the thumb crosses cannot be asserted
    /// from a UI test; the effort word following the thumb can.
    func testModelCardCarriesModelEffortAndSpeed() {
        app.launch()

        let row = app.buttons["session.demo-session-typecheck"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the shared Codex thread is listed")
        row.tap()

        let chip = app.buttons["composer.modelCard"]
        XCTAssertTrue(chip.waitForExistence(timeout: 15), "the composer spends one chip on what runs")
        XCTAssertEqual(chip.value as? String, "GPT-5.4 Codex Medium",
                       "reading the model with the effort word after it")
        XCTAssertFalse(app.buttons["composer.effort"].exists,
                       "and the effort is no longer a chip of its own")
        chip.tap()

        let speed = app.buttons["composer.speed"]
        XCTAssertTrue(speed.waitForExistence(timeout: 10), "the card opens on the speed toggle")
        XCTAssertEqual(speed.value as? String, "Standard", "which starts at the standard speed")
        let slider = app.descendants(matching: .any)["composer.effort"].firstMatch
        XCTAssertTrue(slider.exists, "with the effort slider under it")
        let chipWidth = chip.frame.width
        attach(name: "44-model-card")

        // A tap on a stop moves there. The last stop is the far end of the
        // track, so the tap lands on it whatever width the card took.
        slider.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
        XCTAssertTrue(waitFor { (slider.value as? String) == "High" },
                      "the slider snaps to the agent's own levels")
        XCTAssertTrue(app.staticTexts["High"].waitForExistence(timeout: 10),
                      "and the word in the first row follows the thumb")

        speed.tap()
        XCTAssertTrue(waitFor { (speed.value as? String) == "Fast" },
                      "one tap raises the tier the agent named, drawn before the device answers")
        attach(name: "45-model-card-fast")

        // `docs/DESIGN.md` § "The model card": the chip is as wide as the
        // widest model-and-effort combination, so nothing beside it shifts.
        XCTAssertEqual(chip.frame.width, chipWidth, accuracy: 0.5,
                       "the chip keeps its width through a level and a tier change")
    }

    /// An agent that lists no tier draws no speed control at all, rather than a
    /// disabled one with a caption explaining itself.
    func testModelCardHasNoSpeedToggleForClaude() {
        app.launch()
        openLiveSession()

        let chip = app.buttons["composer.modelCard"]
        XCTAssertTrue(chip.waitForExistence(timeout: 15))
        chip.tap()

        XCTAssertTrue(app.buttons["composer.model"].waitForExistence(timeout: 10),
                      "the card opens")
        XCTAssertFalse(app.buttons["composer.speed"].exists,
                       "and Claude, which has no faster tier, is offered none")
        attach(name: "46-model-card-no-speed")
    }

    /// `docs/DESIGN.md` § "The three screens", Settings: English is the default
    /// whatever the phone is set to, so a Chinese system language changes
    /// nothing until the setting is changed.
    func testInterfaceLanguageDefaultsToEnglishOnAChinesePhone() {
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20),
                      "the list is headed in English on a phone set to Chinese")
        XCTAssertFalse(app.staticTexts["会话"].exists, "and not in the system language")
        attach(name: "47-default-english-on-chinese-phone")
    }

    /// The whole interface in Chinese: the list, the settings, the composer.
    /// Nothing the device reported is translated with it.
    func testChineseInterfaceIsUsedEverywhere() {
        app.launchArguments += ["--language=zh-Hans"]
        app.launch()

        XCTAssertTrue(app.staticTexts["会话"].waitForExistence(timeout: 20), "the list is headed 会话")
        XCTAssertFalse(app.staticTexts["Sessions"].exists, "and no longer in English")
        attach(name: "48-sessions-chinese")

        app.tabBars.buttons["设置"].tap()
        XCTAssertTrue(app.staticTexts["账户"].waitForExistence(timeout: 15), "Settings is headed 账户")
        attach(name: "49-settings-chinese")
        for header in ["通知", "语音", "语言", "时间线"] {
            XCTAssertTrue(scrollDown(to: app.staticTexts[header]), "the section is headed \(header)")
        }
        attach(name: "50-settings-language-chinese")

        app.tabBars.buttons["会话"].tap()
        openLiveSession()
        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the composer is on screen")
        XCTAssertTrue(app.buttons["发送"].exists || app.buttons["composer.send"].label == "发送",
                      "the send button names itself in Chinese")
        XCTAssertTrue(app.buttons["composer.modelCard"].exists, "the model card is still a chip")
        XCTAssertEqual(app.buttons["composer.modelCard"].value as? String, "Sonnet 4.5 High",
                       "and the device's own model and effort labels are not translated")
        attach(name: "51-chat-chinese")
    }

    // MARK: - Devices (amendments A22 and A23)

    /// `docs/DESIGN.md` § "Devices": every row offers the same three actions on
    /// both apps, with the same words in the same order, and on the phone one
    /// trailing swipe holds all three.
    /// `docs/DESIGN.md` § "The device row": the row names the machine once, says
    /// its state and its platform as a word, and draws its agents as logos whose
    /// names are read out rather than written. The hostname and the architecture
    /// are on the machine's own page and on no row.
    func testDeviceRowNamesTheMachineOnceAndDrawsItsAgentsAsLogos() {
        openDevices()
        let row = deviceRow(DemoDevices.studio)
        XCTAssertTrue(row.waitForExistence(timeout: 15), "the machines are listed")

        let label = row.label
        XCTAssertTrue(label.contains("mac-studio-office"), "the row is headed by the machine's name")
        XCTAssertTrue(label.contains("online · macOS"),
                      "and reads state and platform as words beside the dot")
        XCTAssertFalse(label.contains("mac-studio.local"),
                       "the hostname no longer repeats the name one line down")
        XCTAssertFalse(label.contains("arm64"), "and the chip nobody chooses a machine by is gone")
        XCTAssertFalse(label.contains("macos"), "the raw platform id is never on screen")
        for agent in ["Claude Code", "Codex", "Grok Build"] {
            XCTAssertTrue(label.contains(agent), "\(agent)'s logo is read out under its own name")
        }
        // Amendment A36: the gateway keeps every machine on the wheel it
        // serves, so the row states no client version and no build, and never
        // offers one.
        XCTAssertFalse(label.contains(AppBuild.shipped), "the row states no client version")
        XCTAssertFalse(label.contains("client"), "nor the word client")
        XCTAssertFalse(label.contains("3f2b4a9c"), "nor the build hash")
        XCTAssertFalse(label.contains("Update available"), "and never that an update is available")

        let runner = deviceRow(DemoDevices.ci)
        XCTAssertTrue(runner.waitForExistence(timeout: 10), "the Linux machine is listed too")
        XCTAssertTrue(runner.label.contains("offline · Linux"), "which says Linux, not linux")
        XCTAssertFalse(runner.label.contains("x86_64"), "and carries no architecture either")
        // The runner is on an older build, and still says nothing: bringing it
        // forward is the gateway's job, not a reader's.
        XCTAssertFalse(runner.label.contains("1.3.0"), "an outdated machine names no version either")
        XCTAssertFalse(runner.label.contains("Update available"), "and is not called out of date")
        XCTAssertFalse(app.staticTexts["Update available"].exists,
                       "no row on the screen says an update is available")
        attach(name: "59-device-rows")
    }

    /// The Devices screen filters by platform the way Sessions filters by agent
    /// (owner's ruling, 2026-09-18): the same control, top right.
    func testPlatformFilterNarrowsTheDevicesToOnePlatform() {
        openDevices()
        let studio = deviceRow(DemoDevices.studio)
        let runner = deviceRow(DemoDevices.ci)
        XCTAssertTrue(studio.waitForExistence(timeout: 15), "the Macs are listed to start with")
        XCTAssertTrue(runner.exists, "and so is the Linux runner")

        app.buttons["devices.platformFilter"].tap()
        let linux = app.buttons["devices.platformFilter.linux"]
        XCTAssertTrue(linux.waitForExistence(timeout: 10), "the filter names the platforms present")
        XCTAssertTrue(app.buttons["devices.platformFilter.macos"].exists, "both of them")
        linux.tap()

        XCTAssertTrue(studio.waitForNonExistence(timeout: 10), "the Macs go")
        XCTAssertTrue(runner.exists, "the Linux runner stays")
        XCTAssertEqual(app.buttons["devices.platformFilter"].value as? String, "Linux",
                       "and the button says what the list is narrowed to")
        attach(name: "58-device-platform-filter")

        app.buttons["devices.platformFilter"].tap()
        app.buttons["devices.platformFilter.all"].tap()
        XCTAssertTrue(studio.waitForExistence(timeout: 10), "All brings the Macs back")
    }

    /// Amendment A38, rule 20: one swipe, in one order on both apps — Rename ·
    /// Retry update (only while one has failed) · Show quota · Revoke.
    func testDeviceRowSwipeHoldsTheMenuInRuleTwentysOrder() {
        openDevices()
        let studio = deviceRow(DemoDevices.studio)
        XCTAssertTrue(studio.waitForExistence(timeout: 15), "the machines are listed")

        // Amendment A36: a machine the gateway is keeping current has nothing
        // to update, so the swipe holds the three actions that are always there.
        studio.swipeLeft()
        let rename = app.buttons["device.rename"]
        let quota = app.buttons["device.showQuota"]
        let revoke = app.buttons["device.revoke"]
        XCTAssertTrue(rename.waitForExistence(timeout: 10), "one swipe offers Rename")
        XCTAssertTrue(quota.exists, "Show quota")
        XCTAssertTrue(revoke.exists, "and Revoke")
        XCTAssertFalse(app.buttons["device.retryUpdate"].exists,
                       "with nothing to retry on a machine whose update never failed")
        XCTAssertLessThan(rename.frame.minX, quota.frame.minX,
                          "read left to right the row says Rename, then Show quota")
        XCTAssertLessThan(quota.frame.minX, revoke.frame.minX,
                          "and Revoke nearest the edge")
        attach(name: "60-device-swipe-actions")

        rename.tap()
        XCTAssertTrue(app.alerts["Rename device"].waitForExistence(timeout: 10),
                      "and Rename opens the same sheet the menu opens")
        app.alerts["Rename device"].buttons["Cancel"].tap()

        // The one machine the gateway gave up on carries Retry update between
        // Rename and Show quota, and it is the only row that does.
        let laptop = deviceRow(DemoDevices.laptop)
        laptop.swipeLeft()
        let retry = app.buttons["device.retryUpdate"]
        XCTAssertTrue(retry.waitForExistence(timeout: 10),
                      "the failed machine's swipe offers Retry update")
        XCTAssertLessThan(app.buttons["device.rename"].frame.minX, retry.frame.minX,
                          "after Rename")
        XCTAssertLessThan(retry.frame.minX, app.buttons["device.showQuota"].frame.minX,
                          "before Show quota")
        XCTAssertLessThan(app.buttons["device.showQuota"].frame.minX,
                          app.buttons["device.revoke"].frame.minX, "and before Revoke")
        attach(name: "60b-device-swipe-retry")
    }

    /// Amendment A38, rule 20: tapping a machine that is online and offers a
    /// terminal opens a full-screen shell on it — the emulator, the status
    /// line, Close, and the key bar a phone needs.
    func testTappingADeviceOpensATerminalOnIt() {
        openDevices()
        let row = deviceRow(DemoDevices.studio)
        XCTAssertTrue(row.waitForExistence(timeout: 15), "the machines are listed")
        row.tap()

        XCTAssertTrue(app.navigationBars["mac-studio-office"].waitForExistence(timeout: 15),
                      "the row's own tap opens a shell, titled with the machine's name")
        XCTAssertTrue(app.buttons["terminal.close"].exists, "with Close at the trailing edge")

        let status = app.staticTexts["terminal.status"].firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 15),
                      "a thin status line stands under the title")
        _ = waitFor(timeout: 20) { status.label == "Connected" }

        // The key bar of rule 20, in the order the design lists it.
        let escape = app.buttons["terminal.key.escape"]
        XCTAssertTrue(escape.waitForExistence(timeout: 10), "Esc is the first cap")
        XCTAssertLessThan(escape.frame.minX, app.buttons["terminal.key.tab"].frame.minX,
                          "then Tab")
        XCTAssertLessThan(app.buttons["terminal.key.tab"].frame.minX,
                          app.buttons["terminal.key.control"].frame.minX, "then the sticky Ctrl")
        XCTAssertLessThan(app.buttons["terminal.key.control"].frame.minX,
                          app.buttons["terminal.key.up"].frame.minX, "then the arrows")

        // The emulator draws its own glyphs and publishes no text to the
        // accessibility tree, so what the shell printed is checked where it can
        // be read — `RCUIVerify` drives the same demo shell through
        // `TerminalSession` and reads the bytes. Here the screenshot is the
        // evidence, and the status line is the assertion.
        attach(name: "ios-round42-terminal")
        XCTAssertEqual(status.label, "Connected",
                       "and the line under the title says so once the shell is up")

        app.buttons["terminal.close"].tap()
        XCTAssertTrue(row.waitForExistence(timeout: 15), "and Close goes back to the list")
        XCTAssertFalse(app.buttons["terminal.close"].exists, "leaving the screen behind it")
    }

    /// The other half of rule 20: a machine that cannot be opened says why
    /// where it stands, and nothing is pushed.
    func testADeviceWithNoTerminalSaysSoInsteadOfOpeningOne() {
        openDevices()
        let laptop = deviceRow(DemoDevices.laptop)
        XCTAssertTrue(laptop.waitForExistence(timeout: 15), "the machines are listed")
        laptop.tap()

        // A list row is a cell, not a static text, so the notice is looked up
        // wherever SwiftUI put it — the same way the device rows are.
        let notice = app.descendants(matching: .any)["device.terminalRefusal"].firstMatch
        let shown = notice.waitForExistence(timeout: 10)
        attach(name: "91-device-tap-refused")
        XCTAssertTrue(shown, "a machine with the capability off answers under its own row")
        XCTAssertEqual(notice.label, "This device does not offer a terminal.",
                       "saying which of the two reasons it is")
        XCTAssertFalse(app.buttons["terminal.close"].exists, "and nothing is opened")

        deviceRow(DemoDevices.ci).tap()
        XCTAssertTrue(waitFor(timeout: 10) {
            app.descendants(matching: .any)["device.terminalRefusal"].firstMatch.label
                == "This device is offline."
        }, "and a machine that is not there says that instead, in the one place")
        XCTAssertFalse(app.buttons["terminal.close"].exists, "with nothing opened either")
    }

    /// The word for taking a machine's token away is Revoke on both apps, and
    /// the alert says what it costs before it acts.
    func testRevokingADeviceIsConfirmedByThatName() {
        openDevices()
        let row = deviceRow(DemoDevices.laptop)
        XCTAssertTrue(row.waitForExistence(timeout: 15), "the machines are listed")

        row.swipeLeft()
        let revoke = app.buttons["device.revoke"]
        XCTAssertTrue(revoke.waitForExistence(timeout: 10), "the swipe offers Revoke, not Remove")
        revoke.tap()

        let alert = app.alerts["Revoke device"]
        XCTAssertTrue(alert.waitForExistence(timeout: 10), "which confirms under the same word")
        XCTAssertTrue(alert.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "token stops working")).firstMatch.exists,
                      "and says what the machine loses")
        XCTAssertTrue(alert.buttons["Revoke device"].exists, "with the confirm named for the act")
        attach(name: "70-device-revoke-confirm")
        alert.buttons["Cancel"].tap()
    }

    /// Amendment A36: the gateway updates every machine by itself, so the only
    /// row that says anything is the one whose update failed — and the only
    /// action offered there is Retry update, which confirms what it costs and
    /// then carries the row through the update.
    func testFailedUpdateIsRetriedFromTheRowAndRunsToCompletion() {
        openDevices()
        let row = deviceRow(DemoDevices.laptop)
        XCTAssertTrue(row.waitForExistence(timeout: 15), "the machines are listed")
        XCTAssertTrue(anyElement(containing: "Update failed · the device did not come back"),
                      "the machine the gateway gave up on says so, in its own words")
        XCTAssertFalse(row.label.contains("1.3.0"),
                       "without the version it is stuck on")
        attach(name: "61-device-update-failed")

        row.swipeLeft()
        let retry = app.buttons["device.retryUpdate"]
        XCTAssertTrue(retry.waitForExistence(timeout: 10), "the one swipe offers Retry update")
        retry.tap()

        let alert = app.alerts["Update device"]
        XCTAssertTrue(alert.waitForExistence(timeout: 10), "which confirms before it acts")
        XCTAssertTrue(alert.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "to \(AppBuild.shipped)?")).firstMatch.exists,
                      "and names the version it would land on")
        XCTAssertTrue(alert.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "service restarts")).firstMatch.exists,
                      "and says what it costs the device")
        attach(name: "62-device-update-confirm")
        alert.buttons["Update"].tap()

        XCTAssertTrue(app.staticTexts["Updating…"].waitForExistence(timeout: 15),
                      "the row reports the update it asked for")
        attach(name: "63-device-updating")

        XCTAssertTrue(waitFor(timeout: 30) {
            let label = deviceRow(DemoDevices.laptop).label
            return !label.contains("Updating…") && !label.contains("Update failed")
        }, "and falls silent once the device is back: a current machine says nothing at all")
        let settled = deviceRow(DemoDevices.laptop).label
        XCTAssertFalse(settled.contains(AppBuild.shipped), "no version on the row it came back to")
        XCTAssertFalse(settled.contains("3f2b4a9c"), "and never the build hash")
        XCTAssertFalse(app.buttons["device.retryUpdate"].exists, "with nothing left to retry")
        attach(name: "64-device-updated")
    }

    /// The machine's own page says no more about its client than its row does
    /// (`docs/DESIGN.md` § "A device keeps itself current"): no version, no
    /// build, and the same notice with a retry beside it where one failed.
    func testDevicePageStatesNoClientVersionAndCarriesTheRetry() {
        openDevices()
        openDevicePage(DemoDevices.laptop)

        XCTAssertTrue(app.descendants(matching: .any)["device.page"].waitForExistence(timeout: 15))
        XCTAssertTrue(anyText(containing: "macbook-air.local · arm64"),
                      "the page's facts are the hostname and the architecture")
        let notice = app.staticTexts["device.updateNotice"].firstMatch
        XCTAssertTrue(notice.waitForExistence(timeout: 10),
                      "and it carries the same notice the row carries")
        XCTAssertTrue(notice.label.contains("the device did not come back"), "in the same words")
        XCTAssertTrue(app.buttons["device.retryUpdate"].exists, "with Retry update beside it")
        XCTAssertFalse(onScreen(containing: "client "), "the page states no client version")
        XCTAssertFalse(onScreen(containing: "1.3.0"), "not even the one the machine is stuck on")
        XCTAssertFalse(onScreen(containing: "3f2b4a9c"), "and no build hash")
        attach(name: "65-device-page-update-failed")

        app.buttons["device.retryUpdate"].tap()
        let alert = app.alerts["Update device"]
        XCTAssertTrue(alert.waitForExistence(timeout: 10), "which confirms, naming the version")
        XCTAssertTrue(alert.staticTexts.containing(
            NSPredicate(format: "label CONTAINS %@", "to \(AppBuild.shipped)?")).firstMatch.exists,
                      "the same confirmation the row shows")
        alert.buttons["Cancel"].tap()
    }

    /// Amendment A33: the machine's page — the agents on it, how each is
    /// signed in, and what is left of each account's quota. Since A38 it is
    /// reached from the row's menu as **Show quota**, because the row's own tap
    /// opens a terminal; the row's actions stay on the row and are not repeated.
    func testDevicePageShowsHowEachAgentIsSignedInAndWhatIsLeft() {
        openDevices()
        openDevicePage(DemoDevices.studio)

        // The windows are read on request, so the meters say they are coming.
        // This is the first thing the page does, so it is the first thing
        // looked at: the scripted device answers three seconds later.
        let checking = app.descendants(matching: .any)["device.quota.checking"].firstMatch
        XCTAssertTrue(checking.waitForExistence(timeout: 15),
                      "the page asks the device the moment it opens")
        attach(name: "80-device-page-checking")

        let page = app.descendants(matching: .any)["device.page"]
        XCTAssertTrue(page.exists, "the row's Show quota opens the machine's page")
        // `docs/DESIGN.md` § "The device row": what the row dropped is checked
        // here, in one line under the name the navigation bar carries.
        XCTAssertTrue(anyText(containing: "mac-studio.local · arm64"),
                      "the page keeps the hostname and the architecture the row no longer shows")
        // Amendment A36: and says nothing about the client of a machine the
        // gateway is keeping current.
        XCTAssertFalse(app.staticTexts["device.updateNotice"].exists,
                       "a machine being kept current says nothing about its client")
        XCTAssertFalse(onScreen(containing: "client "), "no version on the page either")
        XCTAssertFalse(onScreen(containing: "3f2b4a9c"), "and no build hash")
        for agent in ["claude", "codex", "grok", "pi"] {
            XCTAssertTrue(app.descendants(matching: .any)["device.agent.\(agent)"].exists,
                          "every agent the machine found has a card")
        }
        XCTAssertFalse(app.buttons["device.revoke"].exists,
                       "the three row actions are not repeated on the page")

        let meters = app.descendants(matching: .any)["device.quota.meters"].firstMatch
        XCTAssertTrue(meters.waitForExistence(timeout: 20), "and draws the windows it answers with")
        XCTAssertTrue(meters.label.contains("5-hour"), "a window is named by its length")
        XCTAssertTrue(meters.label.contains("%"), "with the share it has spent")
        XCTAssertTrue(anyElement(containing: "7-day · Fable"),
                      "and a weekly window confined to one model says which")
        XCTAssertTrue(anyText(containing: "Anthropic account · Max · Max 5x · me@example.com"),
                      "an account names its vendor, its plan, its tier and its email")
        XCTAssertTrue(anyText(containing: "xAI account"),
                      "a vendor with no window to report still says how it is signed in")
        XCTAssertTrue(anyText(containing: "OpenAI API key · api.relay.example"),
                      "and a key names its vendor and the host it is sent to")
        attach(name: "81-device-page-quota")
    }

    /// A machine that is not there to ask keeps the credentials it last
    /// reported and says so where the meters go.
    func testOfflineDevicePageKeepsItsAccountsAndSaysWhyThereAreNoMeters() {
        openDevices()
        openDevicePage(DemoDevices.ci)

        XCTAssertTrue(app.descendants(matching: .any)["device.page"].waitForExistence(timeout: 15))
        XCTAssertTrue(anyText(containing: "OpenAI account"),
                      "the account line is the device's last word, not a live one")
        let offline = app.staticTexts["device.quota.offline"].firstMatch
        XCTAssertTrue(offline.waitForExistence(timeout: 10),
                      "and the meters say the machine cannot be asked")
        XCTAssertFalse(app.descendants(matching: .any)["device.quota.meters"].exists,
                       "with no meter drawn from a stale figure")
        attach(name: "82-device-page-offline")
    }

    /// The two shapes that are not a meter: a window the device could not read,
    /// which says why in the device's own words, and an agent installed and
    /// signed in nowhere.
    func testDevicePageSaysWhyAQuotaIsMissingAndWhenNothingIsSignedIn() {
        openDevices()
        openDevicePage(DemoDevices.laptop)

        XCTAssertTrue(app.descendants(matching: .any)["device.page"].waitForExistence(timeout: 15))
        let reason = app.descendants(matching: .any)["device.quota.error"].firstMatch
        XCTAssertTrue(reason.waitForExistence(timeout: 20),
                      "a check the device could not make says why, in one line")
        XCTAssertTrue(reason.label.contains("expired"), "in the device's own words")
        XCTAssertFalse(app.descendants(matching: .any)["device.quota.meters"].exists,
                       "and draws no meter beside it")
        XCTAssertEqual(app.staticTexts["device.agent.grok.signIn"].firstMatch.label,
                       "Not signed in", "an agent signed in nowhere says so")
        attach(name: "84-device-page-no-quota")
    }

    /// The glow around the display while dictating. It is level-driven, so the
    /// scripted platform is pinned at rest, at conversational speech and at the
    /// top of its range, and each is screenshotted.
    func testListeningGlowIsSeenAtEveryLevel() {
        for level in ["0", "0.5", "1"] {
            app = XCUIApplication()
            app.launchArguments = ["--ui-testing", "--demo", "--reset-state", "--voice-preview",
                                   "--voice-level=\(level)"]
            app.launch()
            openLiveSession()
            XCTAssertTrue(promptField().waitForExistence(timeout: 15))
            app.buttons["composer.voice"].tap()
            let done = app.buttons["voice.done"]
            XCTAssertTrue(done.waitForExistence(timeout: 15), "dictation is listening")
            XCTAssertTrue(waitFor { done.isEnabled })
            // The glow rises fast and falls slow; half a second is past both,
            // so the frame is this level's steady state rather than a rise.
            usleep(500_000)
            attach(name: "83-voice-glow-level-\(level)")
            done.tap()
            app.terminate()
        }
    }

    /// Amendment A23: the whole scan flow, with the camera replaced by the
    /// stand-in the demo injects — a simulator has none.
    func testScanningAPrintedCodePairsTheHost() {
        openDevices()
        let add = app.buttons["devices.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 15), "adding a device is a labelled button")
        add.tap()

        let scan = app.buttons["pairing.scan"]
        XCTAssertTrue(scan.waitForExistence(timeout: 15), "the sheet offers the scan flow")
        scan.tap()

        XCTAssertTrue(app.staticTexts["Pair with Remote Control"].waitForExistence(timeout: 10),
                      "the camera opens with the two steps over it")
        XCTAssertTrue(app.staticTexts["scan.command"].label.contains("install.sh | sh"),
                      "step one is the one-liner the host runs")
        XCTAssertTrue(app.buttons["scan.copy"].exists, "which can be copied")
        XCTAssertTrue(app.staticTexts["scan.status"].label.contains("Hold steady"),
                      "and the strip says what the camera is doing")
        attach(name: "65-scan-overlay")

        app.buttons["scan.simulate"].tap()
        XCTAssertTrue(app.staticTexts["pairing.steps"].waitForExistence(timeout: 15)
                      || app.staticTexts["Gateway ready"].waitForExistence(timeout: 15),
                      "a claimed code drops back to the progress the code flow shows")
        XCTAssertTrue(app.staticTexts["RC-9M27-TB4K"].waitForExistence(timeout: 10),
                      "and the sheet shows the code the gateway minted for that host")
        attach(name: "66-scan-claimed")
        XCTAssertTrue(app.staticTexts["Device online"].waitForExistence(timeout: 20),
                      "the host's progress arrives on the claimed code")
        attach(name: "67-scan-progress")
    }

    /// The Photos item in the `+` menu opens the picker. It did nothing on the
    /// owner's phone while Files and Camera worked, because a `PhotosPicker`
    /// built inside a `Menu` leaves the hierarchy when the menu closes and its
    /// sheet never arrives.
    func testPhotosOpensThePickerFromTheAttachMenu() {
        app.launch()
        openLiveSession()

        let attach = app.buttons["composer.attach"]
        XCTAssertTrue(attach.waitForExistence(timeout: 20), "the composer offers attachments")
        attach.tap()

        let photos = app.buttons["Photos"]
        XCTAssertTrue(photos.waitForExistence(timeout: 10), "the menu offers Photos")
        photos.tap()

        XCTAssertTrue(photoPicker().waitForExistence(timeout: 20),
                      "and tapping it opens the photo picker")
        self.attach(name: "68-photos-picker")
    }

    // MARK: - Accounts (A24)

    /// `docs/DESIGN.md` § "Accounts": the form asks for the gateway, the
    /// username and the password, and offers no way to create an account on a
    /// gateway that is not taking them.
    func testSignInAsksForAUsernameAndOffersNoRegistrationWhenItIsClosed() {
        launchSignedOut()

        let gateway = app.textFields["login.gateway"]
        XCTAssertTrue(gateway.waitForExistence(timeout: 20), "the form asks for a gateway")
        XCTAssertTrue(app.textFields["login.username"].exists, "and for a username")
        XCTAssertTrue(app.secureTextFields["login.password"].exists, "and for a password")
        XCTAssertTrue(app.textFields["login.username"].frame.minY > gateway.frame.minY,
                      "in that order, gateway first")
        attach(name: "71-sign-in-form")

        typeGateway()
        XCTAssertFalse(app.buttons["login.register"].waitForExistence(timeout: 5),
                       "a gateway that is not taking accounts offers no way to create one")
    }

    /// A disabled account is told so, and a wrong password is not told which
    /// half was wrong.
    func testDisabledAccountIsToldSoAndAWrongOneIsNot() {
        launchSignedOut()
        signIn(username: "bob", password: "correct horse")

        let error = app.staticTexts["login.error"]
        XCTAssertTrue(error.waitForExistence(timeout: 15), "a refused sign-in says why")
        XCTAssertEqual(error.label, "This account is disabled.")
        attach(name: "72-disabled-account")

        // "bobby" is nobody on this gateway, which reads the same as a wrong
        // password: the form never says which half it did not recognise.
        let name = app.textFields["login.username"]
        name.tap()
        name.typeText("by")
        app.buttons["login.connect"].tap()
        XCTAssertTrue(waitFor(error, label: "Wrong username or password.", timeout: 15),
                      "and an unknown account is not told that it is unknown")
    }

    /// "Create an account" appears only when the gateway reports registration
    /// open, and swaps the card for the registration form.
    func testRegistrationLinkAppearsOnlyWhenTheGatewayIsOpen() {
        launchSignedOut(extra: ["--registration-open"])
        typeGateway()

        let create = app.buttons["login.register"]
        XCTAssertTrue(create.waitForExistence(timeout: 15),
                      "a gateway taking accounts offers to create one")
        XCTAssertTrue(create.frame.minY > app.buttons["login.connect"].frame.minY,
                      "under the button, where the design puts it")
        attach(name: "73-registration-offered")

        create.tap()
        let back = app.buttons["login.signInInstead"]
        XCTAssertTrue(back.waitForExistence(timeout: 10), "with a way back to signing in")
        XCTAssertTrue(app.textFields["login.username"].exists, "the card asks for a username")
        XCTAssertTrue(app.secureTextFields["login.password"].exists, "and a password")
        attach(name: "74-registration-form")

        app.textFields["login.username"].tap()
        app.textFields["login.username"].typeText("carol")
        app.secureTextFields["login.password"].tap()
        app.secureTextFields["login.password"].typeText("correct horse battery staple")
        app.buttons["login.connect"].tap()

        XCTAssertTrue(app.tabBars.buttons["Settings"].waitForExistence(timeout: 25),
                      "creating an account signs it in")
    }

    /// The accounts screen is the admin's and nobody else's: a member never
    /// sees the row, and gets the row an admin does not.
    func testOnlyAnAdminIsOfferedTheUsersScreen() {
        launchSignedOut()
        signIn(username: "admin", password: "correct horse")
        openSettingsTab()

        XCTAssertTrue(app.buttons["settings.users"].waitForExistence(timeout: 20),
                      "an admin is offered the accounts screen")
        XCTAssertFalse(app.buttons["settings.changePassword"].exists,
                       "and not a password it cannot change, because it is the gateway's own")
        let header = app.descendants(matching: .any)["settings.identity"]
        XCTAssertTrue(header.exists, "the header says who is signed in")
        XCTAssertTrue(header.label.contains("admin") && header.label.contains("Admin"),
                      "with the account and the role it has, and not a row for either")
        attach(name: "75-settings-admin")

        app.buttons["settings.signOut"].tap()
        // The confirmation is its own sheet; the row behind it carries the same
        // words, so the button is taken from the sheet rather than by label.
        let confirm = app.sheets.buttons["Sign out"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "signing out asks first")
        confirm.tap()

        signIn(username: "alice", password: "correct horse")
        openSettingsTab()
        XCTAssertTrue(app.buttons["settings.changePassword"].waitForExistence(timeout: 20),
                      "a member can change its own password")
        XCTAssertFalse(app.buttons["settings.users"].exists,
                       "and is never offered the accounts screen")
        attach(name: "76-settings-member")
    }

    /// The screen itself: the switch at the top, one row per account, the
    /// actions on a swipe, and Add user in the bottom bar where Add device and
    /// New session sit.
    func testUsersScreenListsAccountsAndAddsOne() {
        launchSignedOut()
        signIn(username: "admin", password: "correct horse")
        openSettingsTab()
        app.buttons["settings.users"].tap()

        let registration = app.switches["users.registration"]
        XCTAssertTrue(registration.waitForExistence(timeout: 20), "the registration switch is at the top")
        let operatorRow = app.descendants(matching: .any)["user.admin"].firstMatch
        XCTAssertTrue(operatorRow.waitForExistence(timeout: 10), "the operator has a row")
        XCTAssertTrue(app.descendants(matching: .any)["user.alice"].firstMatch.exists,
                      "and so does every other account")
        XCTAssertTrue(operatorRow.frame.minY > registration.frame.minY,
                      "with the accounts under the switch")
        let add = app.buttons["users.add"]
        XCTAssertTrue(add.exists, "Add user is the screen's primary button")
        XCTAssertTrue(add.frame.minY > operatorRow.frame.minY, "in the bottom bar")
        attach(name: "77-users-screen")

        // The operator's row offers nothing; another account's offers three.
        operatorRow.swipeLeft()
        XCTAssertFalse(app.buttons["user.delete"].waitForExistence(timeout: 3),
                       "the admin row has no actions to swipe to")

        app.descendants(matching: .any)["user.alice"].firstMatch.swipeLeft()
        XCTAssertTrue(app.buttons["user.delete"].waitForExistence(timeout: 10),
                      "a member's row swipes to Delete")
        XCTAssertTrue(app.buttons["user.disable"].exists, "Disable")
        XCTAssertTrue(app.buttons["user.reset"].exists, "and Reset password")
        XCTAssertTrue(app.buttons["user.reset"].frame.minX < app.buttons["user.delete"].frame.minX,
                      "reading Reset · Disable · Delete from the inside out")
        attach(name: "78-users-swipe-actions")

        // Deleting asks first and names what goes with the account. The row is
        // read while the tap is still being handled, because dismissing an
        // alert clears the state a task started from it would have read.
        app.buttons["user.delete"].tap()
        let confirm = app.alerts.buttons["Delete account"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "deleting asks first")
        let named = app.alerts.staticTexts.allElementsBoundByIndex
            .contains { $0.label.contains("alice") && $0.label.contains("1 device") }
        XCTAssertTrue(named, "and names the account and the devices that go with it")
        attach(name: "79-users-delete-confirm")
        confirm.tap()
        XCTAssertTrue(waitForAbsence(app.descendants(matching: .any)["user.alice"].firstMatch,
                                     timeout: 15),
                      "and the row goes")

        add.tap()
        let username = app.textFields["addUser.username"]
        XCTAssertTrue(username.waitForExistence(timeout: 10), "the sheet asks for a username")
        XCTAssertTrue(app.secureTextFields["addUser.password"].exists, "a password")
        XCTAssertTrue(app.segmentedControls["addUser.role"].exists, "and a role")
        attach(name: "80-add-user-sheet")

        username.tap()
        username.typeText("dave")
        app.secureTextFields["addUser.password"].tap()
        app.secureTextFields["addUser.password"].typeText("correct horse battery staple")
        app.buttons["addUser.add"].tap()

        XCTAssertTrue(app.descendants(matching: .any)["user.dave"].firstMatch.waitForExistence(timeout: 15),
                      "and the account it made is a row on the screen")
        attach(name: "81-users-after-add")
    }

    /// The sign-in form is reached with the offline gateway behind it, so every
    /// account answer can be driven without a gateway to reach.
    private func launchSignedOut(extra: [String] = []) {
        app.launchArguments = ["--ui-testing", "--demo-account", "--reset-state"] + extra
        app.launch()
    }

    /// The gateway the offline account tests sign in to. It is deliberately not
    /// the field's own placeholder, so an empty field can be told from a filled
    /// one: `value` reports the placeholder when there is nothing in it.
    private static let testGateway = "https://rc.test.example"

    private func typeGateway() {
        let gateway = app.textFields["login.gateway"]
        XCTAssertTrue(gateway.waitForExistence(timeout: 20), "the form is up")
        clear(gateway)
        gateway.typeText(Self.testGateway)
        XCTAssertEqual(gateway.value as? String, Self.testGateway,
                       "the address field holds exactly what was typed")
    }

    /// Signing in after signing out meets a form that remembers the gateway and
    /// the last username, which is the point of it, so both are cleared first.
    private func signIn(username: String, password: String) {
        typeGateway()
        let name = app.textFields["login.username"]
        clear(name)
        name.typeText(username)
        let secret = app.secureTextFields["login.password"]
        secret.tap()
        secret.typeText(password)
        app.buttons["login.connect"].tap()
    }

    /// Tapping the middle of a filled field leaves the caret in the middle of
    /// the text, and a delete only removes what is behind it, so the field ends
    /// up holding the tail of the old value and the whole of the new one. The
    /// trailing edge puts the caret after the last character.
    private func clear(_ field: XCUIElement) {
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
        let value = (field.value as? String) ?? ""
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: value.count))
    }

    private func openSettingsTab() {
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 25), "the app is up")
        settings.tap()
    }

    /// Wait for an element to go away. A row is removed by a reply from the
    /// gateway, so it is still on screen when the tap returns.
    private func waitForAbsence(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if !element.exists { return true }
            _ = element.waitForNonExistence(timeout: 0.4)
        }
        return !element.exists
    }

    /// Wait for one element's label to become what it should be. A refusal
    /// replaces the sentence in place, so existence alone proves nothing.
    private func waitFor(_ element: XCUIElement, label: String, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.exists, element.label == label { return true }
            _ = element.waitForExistence(timeout: 0.4)
        }
        return false
    }

    /// The demo device ids, which are the row identifiers.
    private enum DemoDevices {
        static let studio = "demo-mac-studio"
        static let laptop = "demo-macbook-air"
        static let ci = "demo-ci-runner"
    }

    /// Any label on screen holding this text. A card's lines are separate
    /// elements, so a sentence is looked for rather than matched whole.
    private func anyText(containing text: String) -> Bool {
        app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", text))
            .firstMatch.waitForExistence(timeout: 10)
    }

    /// Whether anything on screen says this, asked once. A word that must not
    /// be there is looked for without waiting for it to turn up.
    private func onScreen(containing text: String) -> Bool {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", text))
            .firstMatch.exists
    }

    /// The same, for a row that combines its children into one element and so
    /// is no longer a static text.
    private func anyElement(containing text: String) -> Bool {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", text))
            .firstMatch.waitForExistence(timeout: 10)
    }

    private func openDevices() {
        app.launch()
        let devices = app.tabBars.buttons["Devices"]
        XCTAssertTrue(devices.waitForExistence(timeout: 20), "the Devices tab is there")
        devices.tap()
    }

    /// A combined accessibility element is not a button, so the row is looked
    /// up wherever SwiftUI decided to put it.
    private func deviceRow(_ deviceID: String) -> XCUIElement {
        app.descendants(matching: .any)["device.\(deviceID)"].firstMatch
    }

    /// Amendment A38, rule 20: the row's own tap opens a terminal, so the
    /// machine's page is reached from the row's menu as **Show quota**.
    private func openDevicePage(_ deviceID: String) {
        let row = deviceRow(deviceID)
        XCTAssertTrue(row.waitForExistence(timeout: 15), "the machines are listed")
        row.swipeLeft()
        let quota = app.buttons["device.showQuota"]
        XCTAssertTrue(quota.waitForExistence(timeout: 10), "the swipe offers Show quota")
        quota.tap()
    }

    /// The system photo picker runs out of process and is titled in the phone's
    /// own language, so it is found by the identifier its own collection
    /// carries rather than by a word.
    private func photoPicker() -> XCUIElement {
        app.descendants(matching: .any)["photosView_content_scroll_view"].firstMatch
    }

    // MARK: - A29, polishing what was dictated

    /// `docs/DESIGN.md` § "Polishing what you dictated": the words land at once,
    /// the status line says the model is working, and the dictated span alone is
    /// replaced with "Polished · Undo" under the field until the next edit.
    ///
    /// § "The composer" → **Done becomes a spinner, and the spinner becomes
    /// Send**: the slot Done stood in holds a spinner for as long as the words
    /// are on their way, and Send is not offered until they are back.
    func testDictationIsPolishedAndOneUndoAway() {
        app.launch()
        turnPolishOn()
        openLiveSession()
        XCTAssertTrue(promptField().waitForExistence(timeout: 15))

        // Where Send stands before a word is spoken, so the spinner can be held
        // against it.
        let sendFrame = app.buttons["composer.send"].frame
        app.buttons["composer.voice"].tap()
        let done = app.buttons["voice.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 15), "dictation is listening")
        XCTAssertTrue(waitFor { done.isEnabled })
        done.tap()

        // The words the recogniser produced are in the field the instant
        // dictation ends, fillers and all.
        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15))
        let dictated = field.value as? String ?? ""
        XCTAssertTrue(dictated.contains("the the"), "the dictated words land unpolished")

        // The tap on Done was answered at once, and the slot is still not
        // something anyone can tap. The demo answers in three seconds, so what
        // has to be looked at while the spinner is up is read first and asserted
        // afterwards: every query costs a fraction of that window.
        let working = app.descendants(matching: .any)["composer.working"].firstMatch
        XCTAssertTrue(working.waitForExistence(timeout: 15),
                      "the tap on Done is answered with a spinner in Send's slot")
        let workingFrame = working.frame
        XCTAssertFalse(app.buttons["composer.send"].exists,
                       "Send is not offered while the model is still writing")

        let status = app.descendants(matching: .any)["chat.status"]
        XCTAssertTrue(waitFor(timeout: 15) { status.exists && status.label.contains("Polishing") },
                      "the status line says the model is working")
        attach(name: "ios-round28-voice-working")

        XCTAssertLessThan(abs(workingFrame.maxX - sendFrame.maxX), 2,
                          "the spinner stands where Send stands, against the trailing edge")
        XCTAssertEqual(workingFrame.height, sendFrame.height, accuracy: 2, "at Send's size")
        XCTAssertFalse(app.buttons["voice.done"].exists, "and Done went with the microphone")

        let note = app.descendants(matching: .any)["composer.polished"]
        XCTAssertTrue(note.waitForExistence(timeout: 20), "the note says the draft was polished")
        let polished = promptField().value as? String ?? ""
        XCTAssertFalse(polished.contains("the the"), "the doubled word is gone")
        XCTAssertFalse(polished.contains("um "), "and so is the filler")

        // The field holds what will be sent, so the slot is Send again.
        XCTAssertTrue(app.buttons["composer.send"].waitForExistence(timeout: 10),
                      "the spinner becomes Send the moment the words are back")
        XCTAssertTrue(waitForAbsence(working, timeout: 10), "and nothing is left spinning")
        attach(name: "ios-round28-voice-send")

        app.buttons["composer.polishUndo"].tap()
        XCTAssertEqual(promptField().value as? String, dictated,
                       "Undo puts the words back exactly as they were dictated")
        XCTAssertFalse(app.descendants(matching: .any)["composer.polished"].exists,
                       "and the note goes with them")
    }

    /// The switch, the model and the strength are the person's own settings,
    /// off until they ask for them, and the group says what is sent and when.
    func testVoiceSettingsOfferPolish() {
        app.launch()
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 20))
        settings.tap()

        let toggle = app.switches["settings.polish"]
        XCTAssertTrue(scrollDown(to: toggle), "the Voice group offers dictation polish")
        XCTAssertTrue(toggle.isEnabled, "the demo gateway has a model, so the switch is live")
        XCTAssertEqual(toggle.value as? String, "0", "off on a fresh install")
        XCTAssertFalse(app.descendants(matching: .any)["settings.polishModel"].exists,
                       "with nothing under it until it is on")

        turnOn(toggle)
        XCTAssertTrue(app.descendants(matching: .any)["settings.polishModel"]
            .waitForExistence(timeout: 10), "turning it on offers the gateway's models")
        XCTAssertTrue(app.descendants(matching: .any)["settings.polishStrength"].exists,
                      "and how hard the model may work")
        attach(name: "ios-polish-settings")
    }

    // MARK: - A30 and A34, messages from other agents

    /// `docs/DESIGN.md` § "The timeline" → "Messages from other agents": what a
    /// teammate session reported is the agent's side of the conversation, drawn
    /// on the left as a muted block and hidden at Simple with the rest of the
    /// agent's working.
    func testAgentMessageSitsOnTheAgentsSideAndSimpleHidesIt() {
        app.launch()
        openSharedSession()

        // Simple is the default. Amendment A34: a teammate's report is the
        // agent's working, so this level does not draw it at all.
        XCTAssertTrue(text(containing: "Read the fact sheet").waitForExistence(timeout: 20),
                      "the transcript around it is there")
        XCTAssertFalse(app.descendants(matching: .any)["chat.message.agent"].exists,
                       "and what another agent filed is not drawn at Simple")
        attach(name: "ios-agent-message-simple")

        app.navigationBars.buttons.firstMatch.tap()
        chooseDetailedTranscript()
        openSharedSession()

        let transcript = app.scrollViews["chat.transcript"]
        XCTAssertTrue(transcript.waitForExistence(timeout: 20), "the conversation is open again")
        let row = app.descendants(matching: .any)["chat.message.agent"].firstMatch
        // The transcript opens at its newest message and lays its rows out
        // lazily, and the report sits near the top; whether it is on screen when
        // the conversation opens depends on how much the demo has said below it
        // by then, so it is scrolled into view rather than waited for.
        XCTAssertTrue(scrollUp(to: row, in: transcript),
                      "Detailed draws it with the agent's other workings")
        XCTAssertTrue(row.label.contains("From another agent"),
                      "and never says the person said it")
        XCTAssertTrue(app.staticTexts["from another agent"].exists,
                      "the caption stands above the words nobody typed")

        // It sits on the left with the agent's own output, not on the right
        // where the person's bubble hugs its text.
        let mine = app.descendants(matching: .any)["chat.message"].firstMatch
        XCTAssertTrue(mine.exists, "the person's own message is on the same screen")
        XCTAssertLessThan(row.frame.minX, mine.frame.minX,
                          "the agent's block starts at the leading margin")
        XCTAssertGreaterThan(row.frame.width, mine.frame.width,
                             "at the width assistant text uses rather than hugging its words")
        attach(name: "ios-agent-message-detailed")
    }

    // MARK: - A31, an app older than its gateway

    /// Protocol 8.16: below the gateway's minimum the app shows one screen and
    /// nothing else — the two versions, where to get a newer build, Sign out.
    func testAppBelowTheGatewayMinimumShowsOnlyTheUpdateScreen() {
        app.launchArguments += ["--demo-update-required"]
        app.launch()

        let title = app.staticTexts["update.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20), "the blocking screen is up")
        XCTAssertEqual(title.label, "Update required", "and says what is required")
        XCTAssertTrue(app.staticTexts["update.versions"].firstMatch.exists,
                      "with this build's version and the one the gateway asks for")
        XCTAssertTrue(app.buttons["update.open"].exists, "with somewhere to get the newer build")
        XCTAssertTrue(app.buttons["update.signOut"].exists, "and a way to another gateway")
        attach(name: "ios-update-required")
    }

    // MARK: - Round 29

    /// `docs/DESIGN.md` § "Status vocabulary" → **A notification opens its
    /// session in place**: a link for session B while session A is open
    /// replaces A with B, Back returns to the list rather than to A, and B is
    /// streaming with its composer the moment it is on screen.
    ///
    /// The conversation B replaced used to close B's own store on its way out —
    /// SwiftUI delivers the covered view's `onDisappear` after the new view's
    /// task — and what was left on screen was a spinner with no composer under
    /// it that no amount of waiting would resolve.
    func testALinkOpensItsSessionOverAnOpenOneAndKeepsItsComposer() {
        app.launch()
        openLiveSession()
        XCTAssertTrue(promptField().waitForExistence(timeout: 20),
                      "the first conversation is open")

        XCUIDevice.shared.system.open(
            URL(string: "remotecontrol://session?device=demo-mac-studio&id=demo-session-vite")!)

        XCTAssertTrue(waitFor(timeout: 30) { app.staticTexts["chat.status"].exists
                                             || app.buttons["chat.takeover"].exists
                                             || promptField().exists },
                      "the linked session is on screen")
        XCTAssertTrue(promptField().waitForExistence(timeout: 20),
                      "with its composer, not a spinner that waits for a tap")
        attach(name: "ios-link-opens-in-place")

        // One conversation on the stack, so Back is the list.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 15),
                      "and Back returns to the list, not to the session it replaced")
    }

    /// `docs/DESIGN.md` § "The composer" → **Attachments are named for what
    /// they are**: "The Camera item is offered only where a camera exists."
    /// A simulator has none, and presenting the picker there raises rather than
    /// refusing.
    func testAttachMenuOffersNoCameraWhereThereIsNone() {
        app.launch()
        openLiveSession()

        let attach = app.buttons["composer.attach"]
        XCTAssertTrue(attach.waitForExistence(timeout: 20), "the composer offers attachments")
        attach.tap()

        XCTAssertTrue(app.buttons["Files"].waitForExistence(timeout: 10), "the menu offers Files")
        XCTAssertTrue(app.buttons["Photos"].exists, "and Photos")
        XCTAssertFalse(app.buttons["Camera"].exists,
                       "and nothing for a camera this machine does not have")
        self.attach(name: "ios-attach-menu-no-camera")
    }

    /// Fixed frames clip at accessibility text sizes. The Send circle and the
    /// attachment pill scale with the type, as the command panel's rows already
    /// do.
    func testSendCircleGrowsWithAccessibilityText() {
        app.launchArguments += ["-UIPreferredContentSizeCategoryName",
                                "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        openLiveSession()

        let send = app.buttons["composer.send"]
        XCTAssertTrue(send.waitForExistence(timeout: 20), "the send button is on screen")
        XCTAssertGreaterThan(send.frame.height, 48,
                             "and its circle is bigger than the default 48 pt at AX5")
        attach(name: "ios-send-circle-accessibility-size")
    }

    /// A string a store built with `L10n.string` and kept goes on saying what it
    /// said in the language it was built in. The sentence under a settings row
    /// is built at read time and its group holds the language, so it follows
    /// the preference on the screen that changes it rather than waiting for the
    /// screen to be left and re-entered.
    func testARowSentenceFollowsAChangeOfLanguage() {
        app.launch()
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 20), "the Settings tab is there")
        settings.tap()

        // The row combines its title, its sentence and its control into one
        // element, so it is found by what it reads rather than by an identifier.
        func row(reading sentence: String) -> XCUIElement {
            app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS %@", sentence)).firstMatch
        }
        XCTAssertTrue(row(reading: "a minute after the limit resets").waitForExistence(timeout: 15),
                      "the resume row explains itself in English to start with")

        let language = app.segmentedControls["settings.language"]
        XCTAssertTrue(scrollDown(to: language), "the interface language is a segmented control")
        language.buttons["中文"].tap()

        // Language sits in Reading, below the group the sentence is in, so
        // reaching it scrolled that row away and a `List` recycles what it no
        // longer shows. The screen was never left: the row is scrolled back to.
        var reads = false
        for _ in 0..<8 where !reads {
            reads = row(reading: "限制重置一分钟后").exists
            if !reads { app.swipeDown() }
        }
        XCTAssertTrue(reads, "and the sentence is in the new language without leaving the screen")
        attach(name: "ios-sentence-follows-language")
    }

    // MARK: - A35, a session the usage limit stopped

    /// Settings, "While you're away": the one switch the account owns, with the
    /// sentence that says what it does. The demo gateway carries preferences,
    /// so the switch is live rather than shown disabled.
    func testAwayGroupOffersTheResumeSwitch() {
        app.launch()
        openSettingsTab()

        let toggle = app.switches["settings.resumeAfterLimit"]
        XCTAssertTrue(scrollDown(to: toggle), "the away group holds the resume switch")
        XCTAssertTrue(app.staticTexts["While you're away"].exists,
                      "under a group named for what it is")
        XCTAssertTrue(anyText(containing: "a minute after the limit resets"),
                      "and the sentence under it says what the device will do")

        XCTAssertEqual(toggle.value as? String, "1",
                       "the demo account has it on, so the live switch can be read")
        attach(name: "ios-round33-resume-settings")

        // It is the account's, not this phone's: the switch writes through the
        // gateway, and what comes back is what it draws.
        turnOff(toggle)
        turnOn(toggle)
    }

    /// A session the five-hour window stopped: the notice above the transcript
    /// names the time it comes back, Change opens a picker with the bounds, and
    /// Cancel takes the resume away at once.
    func testPausedSessionShowsItsResumeAndCancelsIt() {
        app.launch()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 20))

        let row = app.buttons["session.\(DemoFixtures.pausedSessionID)"]
        XCTAssertTrue(scrollDown(to: row), "the paused session is listed")
        row.tap()

        let notice = app.staticTexts["chat.resumeNotice"]
        XCTAssertTrue(notice.waitForExistence(timeout: 15), "it carries a notice above the transcript")
        XCTAssertTrue(notice.label.hasPrefix("Paused by the usage limit"),
                      "naming what happened and when it comes back")
        XCTAssertTrue(anyText(containing: "Paused by the usage limit"),
                      "which says what happened and when it resumes")
        // The timeline says the same thing in the past tense.
        XCTAssertTrue(anyText(containing: "Ended at the usage limit"),
                      "and the turn that ran into the limit ends with it")
        XCTAssertTrue(anyText(containing: "Resume scheduled for"),
                      "with the device's own row under it")
        attach(name: "ios-round33-resume-banner")

        // Change opens the smallest time picker the platform has.
        app.buttons["notice.action"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["resume.picker"].waitForExistence(timeout: 10),
                      "Change opens a date-and-time picker")
        XCTAssertTrue(anyText(containing: "eight days away"), "with the bounds under it")
        app.buttons["Close"].tap()
        XCTAssertTrue(notice.waitForExistence(timeout: 10), "closing it leaves the resume alone")

        // Cancel removes it at once, with no confirmation.
        app.buttons["notice.secondaryAction"].tap()
        XCTAssertTrue(waitForAbsence(notice, timeout: 10), "Cancel takes the notice away with it")
        XCTAssertTrue(anyText(containing: "Resume cancelled"), "and the timeline records it")
    }

    /// `docs/DESIGN.md` § "The Settings screen": the screen opens on who is
    /// signed in and where, and closes on one line of versions — this build,
    /// the gateway's and the protocol — rather than an About group.
    func testSettingsHeaderAndVersionsLineNameTheAccountAndTheBuild() {
        app.launch()
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 20), "the Settings tab is there")
        settings.tap()

        let header = app.descendants(matching: .any)["settings.identity"]
        XCTAssertTrue(header.waitForExistence(timeout: 15), "the header is the first thing on it")
        for part in ["admin", "Admin", "Demo", "Connected"] {
            XCTAssertTrue(header.label.contains(part),
                          "the header reads \(part) to anyone who cannot see the dot")
        }
        attach(name: "ios-round43-settings")

        let versions = app.descendants(matching: .any)["settings.versions"]
        XCTAssertTrue(scrollDown(to: versions), "the versions line closes the screen")
        XCTAssertTrue(versions.label.contains(AppBuild.shipped), "and names this build")
        XCTAssertTrue(versions.label.contains("v\(RemoteProtocol.version)"),
                      "and the protocol both ends speak")
        XCTAssertTrue(app.buttons["settings.diagnostics"].exists, "with Diagnostics beside it")
        attach(name: "ios-round43-settings-2")
    }

    /// Settings, Voice group: turn dictation polish on and come back to the
    /// conversation. The demo gateway has a model, so the switch is live.
    private func turnPolishOn() {
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 20), "the Settings tab is there")
        settings.tap()
        let toggle = app.switches["settings.polish"]
        XCTAssertTrue(scrollDown(to: toggle), "the polish switch is in the Voice group")
        turnOn(toggle)
        XCTAssertTrue(app.descendants(matching: .any)["settings.polishModel"]
            .waitForExistence(timeout: 10), "a model is chosen for it")
        app.tabBars.buttons["Sessions"].tap()
        XCTAssertTrue(app.staticTexts["Sessions"].waitForExistence(timeout: 15),
                      "and the list is back")
    }

    /// Flip a settings switch on. A `Toggle` row in a `Form` is one element
    /// whose centre is the label, so a tap that lands there does nothing on
    /// some layouts; the control itself is against the trailing edge.
    private func turnOn(_ toggle: XCUIElement) {
        toggle.tap()
        if (toggle.value as? String) == "1" { return }
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
        XCTAssertTrue(waitFor { (toggle.value as? String) == "1" }, "the switch turns on")
    }

    /// The other direction, for a switch a gateway or an account starts on. The
    /// row is wider than the control, so the tap lands on the control itself.
    private func turnOff(_ toggle: XCUIElement) {
        toggle.tap()
        if (toggle.value as? String) == "0" { return }
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
        XCTAssertTrue(waitFor { (toggle.value as? String) == "0" }, "the switch turns off")
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


