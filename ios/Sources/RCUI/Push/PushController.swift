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

/// Where the reconciliation below got to, in one value.
///
/// The words are built from it at read time rather than stored: a string
/// `L10n.string` produced and a store kept would go on saying what it said in
/// the language it was first built in, long after the reader changed it.
public enum PushStatus: Sendable, Equatable {
    case off, unsupported, denied, waitingForPermission, appOnly, registering, on, refused

    public var text: String {
        switch self {
        case .off: L10n.string("Off")
        case .unsupported: L10n.string("Not available on this device")
        case .denied: L10n.string("Blocked in iOS Settings")
        case .waitingForPermission: L10n.string("Waiting for permission")
        case .appOnly: L10n.string("On, in this app only")
        case .registering: L10n.string("Registering with Apple")
        case .on: L10n.string("On")
        case .refused: L10n.string("The gateway refused the registration")
        }
    }
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
    public private(set) var status: PushStatus = .off
    public var statusText: String { status.text }
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
            self.status = .off
        }
    }

    private func reconcile() {
        chain { [weak self] in await self?.apply() }
    }

    private func apply() async {
        guard platform.supported else {
            authorization = .unsupported
            status = .unsupported
            return
        }
        authorization = await platform.authorization()
        guard enabled else {
            if let api, let token = registeredToken {
                registeredToken = nil
                try? await api.unregisterPush(token: token)
            }
            platform.unregister()
            status = .off
            return
        }
        switch authorization {
        case .denied:
            status = .denied
            return
        case .notDetermined:
            status = .waitingForPermission
            return
        default:
            break
        }
        // With no gateway to hand a device token to — the demo, or before a
        // sign-in — nothing is registered with Apple at all. The switch still
        // holds: the app's own banners for a finished turn need no gateway.
        guard let api, let environment = platform.environment else {
            status = .appOnly
            return
        }
        platform.register()
        guard let token = platform.token else {
            status = .registering
            return
        }
        guard token != registeredToken else { return }
        do {
            try await api.registerPush(APNSRegistration(token: token, environment: environment,
                                                        bundleID: bundleID))
            registeredToken = token
            errorMessage = nil
            status = .on
        } catch {
            status = .refused
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
