import XCTest

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

    /// The field owns a row of its own and grows with the draft; the controls
    /// sit on a second row underneath it.
    func testComposerFieldOwnsItsRowAndGrows() {
        app.launch()
        openLiveSession()

        let field = promptField()
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the message field is on screen")
        let attachments = app.buttons["composer.attach"]
        let voice = app.buttons["composer.voice"]
        let send = app.buttons["composer.send"]
        XCTAssertTrue(attachments.exists, "attachments are on the row below the field")
        XCTAssertTrue(voice.exists, "and so is dictation")
        XCTAssertTrue(send.exists, "with Send at the other end of that row")
        XCTAssertGreaterThan(field.frame.width, attachments.frame.width * 4,
                             "the field takes the whole width rather than sharing it")
        XCTAssertGreaterThan(attachments.frame.minY, field.frame.maxY - 1,
                             "the controls sit under the field, not beside it")
        XCTAssertLessThan(attachments.frame.minX, send.frame.minX,
                          "attachments and dictation are left, Send is right")

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

    /// Dictation offers exactly Cancel and Done, fills the message field, and
    /// leaves sending to the ordinary Send button.
    func testDictationOffersCancelAndDoneAndFillsTheField() {
        app.launch()
        openLiveSession()
        XCTAssertTrue(promptField().waitForExistence(timeout: 15))

        app.buttons["composer.voice"].tap()

        let done = app.buttons["voice.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 15), "Done stops listening")
        XCTAssertTrue(app.buttons["voice.cancel"].exists, "Cancel is the other way out")
        XCTAssertFalse(app.buttons["voice.stop"].exists, "nothing stops and sends in one tap")
        XCTAssertFalse(app.buttons["composer.send"].exists, "Send is not offered while listening")
        XCTAssertFalse(app.buttons["composer.attach"].exists, "and neither is anything else")
        XCTAssertFalse(app.buttons["composer.voice"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["voice.status"].exists,
                      "one quiet line says what dictation is doing")
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

        // A second dictation, cancelled, leaves the first one exactly as it was.
        app.buttons["composer.voice"].tap()
        let cancel = app.buttons["voice.cancel"]
        XCTAssertTrue(cancel.waitForExistence(timeout: 15))
        cancel.tap()
        let restored = promptField()
        XCTAssertTrue(restored.waitForExistence(timeout: 15))
        XCTAssertEqual(restored.value as? String, dictated,
                       "Cancel discards what that dictation added and restores the draft")
    }

    private func openLiveSession() {
        let row = app.buttons["session.demo-session-auth"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the live demo session is listed")
        row.tap()
    }

    /// SwiftUI renders a growing field as a text view once it wraps, so the
    /// identifier is looked up in both collections.
    private func promptField() -> XCUIElement {
        let view = app.textViews["composer.prompt"].firstMatch
        return view.exists ? view : app.textFields["composer.prompt"].firstMatch
    }

    /// Amendment A10: an attached terminal session takes a message from here,
    /// says it is waiting for the terminal, then says it went in, and its
    /// relayed permission request is answered from the app.
    func testSharedSessionDeliversAndApproves() {
        app.launch()

        let row = app.buttons["session.demo-session-shared"]
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the attached demo session is listed")
        row.tap()

        let composer = app.textViews["composer.prompt"].firstMatch
        let composerField = composer.exists ? composer : app.textFields["composer.prompt"].firstMatch
        XCTAssertTrue(composerField.waitForExistence(timeout: 15),
                      "the composer is enabled on an attached session")
        XCTAssertFalse(app.buttons["chat.takeover"].exists,
                       "an attached session never offers a takeover")

        let note = app.descendants(matching: .any)["composer.terminalNote"]
        XCTAssertTrue(note.waitForExistence(timeout: 10),
                      "one line says what the terminal owns")
        XCTAssertTrue(note.label.contains("Attached to the terminal"),
                      "and it is the consolidated line, not one sentence per control")

        attach(name: "06-shared-idle")

        // Reaching for a control the terminal owns explains that control.
        app.buttons["composer.model"].tap()
        XCTAssertTrue(note.label.contains("Change it in the terminal"),
                      "tapping the model chip says where the model is changed")
        XCTAssertFalse(app.buttons["session.model"].exists, "and never opens the settings sheet")
        attach(name: "06b-shared-blocked")

        composerField.tap()
        composerField.typeText("mention the iOS app too")
        app.buttons["composer.send"].tap()

        let pending = app.descendants(matching: .any)["chat.message.pending"]
        XCTAssertTrue(pending.waitForExistence(timeout: 10), "the held message is in the transcript")
        XCTAssertTrue(pending.label.contains("waiting for the terminal"),
                      "and its chip says it is waiting for the terminal")
        attach(name: "07-shared-pending")

        let delivered = app.descendants(matching: .any)["chat.message.delivered"]
        XCTAssertTrue(delivered.waitForExistence(timeout: 15),
                      "the replacement event marks the same message delivered")
        attach(name: "08-shared-delivered")

        let approval = app.buttons["approval.primary"]
        XCTAssertTrue(approval.waitForExistence(timeout: 15), "the relayed request is answerable here")
        attach(name: "09-shared-approval")
        approval.tap()
        XCTAssertTrue(approval.waitForNonExistence(timeout: 15), "answering resolves the request")
        attach(name: "10-shared-answered")
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

        let note = app.descendants(matching: .any)["composer.terminalNote"]
        XCTAssertTrue(note.waitForExistence(timeout: 10), "the attachment is still named")
        XCTAssertEqual(note.label, "Attached to the terminal",
                       "nothing is handed back to the terminal, so nothing else is said")
        XCTAssertTrue(app.buttons["chat.stop"].exists, "shared_interrupt offers Stop while it runs")
        XCTAssertEqual(composerField.placeholderValue, "Message · will steer the turn",
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
        XCTAssertTrue(app.descendants(matching: .any)["session.terminalNote"].exists == false,
                      "and nothing tells the user to change it in the terminal")
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

    private func attach(name: String) {
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
