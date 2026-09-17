import Testing
import Foundation
@testable import RCCore

/// Amendment A36: what a device says about its client now that the gateway
/// keeps every machine on the wheel it serves, and when a person may ask for an
/// update at all.
@Suite("Amendment A36, a device keeps itself current")
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

    @Test("A device on the served build says nothing and offers nothing")
    func current() {
        let device = device(build: served)
        #expect(DeviceUpdate.notice(for: device) == nil)
        #expect(!DeviceUpdate.canRetry(device))
    }

    @Test("A device on another build, or on none, is the gateway's to bring forward")
    func behind() {
        #expect(DeviceUpdate.notice(for: device(build: old)) == nil)
        #expect(DeviceUpdate.notice(for: device(build: nil)) == nil)
        #expect(!DeviceUpdate.canRetry(device(build: old)))
    }

    @Test("The config names the version a retry would install, or nothing at all")
    func servedVersion() throws {
        // The gateway's own version and the client's differ here on purpose:
        // `servedVersion` is the wheel's, and a decoder that read the outer
        // one would pass on a config where the two agree.
        let json = """
        {"public_origin": "https://rc.example.com", "version": "2.0.0",
         "client": {"version": "\(AppBuild.shipped)", "build": "\(served)",
                    "url": "/dist/rc_client-latest.whl"}}
        """
        let config = try JSONDecoder().decode(GatewayConfig.self, from: Data(json.utf8))
        #expect(config.servedVersion == AppBuild.shipped)
        #expect(GatewayConfig.empty.servedVersion == nil)

        // An older gateway answers with a build and no version; the screens
        // that name it have wording for that.
        let older = """
        {"public_origin": "https://rc.example.com", "version": "1.3.0",
         "client": {"build": "\(served)", "url": "/dist/rc_client-latest.whl"}}
        """
        let decoded = try JSONDecoder().decode(GatewayConfig.self, from: Data(older.utf8))
        #expect(decoded.servedBuild == served)
        #expect(decoded.servedVersion == nil)
    }

    @Test("An update in flight is said, and is not something to retry")
    func updating() {
        let device = device(build: old, state: .updating)
        #expect(DeviceUpdate.notice(for: device) == .updating)
        #expect(!DeviceUpdate.canRetry(device))
    }

    @Test("A failed update shows the device's own reason, and only then offers a retry")
    func failed() {
        let device = device(build: old, state: .failed, message: "2 sessions are running")
        #expect(DeviceUpdate.notice(for: device) == .failed("2 sessions are running"))
        #expect(DeviceUpdate.canRetry(device))
        #expect(DeviceUpdate.block(for: device, servedBuild: served) == nil)
    }

    @Test("A refusal the app holds is shown the same way as one the gateway kept")
    func localRefusal() {
        let device = device(build: old, state: .failed, message: "2 sessions are running")
        #expect(DeviceUpdate.notice(for: device, localError: "already on this build")
                == .failed("already on this build"))
    }

    @Test("An offline device, and a gateway with no wheel, cannot be retried")
    func blocked() {
        let stranded = device(build: old, state: .failed, message: "the device did not come back")
        #expect(DeviceUpdate.block(for: stranded, servedBuild: nil) == .noServedBuild)
        #expect(GatewayConfig.empty.servedBuild == nil)
        #expect(DeviceUpdate.block(for: device(build: old, online: false), servedBuild: served)
                == .offline)
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

    @Test("The demo's failed device takes a retry, then comes back on the new build")
    func demoRetry() async throws {
        let gateway = DemoGateway(resumeDelay: nil)
        let laptop = DemoFixtures.laptopDeviceID
        let stranded = try await gateway.devices().first { $0.deviceID == laptop }
        #expect(stranded?.updateState == .failed)
        #expect(stranded?.updateMessage == DemoFixtures.updateFailure)

        let result = try await gateway.request(
            .updateDevice(deviceID: laptop, build: DemoFixtures.servedBuild))
            .decode(DeviceUpdateResult.self)
        #expect(result.accepted)
        #expect(result.from == DemoFixtures.outdatedBuild)

        let before = try await gateway.devices().first { $0.deviceID == laptop }
        #expect(before?.updateState == .updating)
        #expect(before?.updateMessage == nil)

        var settled: Device?
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            try? await Task.sleep(for: .milliseconds(100))
            let device = try await gateway.devices().first { $0.deviceID == laptop }
            if device?.updateState == .idle { settled = device; break }
        }
        #expect(settled?.clientBuild == DemoFixtures.servedBuild)
        #expect(settled?.clientVersion == DemoFixtures.servedClientVersion)
        #expect(settled.map { DeviceUpdate.notice(for: $0) == nil } == true)
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
