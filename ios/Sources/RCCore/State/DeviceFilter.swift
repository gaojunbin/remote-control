import Foundation

/// The Devices screen's filter (owner's ruling, 2026-09-18): the same control
/// the Sessions screen has for agents, top right, narrowing the list to the
/// machines on one platform. A view of the list rather than a setting, so it
/// is not remembered; the choices are the platforms actually present, in the
/// order the list first shows them.
public enum DeviceFilter {
    /// The platforms the list can be narrowed to, each once, first seen first.
    public static func platforms(in devices: [Device]) -> [DevicePlatform] {
        var found: [DevicePlatform] = []
        for device in devices where !found.contains(device.platform) {
            found.append(device.platform)
        }
        return found
    }

    /// The devices left once the filter is applied; nil means all of them.
    public static func apply(_ devices: [Device], platform: DevicePlatform?) -> [Device] {
        guard let platform else { return devices }
        return devices.filter { $0.platform == platform }
    }
}
