import RCCore
import Testing
@testable import RCMac

/// `web/tests/AddDeviceModal.test.tsx`, on what the modal decides: the steps
/// the handshake lights, the progress line, the frames it listens to, when
/// Continue is enabled, which code a closing modal gives back, and the expiry.
@Suite("Lists: pairing") @MainActor
struct ListsPairingTests {
    private let grant = PairingGrant(code: "RC-7K42-QX9M", expiresAt: 600_000,
                                     install: InstallCommands(macos: "curl … --pair RC-7K42-QX9M",
                                                              linux: "curl … --pair RC-7K42-QX9M"))

    private func pairing() async -> AddDevicePairing {
        let pairing = AddDevicePairing()
        await pairing.request(api: DemoGateway())
        return pairing
    }

    @Test func lightsTheStepsAsTheHandshakeGoes() {
        #expect(PairingChecklist.marks(nil) == [.done, .active, .idle])
        #expect(PairingChecklist.marks(.waiting) == [.done, .active, .idle])
        #expect(PairingChecklist.marks(.enrolled) == [.done, .done, .idle])
        #expect(PairingChecklist.marks(.online) == [.done, .done, .active])
        #expect(PairingChecklist.marks(.agents) == [.done, .done, .done])
    }

    @Test func fillsTheProgressLineAQuarterAStepFrom12PerCent() {
        #expect(PairingChecklist.progress(nil) == 12)
        #expect(PairingChecklist.progress(.waiting) == 25)
        #expect(PairingChecklist.progress(.enrolled) == 50)
        #expect(PairingChecklist.progress(.agents) == 100)
    }

    @Test func namesTheAgentsTheNewDeviceFoundByID() {
        let device = Device(deviceID: "d", name: "new-laptop", platform: .macos, hostname: "", arch: "",
                            clientVersion: "", online: true, lastSeen: 0, createdAt: 0,
                            agents: [AgentInfo(agent: "claude", available: true), AgentInfo(agent: "grok", available: false),
                                     AgentInfo(agent: "codex", available: true)])
        #expect(PairingChecklist.agents(PairingProgress(code: "x", step: .agents, device: device)) == "claude · codex")
        #expect(PairingChecklist.agents(nil) == "")
    }

    @Test func showsTheOneCommandTheGatewayHandedOut() async {
        let pairing = await pairing()
        #expect(pairing.grant?.code == DemoFixtures.pairingGrant.code)
        #expect(pairing.command == DemoFixtures.pairingGrant.install.macos)
        #expect(!pairing.failed)
    }

    @Test func saysSoWhenNoCodeCouldBeMade() async {
        let pairing = AddDevicePairing()
        await pairing.request(api: nil)
        #expect(pairing.failed && pairing.grant == nil)
    }

    @Test func walksTheLiveStepsAndOnlyThenEnablesContinue() async {
        let pairing = await pairing()
        let code = pairing.grant?.code ?? ""
        pairing.receive(.pairingProgress(PairingProgress(code: code, step: .enrolled)))
        #expect(pairing.step == .enrolled && !pairing.connected)
        #expect(pairing.unclaimedCode == code)
        pairing.receive(.pairingProgress(PairingProgress(code: code, step: .online)))
        #expect(pairing.connected)
        #expect(pairing.unclaimedCode == nil)
    }

    @Test func ignoresProgressFramesForADifferentCode() async {
        let pairing = await pairing()
        pairing.receive(.pairingProgress(PairingProgress(code: "RC-OTHER-CODE", step: .agents)))
        #expect(pairing.live == nil && pairing.step == nil && !pairing.connected)
    }

    @Test func countsDownOnTheGatewaysClockAndCallsAnUnclaimedCodeExpired() async {
        let pairing = await pairing()
        let expires = pairing.grant?.expiresAt ?? 0
        #expect(pairing.remaining(now: expires - 90_000) == 90_000)
        #expect(!pairing.hasExpired(now: expires - 1))
        #expect(pairing.hasExpired(now: expires + 5))
        pairing.receive(.pairingProgress(PairingProgress(code: pairing.grant?.code ?? "", step: .online)))
        #expect(!pairing.hasExpired(now: expires + 5))
    }

    @Test func readsTheGatewaysClockOffHello() {
        let clock = GatewayClock()
        #expect(clock.skew == 0)
        clock.receive(.hello(HelloFrame(protocolVersion: 1, gatewayVersion: "1", user: UserIdentity(username: "admin"),
                                        devices: [], sessions: [], stt: .disabled,
                                        serverTime: Format.nowMillis + 60_000)))
        #expect(abs(clock.skew - 60_000) < 1_000)
        clock.receive(.hello(HelloFrame(protocolVersion: 1, gatewayVersion: "1", user: UserIdentity(username: "admin"),
                                        devices: [], sessions: [], stt: .disabled, serverTime: 0)))
        #expect(clock.skew == 0)
    }
}
