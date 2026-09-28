import Foundation
import RCCore

/// How far the gateway's clock is from this Mac's, as the web's `clockSkewMs`:
/// read off every `hello`, so a pairing code's `expires_at` — a gateway
/// timestamp — is counted down against the gateway's clock rather than a local
/// one that may be wrong.
@MainActor
final class GatewayClock {
    private(set) var skew: Int64 = 0

    /// A `hello` without `server_time` decodes it as 0, which says nothing.
    func receive(_ frame: AppFrame) {
        guard case .hello(let hello) = frame else { return }
        skew = hello.serverTime > 0 ? hello.serverTime - Format.nowMillis : 0
    }

    /// Now, on the gateway's clock.
    var now: Int64 { Format.nowMillis + skew }
}
