import RCCore
import SwiftUI

/// `web/src/features/devices/DeviceRow.tsx`: one registered device, as
/// `docs/DESIGN.md` § "The device row" rules it — a computer glyph at the
/// leading edge, the name once, a status line, the agents as logos alone, and a
/// third line only while an update runs or has failed (A36).
///
/// A38, rule 20: the row's own click opens a terminal on the machine. A device
/// that is offline or offers no shell says which of the two it is under its
/// name and goes nowhere. Everything else is the menu's, which sits above the
/// row's target so none of its items navigates.
struct DeviceRow: View {
    let device: Device
    let sessionCount: Int
    /// A22: the build a retry would ask for, when the gateway serves one.
    let servedBuild: String?
    /// A22: why this device's last `device.update` was refused outright.
    let updateError: String?
    /// A preview stage's: the menu drawn open, or the refusal a click gets.
    let menuOpen: Bool
    let refusesOnAppear: Bool
    let onRename: () -> Void
    let onRetryUpdate: () -> Void
    let onRevoke: () -> Void

    @Environment(MacAppModel.self) private var model
    @Environment(\.layoutClass) private var layout
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false
    @State private var note: String?
    @State private var noteTimer: Task<Void, Never>?

    /// How long the row keeps saying why it opened nothing.
    private static let noteDuration: Duration = .seconds(4)

    var body: some View {
        let compact = layout.maxWidth760
        Group { if compact { stacked } else { wide } }
            .frame(maxWidth: .infinity)
            .frame(minHeight: compact ? (layout.maxWidth480 ? RowHeight.rowHStackedTall : RowHeight.rowHStacked)
                                      : RowHeight.rowH)
            // The target under everything but the menu, as the web stretches
            // the name's link over the row and lifts the menu above it.
            .background {
                Button(action: open) { Color.clear.contentShape(Rectangle()) }
                    .buttonStyle(.plain)
                    .pointerStyle(.link)
                    .accessibilityLabel(device.name)
            }
            // The tint eases in; the menu's dots light at once, as the web's do.
            .background {
                Rectangle()
                    .fill(isHovered ? Palette.hover : Color.clear)
                    .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: isHovered)
            }
            .onHover { isHovered = $0 }
            .environment(\.rowIsHovered, isHovered)
            .onAppear { if refusesOnAppear { open() } }
            .onDisappear { noteTimer?.cancel() }
    }

    /// Glyph, the machine, its agents and the menu on one line.
    private var wide: some View {
        HStack(spacing: Space.sp4) {
            DeviceGlyph().allowsHitTesting(false)
            main(wide: true).allowsHitTesting(false)
            DeviceAgentStrip(agents: device.availableAgents).allowsHitTesting(false)
            menu
        }
        .padding(.horizontal, Space.sp5)
    }

    /// 760 and narrower: the agents take a line of their own, indented past
    /// the glyph so they line up with the name.
    private var stacked: some View {
        VStack(alignment: .leading, spacing: Space.sp2) {
            HStack(spacing: Space.sp3) {
                DeviceGlyph().allowsHitTesting(false)
                main(wide: false).allowsHitTesting(false)
                // `margin-top: -2px` on a centred item lifts it by one point.
                menu.offset(y: -1)
            }
            DeviceAgentStrip(agents: device.availableAgents)
                .padding(.leading, DeviceGlyph.side + Space.sp3)
                .allowsHitTesting(false)
        }
        .padding(.vertical, Space.sp3)
        .padding(.horizontal, Space.sp4)
    }

    private func main(wide: Bool) -> some View {
        DeviceRowMain(device: device, sessionCount: sessionCount,
                      notice: DeviceUpdateWords.notice(for: device, localError: updateError),
                      note: note, wide: wide)
    }

    private var menu: some View {
        DeviceRowMenu(device: device,
                      retryBlocked: DeviceUpdateWords.retryBlocked(device, servedBuild: servedBuild),
                      failed: DeviceUpdateWords.notice(for: device, localError: updateError)?.failed == true,
                      initiallyOpen: menuOpen,
                      onRename: onRename, onRetryUpdate: onRetryUpdate,
                      onShowQuota: { model.router.go(.device(id: device.deviceID)) },
                      onRevoke: onRevoke)
    }

    /// A38: the click opens a shell, or says why it cannot. `terminal` is absent
    /// on a client older than the amendment, which is the same answer as false.
    private func open() {
        if device.online && device.offersTerminal {
            model.router.go(.terminal(deviceId: device.deviceID))
            return
        }
        note = device.online ? S.devices.noTerminal : S.devices.deviceOffline
        noteTimer?.cancel()
        noteTimer = Task {
            try? await Task.sleep(for: Self.noteDuration)
            guard !Task.isCancelled else { return }
            note = nil
        }
    }
}
