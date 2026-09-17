import Testing
import Foundation
@testable import RCCore

/// `docs/DESIGN.md` § "The device row" (owner's ruling, 2026-09-17): what a
/// machine's row says, and what only its page says.
@Suite("The device row says less")
struct DeviceLineTests {
    private func device(platform: DevicePlatform, online: Bool = true,
                        hostname: String = "mac-studio.local",
                        arch: String = "arm64") -> Device {
        Device(deviceID: "d1", name: "mac-studio-office", platform: platform,
               hostname: hostname, arch: arch, clientVersion: "1.3.4",
               online: online, lastSeen: 0, createdAt: 0)
    }

    @Test("A platform is a word, and an unknown one is printed as it arrived")
    func platformName() {
        #expect(DeviceLine.platformName(.macos) == "macOS")
        #expect(DeviceLine.platformName(.linux) == "Linux")
        #expect(DeviceLine.platformName(DevicePlatform(rawValue: "freebsd")) == "freebsd")
    }

    @Test("The row's line is the state and the platform, and neither host nor chip")
    func status() {
        #expect(DeviceLine.status(device(platform: .macos)) == "online · macOS")
        #expect(DeviceLine.status(device(platform: .linux, online: false)) == "offline · Linux")
        let line = DeviceLine.status(device(platform: .macos))
        #expect(!line.contains("mac-studio.local"))
        #expect(!line.contains("arm64"))
        #expect(!line.contains("macos"))
    }

    @Test("The page's line is the hostname and the architecture")
    func facts() {
        #expect(DeviceLine.facts(device(platform: .linux, hostname: "ci-runner-01",
                                        arch: "x86_64")) == "ci-runner-01 · x86_64")
    }

    @Test("A device that reported neither half writes no separator at all")
    func missingFacts() {
        #expect(DeviceLine.facts(device(platform: .macos, hostname: "", arch: "")) == "")
        #expect(DeviceLine.facts(device(platform: .macos, arch: "")) == "mac-studio.local")
    }
}
