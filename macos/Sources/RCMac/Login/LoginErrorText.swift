import Foundation
import RCCore

/// What a refused sign-in or registration says: the web's sentences
/// (`signInErrorText` and `registerErrorText` in `LoginPage.tsx`), chosen by
/// the status the gateway answered with. A failure that never reached the
/// gateway is "Cannot reach the gateway."
enum LoginErrorText {
    static func signIn(_ error: (any Error)?) -> String {
        switch status(of: error) {
        case .some(429): S.login.rateLimited
        case .some(403): S.account.disabled
        case .some(401): S.login.failed
        case .some: S.errors.generic
        case .none: error == nil ? S.errors.generic : S.login.unreachable
        }
    }

    static func register(_ error: (any Error)?) -> String {
        switch status(of: error) {
        case .some(429): S.login.rateLimited
        case .some(409): S.account.taken
        case .some(403): S.login.registrationClosed
        case .some(400): S.account.rules
        case .some: S.errors.generic
        case .none: error == nil ? S.errors.generic : S.login.unreachable
        }
    }

    /// A `403` on registering also means the gateway takes no registrations
    /// now, so the link to the form goes.
    static func closesRegistration(_ error: (any Error)?) -> Bool { status(of: error) == 403 }

    /// The HTTP status of a refusal, where the gateway answered at all. RCCore
    /// reports a `401` as `unauthorized`, with its status already read.
    private static func status(of error: (any Error)?) -> Int? {
        switch error as? TransportError {
        case .unauthorized: 401
        case .http(let status, _): status
        default: nil
        }
    }
}
