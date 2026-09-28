import Foundation
import RCCore

/// `web/src/lib/accountErrors.ts` — A24: what the account routes of 3.9
/// refuse, in words, read from `error.code` so the sentence follows the gateway
/// rather than the status line alone.
public enum AccountErrors {
    /// `conflict` means two different things on these routes — a taken
    /// username on `POST /api/users`, and `admin` refusing to be changed
    /// elsewhere — so the caller supplies the sentence for its own route.
    public static func userErrorText(_ error: any Error, conflict: String) -> String {
        guard case .http(_, let code) = error as? TransportError else { return S.errors.generic }
        switch code {
        case GatewayErrorCode.conflict.rawValue: return conflict
        case GatewayErrorCode.badRequest.rawValue: return S.account.rules
        case GatewayErrorCode.forbidden.rawValue: return S.account.notAllowed
        case GatewayErrorCode.notFound.rawValue: return S.account.gone
        default: return S.errors.generic
        }
    }
}
