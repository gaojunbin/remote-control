import XCTest

/// A smoke test against a **real** gateway, a real device daemon and a real
/// agent. It is skipped unless the runner is given a gateway to talk to, so the
/// default `RemoteControlUITests` run stays offline and hermetic.
///
/// Run it with the gateway origin and password in the runner's environment:
///
/// ```
/// TEST_RUNNER_RC_E2E_GATEWAY=http://127.0.0.1:8793 \
/// TEST_RUNNER_RC_E2E_PASSWORD=… \
/// TEST_RUNNER_RC_E2E_SESSION=<claude session id> \
/// xcodebuild test -project RemoteControl.xcodeproj -scheme RemoteControl \
///   -destination "platform=iOS Simulator,id=<udid>" \
///   -only-testing:RemoteControlUITests/RealGatewaySmokeTests CODE_SIGNING_ALLOWED=NO
/// ```
///
/// `xcodebuild` strips the `TEST_RUNNER_` prefix before handing the variables to
/// the runner, and the values are forwarded to the app under test through
/// `launchEnvironment` so a diagnostic run can see what the test was aimed at.
/// `RC_E2E_SESSION` is optional: without it the test opens the first session
/// whose title matches `RC_E2E_TITLE` (default "Reply with exactly OK").
final class RealGatewaySmokeTests: XCTestCase {
    private var app: XCUIApplication!
    private var gateway = ""
    private var password = ""

    override func setUpWithError() throws {
        continueAfterFailure = false
        let environment = ProcessInfo.processInfo.environment
        gateway = environment["RC_E2E_GATEWAY"] ?? ""
        password = environment["RC_E2E_PASSWORD"] ?? ""
        try XCTSkipIf(gateway.isEmpty || password.isEmpty,
                      "set RC_E2E_GATEWAY and RC_E2E_PASSWORD to run the real-gateway smoke")

        app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-state"]
        app.launchEnvironment["RC_E2E_GATEWAY"] = gateway
        app.launchEnvironment["RC_E2E_PASSWORD"] = password
    }

    /// Sign in, read a real transcript, send a real prompt, and check the
    /// device the daemon enrolled is listed as online.
    func testSignsInAndDrivesARealSession() throws {
        app.launch()
        try signIn()
        attach(name: "01-sessions")

        let row = try sessionRow()
        row.tap()

        let composer = try composerField()
        XCTAssertTrue(app.staticTexts["OK"].waitForExistence(timeout: 30),
                      "the transcript from the real device shows the agent's answer")
        attach(name: "02-chat")

        composer.tap()
        composer.typeText("Reply with exactly DONE")
        let send = app.buttons["composer.send"]
        XCTAssertTrue(send.waitForExistence(timeout: 10), "the send button is a separate control")
        XCTAssertTrue(send.isEnabled, "send is enabled once there is a draft")
        send.tap()

        XCTAssertTrue(app.staticTexts["DONE"].waitForExistence(timeout: 180),
                      "the agent's reply to the message this test sent arrives in the transcript")
        attach(name: "03-answered")
    }

    /// The device the daemon enrolled is listed, online, with both agents.
    func testDevicesTabListsTheEnrolledMachine() throws {
        app.launch()
        try signIn()

        app.tabBars.buttons["Devices"].tap()
        XCTAssertTrue(app.navigationBars["Devices"].waitForExistence(timeout: 20),
                      "the devices screen appears")
        XCTAssertTrue(app.buttons["devices.add"].waitForExistence(timeout: 10),
                      "adding a device is offered")

        let deviceName = ProcessInfo.processInfo.environment["RC_E2E_DEVICE_NAME"] ?? "integrate-apps-mac"
        let device = app.staticTexts[deviceName]
        XCTAssertTrue(device.waitForExistence(timeout: 20), "the enrolled machine is listed")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS[c] 'Claude Code'")).firstMatch
                        .waitForExistence(timeout: 10),
                      "the device row names the agents it found")
        attach(name: "04-devices")
    }

    /// Settings renders against a live gateway, including the origin it is on.
    func testSettingsTabRendersAgainstTheLiveGateway() throws {
        app.launch()
        try signIn()

        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 20),
                      "the settings screen appears")
        XCTAssertTrue(app.buttons["settings.signOut"].waitForExistence(timeout: 10),
                      "signing out is offered")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", gateway)).firstMatch
                        .waitForExistence(timeout: 10),
                      "settings shows the gateway this app is signed in to")
        attach(name: "05-settings")
    }

    /// A gateway that worked once is remembered, so the next launch does not ask
    /// for the address again. Backgrounding the app is part of the test: the
    /// scene body runs again there, and it used to rebuild the model and re-apply
    /// `--reset-state` over the address the sign-in had just stored.
    ///
    /// The stronger promise — no password either, because the keychain token is
    /// restored — cannot be asserted from an unsigned simulator build: without an
    /// `application-identifier` entitlement the keychain refuses to store the
    /// token. So this accepts either outcome and requires the address.
    func testRemembersTheGatewayAcrossALaunch() throws {
        app.launch()
        try signIn()

        XCUIDevice.shared.press(.home)
        Thread.sleep(forTimeInterval: 3)
        app.terminate()

        let relaunched = XCUIApplication()
        relaunched.launchArguments = ["--ui-testing"]
        relaunched.launch()

        let restored = relaunched.staticTexts["sessions.summary"]
        let origin = relaunched.textFields["login.gateway"]
        XCTAssertTrue(restored.waitForExistence(timeout: 20) || origin.waitForExistence(timeout: 20),
                      "the relaunched app shows either the session list or the login screen")
        if !restored.exists {
            XCTAssertEqual(origin.value as? String, gateway,
                           "the login screen prefills the gateway that worked last time")
        }
        attach(name: "06-relaunch")
    }

    // MARK: - helpers

    private func signIn() throws {
        let origin = app.textFields["login.gateway"]
        XCTAssertTrue(origin.waitForExistence(timeout: 30), "the login screen appears")
        origin.tap()
        origin.typeText(gateway)

        let secret = app.secureTextFields["login.password"]
        XCTAssertTrue(secret.waitForExistence(timeout: 10), "the password field is present")
        secret.tap()
        secret.typeText(password)

        let connect = app.buttons["login.connect"]
        XCTAssertTrue(connect.isEnabled, "connect is enabled once both fields are filled")
        connect.tap()

        XCTAssertTrue(app.staticTexts["sessions.summary"].waitForExistence(timeout: 40),
                      "signing in lands on the sessions list: \(app.staticTexts["login.error"].label)")
    }

    /// The session row to open: by id when the runner names one, otherwise the
    /// first row whose title matches.
    private func sessionRow() throws -> XCUIElement {
        let environment = ProcessInfo.processInfo.environment
        if let sessionID = environment["RC_E2E_SESSION"], !sessionID.isEmpty {
            let row = app.buttons["session.\(sessionID)"]
            if !row.waitForExistence(timeout: 10) {
                search(for: environment["RC_E2E_TITLE"] ?? "Reply with exactly OK")
            }
            XCTAssertTrue(row.waitForExistence(timeout: 20), "the session the test was aimed at is listed")
            return row
        }
        let title = environment["RC_E2E_TITLE"] ?? "Reply with exactly OK"
        search(for: title)
        let row = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 20), "a session titled \(title) is listed")
        return row
    }

    private func search(for text: String) {
        let field = app.searchFields.firstMatch
        guard field.waitForExistence(timeout: 10) else { return }
        field.tap()
        field.typeText(text)
    }

    private func composerField() throws -> XCUIElement {
        let textView = app.textViews["composer.prompt"].firstMatch
        if textView.waitForExistence(timeout: 20) { return textView }
        let field = app.textFields["composer.prompt"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the composer is on screen")
        return field
    }

    private func attach(name: String) {
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
