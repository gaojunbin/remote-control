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

    func testNewSessionSheetOffersDeviceAndAgent() {
        app.launch()
        let newSession = app.buttons["sessions.new"]
        XCTAssertTrue(newSession.waitForExistence(timeout: 20))
        newSession.tap()

        XCTAssertTrue(app.buttons["newsession.start"].waitForExistence(timeout: 10),
                      "the sheet offers an explicit start action")
        XCTAssertTrue(app.otherElements["newsession.agent"].exists || app.segmentedControls.firstMatch.exists,
                      "an agent picker is present")
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
