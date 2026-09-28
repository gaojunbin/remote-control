import Foundation
import RCCore

/// A36, in the web's words: `updateNotice` in `web/src/stores/devices.ts` and
/// the two reasons the row and the page give for a Retry they cannot send.
/// RCCore's `DeviceUpdate` decides; this says it.
enum DeviceUpdateWords {
    /// The row's third line, or nil when there is nothing to say — the ordinary
    /// case, because the gateway brings every device to the wheel it serves on
    /// its own.
    static func notice(for device: Device, localError: String?) -> (text: String, failed: Bool)? {
        switch DeviceUpdate.notice(for: device, localError: localError) {
        case .updating: (S.devices.updating, false)
        case .failed(let reason): (S.devices.updateFailed(reason), true)
        case nil: nil
        }
    }

    /// Why Retry update cannot be pressed, or nil when it can.
    static func retryBlocked(_ device: Device, servedBuild: String?) -> String? {
        switch DeviceUpdate.block(for: device, servedBuild: servedBuild) {
        case .offline: S.devices.deviceOffline
        case .noServedBuild: S.devices.updateNoBuild
        case nil: nil
        }
    }
}
