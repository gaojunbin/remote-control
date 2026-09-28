import Foundation
import RCCore

/// What `web/src/stores/devices.ts` keeps about the list itself: the devices in
/// name order — the web sorts on every load and every upsert, where RCCore keeps
/// the order `hello` listed them in — and the numbers a row prints beside them.
enum DeviceOrder {
    /// `localeCompare` on the names, keeping two equal names in the order they came.
    static func byName(_ devices: [Device]) -> [Device] {
        SessionLayout.stableSorted(devices) { $0.name.localizedCompare($1.name) == .orderedAscending }
    }

    /// The online devices, in name order: what the New session drawer offers.
    static func online(_ devices: [Device]) -> [Device] {
        byName(devices).filter(\.online)
    }

    /// How many sessions each device has that nobody archived by hand, which is
    /// the count a device row prints.
    static func sessionCounts(_ sessions: [Session]) -> [String: Int] {
        var counts: [String: Int] = [:]
        for session in sessions where !session.archived { counts[session.deviceID, default: 0] += 1 }
        return counts
    }
}
