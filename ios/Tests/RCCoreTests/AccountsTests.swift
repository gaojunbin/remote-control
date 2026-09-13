import Testing
import Foundation
@testable import RCCore

/// Amendment A24: every person on a gateway has their own devices, sessions and
/// settings. These are the parts of that the app owns — what it decodes, what
/// it says when it is refused, and whose preferences it reads.
@Suite("Accounts (A24)")
struct AccountsTests {
    // MARK: - What the app decodes

    @Test("A user is a username and a role")
    func userIdentity() throws {
        let json: JSONValue = ["username": "alice", "role": "member"]
        let user = try json.decode(UserIdentity.self)
        #expect(user.username == "alice")
        #expect(user.role == .member)
        #expect(!user.role.isAdmin)
    }

    @Test("A role this build has never heard of is decoded, and is not the admin")
    func unknownRole() throws {
        let json: JSONValue = ["username": "alice", "role": "owner"]
        let user = try json.decode(UserIdentity.self)
        #expect(user.role.rawValue == "owner")
        #expect(!user.role.isAdmin)
        #expect(user.role.title == "owner")
    }

    @Test("A hello with no user at all still decodes")
    func helloWithoutUser() throws {
        let frame = try AppFrame(json: ["type": "hello", "protocol": 1, "gateway_version": "0.1.0",
                                        "server_time": 0])
        guard case .hello(let hello) = frame else {
            Issue.record("expected a hello")
            return
        }
        #expect(hello.user.username.isEmpty)
        #expect(!hello.user.role.isAdmin)
    }

    @Test("A user record carries what the row draws")
    func userRecord() throws {
        let json: JSONValue = ["username": "alice", "role": "member", "state": "disabled",
                               "created_at": 1_788_512_400_000, "last_login_at": .null, "devices": 0]
        let record = try json.decode(UserRecord.self)
        #expect(record.roleAndState == "Member · Disabled")
        #expect(record.deviceSummary.isEmpty)
        #expect(record.lastLoginSummary() == "never")
        #expect(!record.isOperator)
        #expect(!record.isActive)
    }

    @Test("The operator's row is the one with no actions")
    func operatorRecord() throws {
        let json: JSONValue = ["username": "admin", "role": "admin", "state": "active",
                               "created_at": 1, "last_login_at": 2, "devices": 2]
        let record = try json.decode(UserRecord.self)
        #expect(record.isOperator)
        #expect(record.deviceSummary == "2 devices")
        #expect(record.identity.role.isAdmin)
    }

    @Test("The health response says whether registration is open")
    func health() throws {
        let json: JSONValue = ["ok": true, "version": "0.1.0", "protocol": 1,
                               "auth": ["mode": "password", "registration_open": true]]
        #expect(try json.decode(HealthResponse.self).registrationOpen)
        // A gateway that says nothing about it is not taking accounts.
        let quiet: JSONValue = ["ok": true, "version": "0.1.0", "protocol": 1]
        #expect(try quiet.decode(HealthResponse.self).registrationOpen == false)
    }

    // MARK: - What each refusal says

    @Test("Signing in never says which half was wrong", arguments: [
        (401, "Wrong username or password."),
        (403, "This account is disabled."),
        (400, "Enter a username and a password.")
    ])
    func signInWording(status: Int, sentence: String) {
        #expect(AccountError.signIn(error(status)) == sentence)
    }

    @Test("Registering is refused by name, by rule or by the switch", arguments: [
        (409, "That username is taken."),
        (403, "Registration is closed.")
    ])
    func registerWording(status: Int, sentence: String) {
        #expect(AccountError.register(error(status)) == sentence)
    }

    @Test("A refused name is the one time the rule is stated")
    func accountRules() {
        #expect(AccountError.register(error(400)) == AccountError.rules)
        #expect(AccountError.create(error(400)) == AccountError.rules)
        #expect(AccountError.manage(error(400)) == AccountError.rules)
        #expect(AccountError.rules.contains("hyphens"))
        #expect(AccountError.rules.contains("8 characters or more"))
    }

    @Test("Only the caller's own password can be wrong")
    func passwordWording() {
        #expect(AccountError.passwordChange(TransportError.unauthorized)
                == "That is not your current password.")
        // The operator's password is the gateway's own, so the route refuses
        // it. The row is never offered to an admin; this is what a race says.
        #expect(AccountError.passwordChange(error(403)) == "This account cannot be changed.")
    }

    @Test("A conflict on an account that exists is only ever the operator's row")
    func manageConflict() {
        #expect(AccountError.manage(error(409)) == "This account cannot be changed.")
        #expect(AccountError.create(error(409)) == "That username is taken.")
    }

    // MARK: - The rules, written once

    @Test("A password is eight characters or more")
    func passwordLength() {
        #expect(!AccountRules.isPasswordLongEnough(""))
        #expect(!AccountRules.isPasswordLongEnough("1234567"))
        #expect(AccountRules.isPasswordLongEnough("12345678"))
        #expect(AccountRules.passwordLength == 8...128)
    }

    // MARK: - Whose settings the app is reading

    @Test("Two people on one phone do not share the app's settings")
    @MainActor
    func settingsArePerAccount() {
        let defaults = suite()
        let first = SettingsStore(defaults: defaults)
        first.remember(origin: "https://rc.example.com", username: "alice")
        first.language = .zhHans
        first.timelineDetail = .detailed
        first.voiceLanguage = "zh"
        first.notificationsEnabled = true

        let second = SettingsStore(defaults: defaults)
        second.remember(origin: "https://rc.example.com", username: "bob")
        #expect(second.language == .en)
        #expect(second.timelineDetail == .simple)
        #expect(second.voiceLanguage == "auto")
        #expect(!second.notificationsEnabled)

        second.remember(origin: "https://rc.example.com", username: "alice")
        #expect(second.language == .zhHans)
        #expect(second.timelineDetail == .detailed)
    }

    @Test("The same username on another gateway is another person")
    @MainActor
    func settingsArePerGateway() {
        let defaults = suite()
        let store = SettingsStore(defaults: defaults)
        store.remember(origin: "https://one.example.com", username: "alice")
        store.timelineDetail = .detailed
        store.remember(origin: "https://two.example.com", username: "alice")
        #expect(store.timelineDetail == .simple)
    }

    @Test("The form is prefilled with the gateway, and with the account used there")
    @MainActor
    func prefill() {
        let defaults = suite()
        let store = SettingsStore(defaults: defaults)
        store.remember(origin: "https://one.example.com", username: "alice")
        store.remember(origin: "https://two.example.com", username: "bob")
        #expect(store.username(for: "https://one.example.com") == "alice")
        #expect(store.username(for: "https://two.example.com") == "bob")
        #expect(store.username(for: "https://three.example.com").isEmpty)

        // The launch comes back to the gateway it left, with the account that
        // signed in there — which is the one the keychain token is filed under.
        let relaunched = SettingsStore(defaults: defaults)
        #expect(relaunched.lastOrigin == "https://two.example.com")
        #expect(relaunched.lastUsername == "bob")
        // And it reads that account's preferences, so the first screen is
        // already in their language.
        store.language = .zhHans
        #expect(SettingsStore(defaults: defaults).language == .zhHans)
    }

    @Test("A reset forgets every account, not only the last one")
    @MainActor
    func resetClearsEveryScope() {
        let defaults = suite()
        let store = SettingsStore(defaults: defaults)
        store.remember(origin: "https://rc.example.com", username: "alice")
        store.language = .zhHans
        store.remember(origin: "https://rc.example.com", username: "bob")
        store.timelineDetail = .detailed
        store.reset()
        #expect(store.lastOrigin.isEmpty)
        store.remember(origin: "https://rc.example.com", username: "alice")
        #expect(store.language == .en)
        store.remember(origin: "https://rc.example.com", username: "bob")
        #expect(store.timelineDetail == .simple)
    }

    @Test("A pinned language outlives whichever account signs in")
    @MainActor
    func pinnedLanguage() {
        let defaults = suite()
        let stored = SettingsStore(defaults: defaults)
        stored.remember(origin: "https://rc.example.com", username: "alice")
        stored.language = .en

        let pinned = SettingsStore(defaults: defaults)
        pinned.pinLanguage(.zhHans)
        pinned.remember(origin: "https://rc.example.com", username: "alice")
        #expect(pinned.language == .zhHans)
    }

    // MARK: - The demo gateway models the three answers

    @Test("A disabled account is refused with 403, an unknown one with 401")
    func demoSignIn() async throws {
        let gateway = DemoGateway(resumeDelay: nil)
        await #expect(throws: TransportError.http(status: 403, code: "forbidden")) {
            _ = try await gateway.login(username: DemoFixtures.disabledUsername, password: "correct horse")
        }
        await #expect(throws: TransportError.unauthorized) {
            _ = try await gateway.login(username: "nobody", password: "correct horse")
        }
        let member = try await gateway.login(username: DemoFixtures.memberUsername, password: "correct horse")
        #expect(member.user.role == .member)
    }

    @Test("A member is refused the account routes")
    func demoMemberIsRefused() async throws {
        let gateway = DemoGateway(resumeDelay: nil)
        _ = try await gateway.login(username: DemoFixtures.memberUsername, password: "correct horse")
        await #expect(throws: TransportError.http(status: 403, code: "forbidden")) {
            _ = try await gateway.users()
        }
    }

    @Test("Registration is closed on a fresh gateway and opens from the admin's screen")
    func demoRegistration() async throws {
        let gateway = DemoGateway(resumeDelay: nil)
        #expect(try await gateway.health().registrationOpen == false)
        await #expect(throws: TransportError.http(status: 403, code: "forbidden")) {
            _ = try await gateway.register(username: "carol", password: "correct horse battery staple")
        }
        #expect(try await gateway.setRegistration(open: true))
        let created = try await gateway.register(username: "Carol",
                                                 password: "correct horse battery staple")
        #expect(created.user.username == "carol")
        #expect(created.user.role == .member)
        await #expect(throws: TransportError.http(status: 409, code: "conflict")) {
            _ = try await gateway.register(username: "carol", password: "correct horse battery staple")
        }
        await #expect(throws: TransportError.http(status: 400, code: "bad_request")) {
            _ = try await gateway.register(username: "no", password: "correct horse battery staple")
        }
    }

    private func error(_ status: Int) -> TransportError {
        status == 401 ? .unauthorized : .http(status: status, code: nil)
    }

    @MainActor
    private func suite() -> UserDefaults {
        UserDefaults(suiteName: "rc-tests-\(UUID().uuidString)")!
    }
}
