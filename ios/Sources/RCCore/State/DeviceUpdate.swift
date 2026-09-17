import Foundation

/// Amendment A36: what an app says about a device's client, and when a person
/// may ask for an update at all.
///
/// The gateway brings every device to the wheel it serves without being asked,
/// so the client version is nobody's to watch: an app states neither it nor its
/// build, and offers nothing while nothing is wrong. What is left is an update
/// in flight and one that failed. Both apps decide this the same way, and the
/// rule is here rather than in a view so it can be tested without one.
public enum DeviceUpdate {
    /// The line a device draws about its client, when there is one. `nil` means
    /// it says nothing at all.
    public enum Notice: Sendable, Hashable {
        case updating
        case failed(String)
    }

    /// Why Retry update cannot be asked for. `nil` means it can.
    public enum Block: Sendable, Hashable {
        case offline
        case noServedBuild
    }

    /// `localError` is a refusal the device replied with, which lives in the app
    /// rather than on the record: the gateway never saw an update start.
    public static func notice(for device: Device, localError: String? = nil) -> Notice? {
        if device.updateState == .updating { return .updating }
        if let localError { return .failed(localError) }
        if device.updateState == .failed, let message = device.updateMessage { return .failed(message) }
        return nil
    }

    /// A person steps in only where the gateway gave up: a failed update is the
    /// one state Retry is offered in.
    public static func canRetry(_ device: Device) -> Bool { device.updateState == .failed }

    public static func block(for device: Device, servedBuild: String?) -> Block? {
        if !device.online { return .offline }
        return servedBuild == nil ? .noServedBuild : nil
    }
}
