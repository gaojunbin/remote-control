import Testing
import Foundation
@testable import RCCore

/// The Devices screen's platform filter (owner's ruling, 2026-09-18).
@Suite("The device filter")
struct DeviceFilterTests {
    private func device(_ id: String, _ platform: DevicePlatform) -> Device {
        Device(deviceID: id, name: id, platform: platform, hostname: "\(id).local", arch: "arm64",
               clientVersion: "1.4.4", online: true, lastSeen: 0, createdAt: 0)
    }

    @Test("The choices are the platforms present, each once, first seen first")
    func platforms() {
        let devices = [device("a", .macos), device("b", .linux), device("c", .macos),
                       device("d", DevicePlatform(rawValue: "freebsd"))]
        #expect(DeviceFilter.platforms(in: devices) == [.macos, .linux, DevicePlatform(rawValue: "freebsd")])
        #expect(DeviceFilter.platforms(in: []).isEmpty)
    }

    @Test("Nil keeps every device; a platform keeps its own and no other")
    func apply() {
        let devices = [device("a", .macos), device("b", .linux), device("c", .macos)]
        #expect(DeviceFilter.apply(devices, platform: nil).map(\.deviceID) == ["a", "b", "c"])
        #expect(DeviceFilter.apply(devices, platform: .linux).map(\.deviceID) == ["b"])
        #expect(DeviceFilter.apply(devices, platform: .macos).map(\.deviceID) == ["a", "c"])
        #expect(DeviceFilter.apply(devices, platform: DevicePlatform(rawValue: "freebsd")).isEmpty)
    }
}
