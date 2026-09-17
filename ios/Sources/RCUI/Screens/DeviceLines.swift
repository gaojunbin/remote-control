import SwiftUI
import RCCore

/// The dot, the state and the platform. It is the second line of a device's row
/// and the first line of its page, written once so the two never drift.
struct DeviceStatusLine: View {
    let device: Device

    var body: some View {
        HStack(spacing: 5) {
            OnlineDot(online: device.online, updating: device.updateState == .updating)
            Text(DeviceLine.status(device))
                .font(Theme.Text.meta)
                .foregroundStyle(Theme.inkSecondary)
            Spacer(minLength: 0)
        }
    }
}

/// `docs/DESIGN.md` § "The device row": the hostname and the architecture are
/// facts someone opens a machine's page to check, so they are drawn there and
/// on no row.
struct DeviceFactsLine: View {
    let device: Device

    var body: some View {
        HStack(spacing: 5) {
            CodeText(DeviceLine.facts(device), font: Theme.Text.metaMono)
            Spacer(minLength: 0)
        }
    }
}

/// `docs/DESIGN.md` § "A device keeps itself current": the one thing an app
/// says about a machine's client, on its row and on its page alike. A device
/// the gateway is keeping current says nothing, so this line is drawn only
/// where there is a notice to draw (A36).
struct DeviceUpdateLine: View {
    let notice: DeviceUpdate.Notice

    var body: some View {
        Text(DeviceUpdateText.line(notice))
            .font(Theme.Text.caption)
            .foregroundStyle(notice.isFailure ? Theme.danger : Theme.inkSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("device.updateNotice")
    }
}

/// `docs/DESIGN.md` § "An update names its version": what a device says about
/// an update, written once so the row, the page and the confirmation can never
/// tell three different stories about the same wheel.
public enum DeviceUpdateText {
    /// The notice itself. There is no wording for a current device: the gateway
    /// keeps it current and the app says nothing (A36).
    public static func line(_ notice: DeviceUpdate.Notice) -> String {
        switch notice {
        case .updating: return L10n.string("Updating…")
        case .failed(let message): return L10n.string("Update failed · %@", message)
        }
    }

    /// Why Retry update cannot act, said the same way wherever the action is
    /// drawn disabled.
    public static func reason(_ block: DeviceUpdate.Block) -> String {
        switch block {
        case .offline: L10n.string("This device is offline.")
        case .noServedBuild: L10n.string("This gateway is not serving a client build.")
        }
    }

    /// The confirmation, which names the machine and what it would land on.
    public static func confirmation(name: String, servedVersion: String?) -> String {
        guard let servedVersion else {
            return L10n.string(
                "Update %@ to the gateway's client? Its service restarts; sessions it drives are stopped.",
                name)
        }
        return L10n.string(
            "Update %@ to %@? Its service restarts; sessions it drives are stopped.",
            name, servedVersion)
    }
}

extension DeviceUpdate.Notice {
    var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }
}
