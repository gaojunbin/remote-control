import Foundation
import RCCore

extension MacAppModel {
    public func device(_ id: String) -> Device? { connection.device(id) }

    public func device(for session: Session) -> Device? { connection.device(session.deviceID) }

    /// The agent a session runs, as its device describes it.
    public func agent(for session: Session) -> AgentInfo? {
        connection.device(session.deviceID)?.agent(session.agent)
    }

    /// Amendment A22: ask a device to fetch the build the gateway serves. The
    /// accepted case says nothing here — `device.updated` carries the state the
    /// row draws from then on; a refusal is the device's own words.
    public func updateDevice(_ device: Device) async {
        guard let channel = connection.channel, let build = connection.config.servedBuild else { return }
        deviceUpdateErrors.removeValue(forKey: device.deviceID)
        do {
            _ = try await channel.request(.updateDevice(deviceID: device.deviceID, build: build),
                                          as: DeviceUpdateResult.self)
        } catch let refusal as GatewayErrorBody {
            deviceUpdateErrors[device.deviceID] = refusal.message
        } catch {
            deviceUpdateErrors[device.deviceID] = S.errors.generic
        }
    }

    /// The Add device flow on this connection's own credential.
    public func pairingFlow() -> PairingFlow? {
        guard let api = connection.api else { return nil }
        return PairingFlow(api: api)
    }
}
