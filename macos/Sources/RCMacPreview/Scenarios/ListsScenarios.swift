import RCCore
import RCMac
import SwiftUI

/// The lists feature's scenarios: the Devices page with its menus, dialogs and
/// the Add device handshake, a device's page, the Sessions page with its groups,
/// search, filters and close question, the New session drawer with its
/// directory picker, and the conversation's session sidebar alone. Each works
/// on `--demo` and on the web's mock gateway: a scenario about "the device
/// whose update failed" finds that device in whichever gateway it is on.
enum ListsScenarios {
    static var all: [PreviewScenario] {
        devices + pairing + devicePage + sessions + drawer + sidebar
    }

    private static var devices: [PreviewScenario] {
        [
            PreviewScenario(name: "devices", route: .devices),
            PreviewScenario(name: "devices-900", route: .devices, width: 900),
            PreviewScenario(name: "devices-600", route: .devices, width: 600, height: 760),
            PreviewScenario(name: "devices-480", route: .devices, width: 480, height: 760),
            PreviewScenario(name: "devices-zh", route: .devices, language: .zhHans),
            PreviewScenario(name: "devices-menu", route: .devices, stage: "devices.menu"),
            PreviewScenario(name: "devices-menu-failed", route: .devices, stage: "devices.menu.failed"),
            PreviewScenario(name: "devices-refusal", route: .devices, stage: "devices.refusal"),
            PreviewScenario(name: "devices-rename", route: .devices, stage: "devices.rename"),
            PreviewScenario(name: "devices-revoke", route: .devices, stage: "devices.revoke"),
            PreviewScenario(name: "devices-retry", route: .devices, stage: "devices.retry"),
            PreviewScenario(name: "devices-updating", route: .devices, prepare: { context in
                // A retry the gateway takes on: the row says "Updating…" with
                // its dot pulsing until the device comes back.
                guard let failed = context.model.connection.devices.first(where: { $0.updateState == .failed })
                else { return }
                await context.model.updateDevice(failed)
                await context.wait { context.model.device(failed.deviceID)?.updateState == .updating }
            })
        ]
    }

    /// Add device at each step of the handshake, as `pairing.progress` arrives.
    private static var pairing: [PreviewScenario] {
        [
            PreviewScenario(name: "devices-add", route: .devices, stage: "devices.add", settle: .milliseconds(200)),
            PreviewScenario(name: "devices-add-manual", route: .devices, stage: "devices.add.manual"),
            handshake("devices-add-enrolled", reaching: .enrolled),
            handshake("devices-add-online", reaching: .online),
            handshake("devices-add-agents", reaching: .agents),
            PreviewScenario(name: "devices-add-zh", route: .devices, stage: "devices.add", language: .zhHans)
        ]
    }

    private static func handshake(_ name: String, reaching step: PairingStep) -> PreviewScenario {
        PreviewScenario(name: name, route: .devices, stage: "devices.add", settle: .milliseconds(300),
                        prepare: { context in
            let reached = StepWatch()
            context.model.connection.addFrameHandler("preview-pairing") { frame in
                if case .pairingProgress(let progress) = frame, progress.step.order >= step.order { reached.done = true }
            }
            await context.wait(timeout: .seconds(10)) { reached.done }
            context.model.connection.removeFrameHandler("preview-pairing")
        })
    }

    private static var devicePage: [PreviewScenario] {
        [
            page("device-page", settle: .milliseconds(1800)) { $0.first { $0.agents.count > 3 } ?? $0.first },
            page("device-page-checking", settle: .milliseconds(150)) { $0.first { $0.agents.count > 3 } ?? $0.first },
            page("device-page-failed", settle: .milliseconds(1800)) { $0.first { $0.updateState == .failed } },
            page("device-page-offline") { $0.first { !$0.online } ?? $0.first },
            page("device-page-480", width: 480, settle: .milliseconds(1800)) { $0.first { $0.agents.count > 3 } },
            page("device-page-zh", language: .zhHans, settle: .milliseconds(1800)) {
                $0.first { $0.agents.count > 3 }
            },
            PreviewScenario(name: "device-page-gone", route: .device(id: "no-such-device"))
        ]
    }

    /// A device's page, for the device `pick` finds on the gateway the render is on.
    private static func page(_ name: String, width: CGFloat? = nil, language: InterfaceLanguage? = nil,
                             settle: Duration = .milliseconds(900),
                             pick: @escaping @MainActor @Sendable ([Device]) -> Device?) -> PreviewScenario {
        PreviewScenario(name: name, route: .devices, width: width, language: language, settle: settle,
                        setup: { context in
            let devices = context.model.connection.devices.sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
            guard let device = pick(devices) else { return }
            context.model.router.replace(.device(id: device.deviceID))
        })
    }

    private static var sessions: [PreviewScenario] {
        [
            PreviewScenario(name: "sessions", route: .sessions),
            PreviewScenario(name: "sessions-900", route: .sessions, width: 900),
            PreviewScenario(name: "sessions-600", route: .sessions, width: 600),
            PreviewScenario(name: "sessions-zh", route: .sessions, language: .zhHans),
            PreviewScenario(name: "sessions-legend", route: .sessions, height: 420),
            PreviewScenario(name: "sessions-collapsed", route: .sessions, setup: { context in
                fold(context) { groups in groups.first.map { context.model.sessions.toggleCollapsed($0.id) } }
            }),
            // Tall enough to reach the Archives under the active rows.
            PreviewScenario(name: "sessions-archive", route: .sessions, height: 2400, setup: { context in
                fold(context) { groups in
                    for group in groups where !group.archive.isEmpty { context.model.sessions.toggleArchive(group.id) }
                }
            }),
            PreviewScenario(name: "sessions-search", route: .sessions, stage: "sessions.search:vite"),
            PreviewScenario(name: "sessions-search-none", route: .sessions, stage: "sessions.search:zzzz"),
            PreviewScenario(name: "sessions-filter-agent", route: .sessions, stage: "sessions.filter.agent"),
            PreviewScenario(name: "sessions-filter-device", route: .sessions, stage: "sessions.filter.device"),
            PreviewScenario(name: "sessions-filtered", route: .sessions, setup: { context in
                context.model.sessions.agentFilter = "codex"
            }),
            PreviewScenario(name: "sessions-close", route: .sessions, stage: "sessions.close")
        ]
    }

    /// Fold or open groups the way a reader would, on the groups the gateway lists.
    @MainActor
    private static func fold(_ context: PreviewContext, _ change: ([DeviceGroup]) -> Void) {
        let model = context.model
        change(model.sessions.groups(model.connection.sessions, devices: model.connection.devices))
    }

    private static var drawer: [PreviewScenario] {
        [
            PreviewScenario(name: "new-session", route: .sessions, stage: "sessions.new", settle: .milliseconds(1500)),
            PreviewScenario(name: "new-session-600", route: .sessions, width: 600, height: 860,
                            stage: "sessions.new", settle: .milliseconds(1500)),
            PreviewScenario(name: "new-session-zh", route: .sessions, stage: "sessions.new", language: .zhHans,
                            settle: .milliseconds(1500)),
            PreviewScenario(name: "new-session-device", route: .sessions, stage: "sessions.new.device",
                            settle: .milliseconds(1500)),
            PreviewScenario(name: "new-session-browse", route: .sessions, stage: "sessions.new.browse",
                            settle: .milliseconds(2000)),
            PreviewScenario(name: "new-session-folder", route: .sessions, stage: "sessions.new.folder",
                            settle: .milliseconds(2000)),
            PreviewScenario(name: "new-session-folder-clash", route: .sessions, stage: "sessions.new.folder.clash",
                            settle: .milliseconds(2400))
        ]
    }

    /// The sidebar alone, as the conversation page places it, on the session
    /// the render finds waiting for an approval.
    private static var sidebar: [PreviewScenario] {
        [
            sidebarScenario("sidebar"),
            sidebarScenario("sidebar-zh", language: .zhHans)
        ]
    }

    private static func sidebarScenario(_ name: String, language: InterfaceLanguage? = nil) -> PreviewScenario {
        PreviewScenario(name: name, route: .sessions, language: language, content: { context in
            let sessions = context.model.connection.sessions
            let open = sessions.first { $0.state == .needsApproval } ?? sessions.first
            return AnyView(HStack(spacing: 0) {
                SessionSidebar(deviceId: open?.deviceID ?? "", sessionId: open?.sessionID ?? "")
                Palette.surface
            })
        })
    }
}

/// A flag a frame handler raises for a scenario's preparation to wait on.
@MainActor
private final class StepWatch {
    var done = false
}
