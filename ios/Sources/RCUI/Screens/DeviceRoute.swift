import Foundation
import RCCore

/// Where a device row can lead (amendment A38, rule 20).
///
/// Two destinations, not one: the row's tap opens a shell and the row's menu
/// opens the machine's page, so the stack needs to tell them apart.
public enum DeviceRoute: Hashable, Sendable {
    /// The machine's own page: the agents on it, and what is left of each
    /// account's quota (A33). Reached from the menu as **Show quota**.
    case page(String)
    /// A shell on the machine (7.3).
    case terminal(String)
}

/// What the row's tap does, decided from the device alone.
///
/// Rule 20: tapping a device that is online and offers a terminal opens one; an
/// offline device or one without the capability says so instead of opening
/// anything. It is a function and not a branch inside a view so the rule can be
/// read, and checked, in one place.
public enum DeviceTap {
    public enum Outcome: Equatable, Sendable {
        case terminal
        /// Nothing opens, and this is what the row says in place.
        case refused(String)
    }

    public static func outcome(for device: Device) -> Outcome {
        guard device.online else { return .refused(L10n.string("This device is offline.")) }
        guard device.offersTerminal else {
            return .refused(L10n.string("This device does not offer a terminal."))
        }
        return .terminal
    }
}

/// The row's swipe and its context menu, in one order on both apps (rule 20):
/// **Rename**, **Retry update** (only while an update has failed, A36),
/// **Show quota**, **Revoke**.
public enum DeviceRowAction: String, Sendable, Hashable, CaseIterable, Identifiable {
    case rename
    case retryUpdate
    case showQuota
    case revoke

    public var id: String { rawValue }

    /// The actions this machine offers, in the order they are offered.
    public static func menu(for device: Device) -> [DeviceRowAction] {
        allCases.filter { $0 != .retryUpdate || DeviceUpdate.canRetry(device) }
    }

    /// The identifier the checks and the UI tests look the action up by.
    public var identifier: String {
        switch self {
        case .rename: "device.rename"
        case .retryUpdate: "device.retryUpdate"
        case .showQuota: "device.showQuota"
        case .revoke: "device.revoke"
        }
    }

    public var symbol: String {
        switch self {
        case .rename: "pencil"
        case .retryUpdate: "arrow.clockwise"
        case .showQuota: "gauge.with.dots.needle.33percent"
        case .revoke: "trash"
        }
    }
}
