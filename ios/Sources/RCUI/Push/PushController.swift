import Foundation
import Observation
import RCCore

public enum PushAuthorization: Sendable, Equatable {
    case notDetermined, denied, authorized, provisional, unsupported
}

/// The system side of notifications, so the controller can be driven without
/// UserNotifications in a preview or a check.
@MainActor
public protocol NotificationPlatform: AnyObject {
    var supported: Bool { get }
    var environment: String? { get }
    var token: String? { get }
    var onChange: (() -> Void)? { get set }
    var onOpen: ((PushRoute) -> Void)? { get set }
    func authorization() async -> PushAuthorization
    func requestAuthorization() async throws -> Bool
    func register()
    func unregister()
    func openSettings()
}

/// Reconciles one preference, one system authorization, one APNs token and one
/// gateway registration.
///
/// Work is serialized through a single chained task, so a slow registration can
/// never land after the user turned notifications back off.
@MainActor
@Observable
public final class PushController {
    public private(set) var authorization: PushAuthorization = .notDetermined
    public private(set) var statusText = "Off"
    public private(set) var errorMessage: String?

    @ObservationIgnored private let platform: any NotificationPlatform
    @ObservationIgnored private var api: (any GatewayAPI)?
    @ObservationIgnored private var enabled = false
    @ObservationIgnored private var registeredToken: String?
    @ObservationIgnored private var operation: Task<Void, Never>?
    @ObservationIgnored private let bundleID: String

    public init(platform: any NotificationPlatform,
                bundleID: String = Bundle.main.bundleIdentifier ?? "com.junbingao.remotecontrol") {
        self.platform = platform
        self.bundleID = bundleID
        platform.onChange = { [weak self] in self?.reconcile() }
    }

    public var isSupported: Bool { platform.supported }

    /// Notifications can only be re-enabled in iOS Settings once denied.
    public func openSystemSettings() { platform.openSettings() }

    public func attach(api: (any GatewayAPI)?, enabled: Bool, onOpen: @escaping (PushRoute) -> Void) {
        self.api = api
        self.enabled = enabled
        platform.onOpen = onOpen
        reconcile()
    }

    public func setEnabled(_ value: Bool) {
        enabled = value
        reconcile()
    }

    /// Ask the system, then register. Called from the settings toggle.
    public func requestAuthorizationIfNeeded() {
        chain { [weak self] in
            guard let self else { return }
            do {
                if await self.platform.authorization() == .notDetermined {
                    _ = try await self.platform.requestAuthorization()
                }
            } catch {
                self.errorMessage = error.localizedDescription
            }
            await self.apply()
        }
    }

    public func detach() {
        chain { [weak self] in
            guard let self, let api = self.api, let token = self.registeredToken else { return }
            self.registeredToken = nil
            try? await api.unregisterPush(token: token)
            self.platform.unregister()
            self.statusText = "Off"
        }
    }

    private func reconcile() {
        chain { [weak self] in await self?.apply() }
    }

    private func apply() async {
        guard platform.supported else {
            authorization = .unsupported
            statusText = "Not available on this device"
            return
        }
        authorization = await platform.authorization()
        guard enabled else {
            if let api, let token = registeredToken {
                registeredToken = nil
                try? await api.unregisterPush(token: token)
            }
            platform.unregister()
            statusText = "Off"
            return
        }
        switch authorization {
        case .denied:
            statusText = "Blocked in iOS Settings"
            return
        case .notDetermined:
            statusText = "Waiting for permission"
            return
        default:
            break
        }
        platform.register()
        guard let token = platform.token else {
            statusText = "Registering with Apple"
            return
        }
        guard let api, let environment = platform.environment else {
            statusText = "Waiting for the gateway"
            return
        }
        guard token != registeredToken else { return }
        do {
            try await api.registerPush(APNSRegistration(token: token, environment: environment,
                                                        bundleID: bundleID))
            registeredToken = token
            errorMessage = nil
            statusText = "On"
        } catch {
            statusText = "The gateway refused the registration"
            errorMessage = (error as? TransportError)?.errorDescription ?? error.localizedDescription
        }
    }

    /// Serialize every step: a registration must never overtake a removal.
    private func chain(_ work: @escaping @MainActor () async -> Void) {
        let previous = operation
        operation = Task { @MainActor in
            await previous?.value
            await work()
        }
    }
}
