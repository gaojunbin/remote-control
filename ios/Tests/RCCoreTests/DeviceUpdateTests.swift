import Testing
import Foundation
@testable import RCCore

/// Amendment A22: what a device row says about its client build, and when
/// Update can be asked for at all.
@Suite("Amendment A22, updating a device from the app")
struct DeviceUpdateTests {
    private let served = String(repeating: "a", count: 64)
    private let old = String(repeating: "b", count: 64)

    private func device(build: String?, online: Bool = true,
                        state: DeviceUpdateState = .idle, message: String? = nil) -> Device {
        Device(deviceID: "d1", name: "mac-studio", platform: .macos, hostname: "mac-studio.local",
               arch: "arm64", clientVersion: "0.1.0", clientBuild: build,
               updateState: state, updateMessage: message,
               online: online, lastSeen: 0, createdAt: 0)
    }

    @Test("A device on the served build says nothing and offers no update")
    func current() {
        let device = device(build: served)
        #expect(DeviceUpdate.notice(for: device, servedBuild: served) == nil)
        #expect(DeviceUpdate.block(for: device, servedBuild: served) == .current)
    }

    @Test("A device on another build, or on none, has an update available")
    func behind() {
        #expect(DeviceUpdate.notice(for: device(build: old), servedBuild: served) == .available)
        #expect(DeviceUpdate.notice(for: device(build: nil), servedBuild: served) == .available)
        #expect(DeviceUpdate.block(for: device(build: old), servedBuild: served) == nil)
    }

    @Test("A gateway serving no wheel never calls a device out of date")
    func noServedBuild() {
        let device = device(build: old)
        #expect(DeviceUpdate.notice(for: device, servedBuild: nil) == nil)
        #expect(DeviceUpdate.block(for: device, servedBuild: nil) == .noServedBuild)
        #expect(GatewayConfig.empty.servedBuild == nil)
    }

    @Test("An update in flight outranks everything else the row could say")
    func updating() {
        let device = device(build: old, state: .updating)
        #expect(DeviceUpdate.notice(for: device, servedBuild: served) == .updating)
        #expect(DeviceUpdate.block(for: device, servedBuild: served) == .inFlight)
    }

    @Test("A failed update shows the device's own reason, and lets it be tried again")
    func failed() {
        let device = device(build: old, state: .failed, message: "2 sessions are running")
        #expect(DeviceUpdate.notice(for: device, servedBuild: served)
                == .failed("2 sessions are running"))
        #expect(DeviceUpdate.block(for: device, servedBuild: served) == nil)
    }

    @Test("A refusal the app holds is shown the same way as one the gateway kept")
    func localRefusal() {
        let device = device(build: old)
        #expect(DeviceUpdate.notice(for: device, servedBuild: served, localError: "already on this build")
                == .failed("already on this build"))
    }

    @Test("An offline device is never asked to update itself")
    func offline() {
        #expect(DeviceUpdate.block(for: device(build: old, online: false), servedBuild: served)
                == .offline)
    }

    @Test("The build is named by its first eight characters")
    func shortBuild() {
        #expect(DeviceUpdate.shortBuild(served) == "aaaaaaaa")
    }

    @Test("A device record carries the build, the state and the reason")
    func decoding() throws {
        let json = """
        {"device_id": "d1", "name": "mac", "platform": "macos", "hostname": "mac.local",
         "arch": "arm64", "client_version": "0.1.0", "client_build": "\(old)",
         "update_state": "failed", "update_message": "the device did not come back",
         "online": true, "last_seen": 1, "created_at": 1, "latency_ms": null, "agents": []}
        """
        let device = try JSONDecoder().decode(Device.self, from: Data(json.utf8))
        #expect(device.clientBuild == old)
        #expect(device.updateState == .failed)
        #expect(device.updateMessage == "the device did not come back")
    }

    @Test("A record with no update fields at all is a device at rest")
    func decodingWithoutUpdateFields() throws {
        let json = """
        {"device_id": "d1", "name": "mac", "platform": "macos", "hostname": "mac.local",
         "arch": "arm64", "client_version": "0.1.0", "online": true,
         "last_seen": 1, "created_at": 1, "agents": []}
        """
        let device = try JSONDecoder().decode(Device.self, from: Data(json.utf8))
        #expect(device.clientBuild == nil)
        #expect(device.updateState == .idle)
    }

    @Test("The request names the device and the build it must land on")
    func request() {
        let request = GatewayRequest.updateDevice(deviceID: "d1", build: served)
        #expect(request.type == "device.update")
        #expect(request.body["device_id"] == .string("d1"))
        #expect(request.body["build"] == .string(served))
    }

    @Test("The demo device takes the update, then comes back on the new build")
    func demoUpdate() async throws {
        let gateway = DemoGateway(resumeDelay: nil)
        let laptop = DemoFixtures.laptopDeviceID
        let result = try await gateway.request(
            .updateDevice(deviceID: laptop, build: DemoFixtures.servedBuild))
            .decode(DeviceUpdateResult.self)
        #expect(result.accepted)
        #expect(result.from == DemoFixtures.outdatedBuild)

        let before = try await gateway.devices().first { $0.deviceID == laptop }
        #expect(before?.updateState == .updating)

        var settled: Device?
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            try? await Task.sleep(for: .milliseconds(100))
            let device = try await gateway.devices().first { $0.deviceID == laptop }
            if device?.updateState == .idle { settled = device; break }
        }
        #expect(settled?.clientBuild == DemoFixtures.servedBuild)
        await gateway.disconnect()
    }

    @Test("A device already on the build refuses rather than reinstalling it")
    func demoRefusesTheSameBuild() async {
        let gateway = DemoGateway(resumeDelay: nil)
        await #expect(throws: GatewayErrorBody.self) {
            _ = try await gateway.request(.updateDevice(deviceID: DemoFixtures.macDeviceID,
                                                        build: DemoFixtures.servedBuild))
        }
        await gateway.disconnect()
    }

    @Test("An offline device refuses before anything is downloaded")
    func demoRefusesOffline() async {
        let gateway = DemoGateway(resumeDelay: nil)
        await #expect(throws: GatewayErrorBody.self) {
            _ = try await gateway.request(.updateDevice(deviceID: DemoFixtures.ciDeviceID,
                                                        build: DemoFixtures.servedBuild))
        }
        await gateway.disconnect()
    }
}

/// Amendment A23: the link a host prints as a QR code, and the claim it leads to.
@Suite("Amendment A23, pairing by scanning")
struct PairingClaimTests {
    private let gateway = try! GatewayEndpoint("https://rc.example.com")

    @Test("The printed link carries a claim token for this gateway")
    func parses() {
        let link = PairingClaimLink(payload: "https://rc.example.com/pair#7ZK3M9Q2X5H8B1V4N6P0R2T4W6",
                                    gateway: gateway)
        #expect(link?.token == "7ZK3M9Q2X5H8B1V4N6P0R2T4W6")
    }

    @Test("A default port and a trailing slash still name the same gateway")
    func canonicalOrigin() {
        #expect(PairingClaimLink(payload: "https://RC.example.com:443/pair/#ABCDEFGH",
                                 gateway: gateway)?.token == "ABCDEFGH")
    }

    @Test("A link for another gateway is not ours to claim")
    func otherGateway() {
        #expect(PairingClaimLink(payload: "https://other.example.com/pair#ABCDEFGH",
                                 gateway: gateway) == nil)
    }

    @Test("Anything that is not a claim link is refused before the gateway hears about it")
    func malformed() {
        for payload in ["https://rc.example.com/pair", "https://rc.example.com/#ABCDEFGH",
                        "not a url at all", "https://rc.example.com/pair#short",
                        "https://rc.example.com/pair#ABCDEFGI!"] {
            #expect(PairingClaimLink(payload: payload, gateway: gateway) == nil, "\(payload)")
        }
    }

    @Test("Either origin the app knows the gateway by is accepted")
    func severalOrigins() throws {
        let lan = try GatewayEndpoint("http://192.168.1.20:8080")
        let link = PairingClaimLink(payload: "https://rc.example.com/pair#ABCDEFGH",
                                    gateways: [lan, gateway])
        #expect(link?.token == "ABCDEFGH")
    }

    @Test("A claimed token hands the flow the gateway's own pairing code")
    func claimFollowsProgress() async throws {
        let gateway = DemoGateway(resumeDelay: nil)
        let flow = await PairingFlow(api: gateway)
        await flow.begin()
        let minted = await flow.code
        #expect(!minted.isEmpty)

        try await flow.claim(token: DemoFixtures.claimToken)
        let claimed = await flow.code
        #expect(claimed == DemoFixtures.pairingClaim.code)
        #expect(claimed != minted)
        // The scan flow has no one-liner: the host ran one to get here.
        #expect(await flow.command.isEmpty)

        await flow.receive(.pairingProgress(PairingProgress(code: claimed, step: .enrolled, device: nil)))
        #expect(await flow.reached == .enrolled)
        await gateway.disconnect()
    }

    @Test("An unknown token is refused")
    func unknownToken() async {
        let gateway = DemoGateway(resumeDelay: nil)
        let flow = await PairingFlow(api: gateway)
        await #expect(throws: GatewayErrorBody.self) {
            try await flow.claim(token: "0000000000000000000000000A")
        }
        await gateway.disconnect()
    }
}
