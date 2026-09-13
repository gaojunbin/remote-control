import Foundation
import RCCore

/// Accounts (amendment A24), end to end through the real HTTP client.
///
/// Every body the app sends is compared with the fixture in `protocol/fixtures`
/// rather than with a copy written here, and every answer it reads is decoded
/// from one. What is left is the wording: which sentence each refusal produces,
/// which is the part a reader actually meets.
enum AccountChecks {
    static func run() async -> CheckResult {
        let checks = CheckRunner(group: "accounts")
        responses(checks)
        await requests(checks)
        await refusals(checks)
        rules(checks)
        return checks.result()
    }

    // MARK: - What the gateway answers

    private static func responses(_ checks: CheckRunner) {
        if let json = FixtureSource.json("http/health.response.json") {
            checks.noThrow("a health response decodes") {
                let health = try json.decode(HealthResponse.self)
                guard health.protocolVersion == RemoteProtocol.version,
                      health.registrationOpen == false else {
                    throw ProtocolFailure.malformed("health")
                }
            }
        } else {
            checks.expect(false, "http/health.response.json exists")
        }

        if let json = FixtureSource.json("http/login.response.json"),
           let login = try? json.decode(LoginResponse.self) {
            checks.equal(login.user.username, "admin", "a login response names the account")
            checks.expect(login.user.role.isAdmin, "and carries its role")
        } else {
            checks.expect(false, "http/login.response.json decodes")
        }

        if let json = FixtureSource.json("http/auth.session.response.json"),
           let info = try? json.decode(SessionInfoResponse.self) {
            checks.expect(info.user.role.isAdmin, "the stored account's role comes back from /api/session")
        } else {
            checks.expect(false, "http/auth.session.response.json decodes")
        }

        if let json = FixtureSource.json("app/hello.json"), let frame = try? AppFrame(json: json),
           case .hello(let hello) = frame {
            checks.equal(hello.user.username, "admin", "the app hello names the account the socket signed in as")
        } else {
            checks.expect(false, "app/hello.json decodes")
        }

        if let json = FixtureSource.json("http/users.list.response.json"),
           let list = try? json.decode(UserListResponse.self) {
            checks.equal(list.users.count, 2, "the user list decodes both accounts")
            checks.expect(!list.registrationOpen, "and says registration is closed")
            let admin = list.users.first
            checks.expect(admin?.isOperator == true, "the first row is the operator's")
            checks.equal(admin?.devices, 2, "with the devices it has enrolled")
            let alice = list.users.last
            checks.equal(alice?.state, .disabled, "the second account is disabled")
            checks.equal(alice?.lastLoginAt, nil, "and has never signed in")
            checks.equal(alice?.lastLoginSummary(), "never", "which the row says in words")
            checks.equal(alice?.deviceSummary, "", "an account with no devices says nothing about them")
            checks.expect(alice?.isOperator == false, "and it is not the operator, so it has actions")
        } else {
            checks.expect(false, "http/users.list.response.json decodes")
        }

        if let json = FixtureSource.json("http/users.response.json") {
            checks.noThrow("a single user response decodes") {
                let record = try json.decode(UserResponse.self).user
                guard record.role == .member, record.state == .active else {
                    throw ProtocolFailure.malformed("users.response")
                }
            }
        } else {
            checks.expect(false, "http/users.response.json exists")
        }

        if let json = FixtureSource.json("http/registration.response.json") {
            checks.noThrow("a registration response decodes") {
                guard try json.decode(RegistrationResponse.self).open else {
                    throw ProtocolFailure.malformed("registration.response")
                }
            }
        } else {
            checks.expect(false, "http/registration.response.json exists")
        }

        // A role this build has never heard of decodes rather than failing, and
        // is not the admin: an older app must never open 3.9 by accident.
        let unknown = FixtureSource.invalid.appending(path: "objects.user__role_unknown.json")
        if let data = try? Data(contentsOf: unknown),
           let json = try? JSONDecoder().decode(JSONValue.self, from: data),
           let user = try? json.decode(UserIdentity.self) {
            checks.equal(user.role.rawValue, "owner", "an unknown role decodes as itself")
            checks.expect(!user.role.isAdmin, "and is not treated as the admin")
        } else {
            checks.expect(false, "the unknown-role fixture decodes")
        }
    }

    // MARK: - What the app sends

    private static func requests(_ checks: CheckRunner) async {
        let transport = RecordingTransport()
        await transport.answer("POST /api/login", body: FixtureSource.json("http/login.response.json") ?? .object([:]))
        await transport.answer("POST /api/register",
                               body: FixtureSource.json("http/login.response.json") ?? .object([:]))
        await transport.answer("GET /api/users",
                               body: FixtureSource.json("http/users.list.response.json") ?? .object([:]))
        await transport.answer("POST /api/users",
                               body: FixtureSource.json("http/users.response.json") ?? .object([:]))
        await transport.answer("PATCH /api/users/alice",
                               body: FixtureSource.json("http/users.response.json") ?? .object([:]))
        await transport.answer("PATCH /api/registration",
                               body: FixtureSource.json("http/registration.response.json") ?? .object([:]))
        await transport.answer("GET /api/health",
                               body: FixtureSource.json("http/health.response.json") ?? .object([:]))
        let client = GatewayHTTPClient(endpoint: GatewayEndpoint.placeholder, transport: transport,
                                       secrets: MemorySecretStore())

        _ = try? await client.login(username: "admin", password: "correct horse battery staple")
        await compare(transport, "POST /api/login", with: "http/login.request.json", checks: checks)

        _ = try? await client.register(username: "alice", password: "correct horse battery staple")
        await compare(transport, "POST /api/register", with: "http/register.request.json", checks: checks)

        try? await client.changePassword(current: "correct horse battery staple",
                                         new: "staple battery horse correct")
        await compare(transport, "POST /api/password", with: "http/password.request.json", checks: checks)

        let listed = try? await client.users()
        checks.equal(listed?.users.count, 2, "the admin's list comes back as records")

        _ = try? await client.createUser(username: "alice", password: "correct horse battery staple",
                                         role: .member)
        await compare(transport, "POST /api/users", with: "http/users.create.request.json", checks: checks)

        _ = try? await client.patchUser("alice", state: .disabled)
        await compare(transport, "PATCH /api/users/alice", with: "http/users.patch.request.json", checks: checks)

        _ = try? await client.setRegistration(open: true)
        await compare(transport, "PATCH /api/registration", with: "http/registration.patch.request.json",
                      checks: checks)

        // The username is required: the negative fixture is the body 3.1
        // refuses, and nothing the app builds may look like it.
        let login = await transport.body(of: "POST /api/login")
        checks.expect(login?["username"]?.stringValue == "admin",
                      "every sign-in the app sends names an account")
        let refused = FixtureSource.invalid.appending(path: "http.login__no_username.json")
        if let data = try? Data(contentsOf: refused),
           let json = try? JSONDecoder().decode(JSONValue.self, from: data) {
            checks.expect(json["username"] == nil && login != json,
                          "the body 3.1 refuses is not the one the app builds")
        } else {
            checks.expect(false, "the no-username fixture is readable")
        }

        // The account routes are the signed-in caller's, so they carry the
        // bearer the login stored and never go out unauthenticated.
        let authorized = await transport.authorization(of: "GET /api/users")
        checks.expect(authorized?.hasPrefix("Bearer ") == true, "the account routes carry the bearer token")
        checks.equal(await transport.authorization(of: "GET /api/health"), nil,
                     "and the health probe carries nothing, because nobody has signed in yet")

        _ = try? await client.deleteUser("alice")
        checks.equal(await transport.method(of: "DELETE /api/users/alice"), "DELETE",
                     "deleting an account is a DELETE on its own path")

        let health = try? await client.health()
        checks.equal(health?.registrationOpen, false, "the health probe reads the registration switch")
    }

    private static func compare(_ transport: RecordingTransport, _ call: String,
                                with fixture: String, checks: CheckRunner) async {
        guard let expected = FixtureSource.json(fixture) else {
            checks.expect(false, "\(fixture) exists")
            return
        }
        guard let sent = await transport.body(of: call) else {
            checks.expect(false, "\(call) was sent")
            return
        }
        checks.equal(sent, expected, "\(call) sends exactly \(fixture)")
    }

    // MARK: - What each refusal says

    private static func refusals(_ checks: CheckRunner) async {
        checks.equal(AccountError.signIn(TransportError.unauthorized),
                     "Wrong username or password.",
                     "a wrong password is not told which half was wrong")
        checks.equal(AccountError.signIn(TransportError.http(status: 403, code: "forbidden")),
                     "This account is disabled.",
                     "a disabled account is told so")
        checks.equal(AccountError.register(TransportError.http(status: 409, code: "conflict")),
                     "That username is taken.",
                     "a taken username says so")
        checks.equal(AccountError.create(TransportError.http(status: 409, code: "conflict")),
                     "That username is taken.",
                     "and says the same thing when an admin is the one adding it")
        checks.equal(AccountError.manage(TransportError.http(status: 409, code: "conflict")),
                     "This account cannot be changed.",
                     "while a 409 on an account that exists is only ever the operator's row")
        checks.equal(AccountError.register(TransportError.http(status: 403, code: "forbidden")),
                     "Registration is closed.",
                     "registering into a closed gateway says so")
        checks.equal(AccountError.register(TransportError.http(status: 400, code: "bad_request")),
                     AccountError.rules,
                     "and a refused name is the only time the rule is stated")
        checks.equal(AccountError.passwordChange(TransportError.unauthorized),
                     "That is not your current password.",
                     "a wrong current password says which field was wrong")
        checks.equal(AccountError.manage(TransportError.http(status: 404, code: "not_found")),
                     "That account no longer exists.",
                     "an account that went away while the screen was open says so")
        checks.equal(AccountError.manage(TransportError.http(status: 403, code: "forbidden")),
                     "This account cannot be changed.",
                     "and so is a caller 3.9 will not take at all")
    }

    // MARK: - The rules, written once

    private static func rules(_ checks: CheckRunner) {
        checks.expect(!AccountRules.isPasswordLongEnough("1234567"), "seven characters is not a password")
        checks.expect(AccountRules.isPasswordLongEnough("12345678"), "eight is")
        checks.equal(AccountRules.operatorUsername, "admin", "the operator is the account named admin")
        checks.expect(UserRole.admin.isAdmin && !UserRole.member.isAdmin,
                      "only the admin role opens the accounts screen")
    }
}

/// An `HTTPTransport` that answers from a table and keeps what it was asked, so
/// the bodies the client builds can be compared with the frozen fixtures.
actor RecordingTransport: HTTPTransport {
    private struct Call {
        let method: String
        let body: JSONValue?
        let authorization: String?
    }

    private var calls: [String: Call] = [:]
    private var answers: [String: (status: Int, body: JSONValue)] = [:]

    func answer(_ call: String, status: Int = 200, body: JSONValue) {
        answers[call] = (status, body)
    }

    func body(of call: String) -> JSONValue? { calls[call]?.body }
    func authorization(of call: String) -> String? { calls[call]?.authorization }
    func method(of call: String) -> String? { calls[call]?.method }

    func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        guard let url = request.url else { throw TransportError.invalidEndpoint }
        let method = request.httpMethod ?? "GET"
        let call = "\(method) \(url.path)"
        calls[call] = Call(method: method,
                           body: request.httpBody.flatMap { try? JSONDecoder().decode(JSONValue.self, from: $0) },
                           authorization: request.value(forHTTPHeaderField: "Authorization"))
        let answer = answers[call] ?? (200, JSONValue.object([:]))
        guard let response = HTTPURLResponse(url: url, statusCode: answer.status,
                                             httpVersion: nil, headerFields: nil) else {
            throw TransportError.invalidResponse
        }
        return (try JSONEncoder().encode(answer.body), response)
    }
}
