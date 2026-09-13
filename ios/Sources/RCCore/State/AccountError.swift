import Foundation

/// One error, one sentence, whatever kind of error it is.
///
/// Every store that shows a failure to the reader comes through here, so a
/// transport failure, a gateway `reply` error and a decoding failure read alike.
public enum GatewayMessage {
    public static func text(for error: any Error) -> String {
        if let transport = error as? TransportError { return transport.errorDescription ?? "\(transport)" }
        if let gateway = error as? GatewayErrorBody { return gateway.message }
        if let failure = error as? ProtocolFailure { return failure.errorDescription ?? "\(failure)" }
        return error.localizedDescription
    }

    /// The HTTP status behind an error, where it had one. A 401 arrives as
    /// `TransportError.unauthorized` because every authenticated call treats it
    /// as the end of a session; the account forms read it for their own reason.
    static func status(of error: any Error) -> Int? {
        switch error as? TransportError {
        case .unauthorized: 401
        case .http(let status, _): status
        default: nil
        }
    }
}

/// What the account forms say when the gateway refuses them (A24).
///
/// The words are the web's words: one product, two apps, and a person who is
/// told "That username is taken" in a browser reads the same sentence on the
/// phone. What the gateway answered decides which sentence, never the app's
/// guess at what the reader did wrong.
///
/// One status means two things across the routes — a `409` is a taken username
/// on `POST /api/users` and an account refusing to be touched on `PATCH` — so
/// each route has its own function here rather than one mapper guessing from
/// the code. The web splits them the same way for the same reason; it keys off
/// `error.code` where a browser can read one, while a `401` reaches this side
/// as `TransportError.unauthorized` with the code already dropped.
public enum AccountError {
    /// `POST /api/login`. A wrong password is never told which half was wrong.
    public static func signIn(_ error: any Error) -> String {
        switch GatewayMessage.status(of: error) {
        case 400: L10n.string("Enter a username and a password.")
        case 401: L10n.string("Wrong username or password.")
        case 403: L10n.string("This account is disabled.")
        default: GatewayMessage.text(for: error)
        }
    }

    /// `POST /api/register`.
    public static func register(_ error: any Error) -> String {
        switch GatewayMessage.status(of: error) {
        case 400: rules
        case 403: L10n.string("Registration is closed.")
        case 409: L10n.string("That username is taken.")
        default: GatewayMessage.text(for: error)
        }
    }

    /// `POST /api/password`, the caller changing their own.
    public static func passwordChange(_ error: any Error) -> String {
        switch GatewayMessage.status(of: error) {
        case 400: rules
        case 401: L10n.string("That is not your current password.")
        default: manage(error)
        }
    }

    /// Changing an account that already exists, 3.9. A refusal here is the
    /// `admin` row — the one account no state, role or password may touch —
    /// or a caller who is not an admin at all. The screen offers neither, so
    /// this is what a race says.
    public static func manage(_ error: any Error) -> String {
        switch GatewayMessage.status(of: error) {
        case 400: rules
        case 403, 409: L10n.string("This account cannot be changed.")
        case 404: L10n.string("That account no longer exists.")
        default: GatewayMessage.text(for: error)
        }
    }

    /// Creating an account, where a 409 is a name somebody already has rather
    /// than an account refusing to be touched.
    public static func create(_ error: any Error) -> String {
        GatewayMessage.status(of: error) == 409
            ? L10n.string("That username is taken.")
            : manage(error)
    }

    /// Both rules in one sentence each, because one route answers `400` for a
    /// username outside the rules and for a password outside them alike, and
    /// the form cannot tell which it was.
    public static var rules: String {
        L10n.string(
            "Usernames are 3 to 32 characters: lower-case letters, digits, dots, underscores and hyphens. Passwords are 8 characters or more.")
    }
}
