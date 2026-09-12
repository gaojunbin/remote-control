import Foundation

/// Amendment A22: what a device row says about its client build, and whether
/// Update can be offered at all.
///
/// Both apps decide this the same way, and the rule is here rather than in a
/// view so it can be tested without one.
public enum DeviceUpdate {
    /// The line under the hostname, when there is one. `nil` means the row says
    /// nothing beyond the version and the build it runs.
    public enum Notice: Sendable, Hashable {
        case available
        case updating
        case failed(String)
    }

    /// Why Update cannot be asked for. `nil` means it can.
    public enum Block: Sendable, Hashable {
        case offline
        case inFlight
        case noServedBuild
        case current
    }

    /// A build is a SHA-256; eight characters name it without filling the row.
    public static func shortBuild(_ build: String) -> String { String(build.prefix(8)) }

    /// A device is behind when the gateway serves a build and this one is not
    /// on it — including the device that cannot say which build it runs, since
    /// an unknown build is not the served one.
    public static func isBehind(_ device: Device, servedBuild: String?) -> Bool {
        guard let servedBuild else { return false }
        return device.clientBuild != servedBuild
    }

    /// `localError` is a refusal the device replied with, which lives in the app
    /// rather than on the record: the gateway never saw an update start.
    public static func notice(for device: Device, servedBuild: String?,
                              localError: String? = nil) -> Notice? {
        if device.updateState == .updating { return .updating }
        if let localError { return .failed(localError) }
        if device.updateState == .failed, let message = device.updateMessage { return .failed(message) }
        return isBehind(device, servedBuild: servedBuild) ? .available : nil
    }

    public static func block(for device: Device, servedBuild: String?) -> Block? {
        if !device.online { return .offline }
        if device.updateState == .updating { return .inFlight }
        guard let servedBuild else { return .noServedBuild }
        return device.clientBuild == servedBuild ? .current : nil
    }
}
