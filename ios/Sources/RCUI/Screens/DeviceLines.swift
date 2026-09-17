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

/// Amendment A22: the build this machine runs, and the one line that replaces
/// it whenever there is something to say about an update.
struct DeviceClientLine: View {
    let device: Device
    var servedBuild: String?
    var servedVersion: String?
    var localError: String?

    var body: some View {
        HStack(spacing: 5) {
            CodeText(clientText, font: Theme.Text.metaMono)
            if let notice {
                Text("·").font(Theme.Text.caption).foregroundStyle(Theme.inkSecondary)
                Text(DeviceUpdateText.notice(notice, servedVersion: servedVersion))
                    .font(Theme.Text.caption)
                    .foregroundStyle(notice.isFailure ? Theme.danger : Theme.inkSecondary)
                    .accessibilityIdentifier("device.updateNotice")
            }
            Spacer(minLength: 0)
        }
    }

    private var notice: DeviceUpdate.Notice? {
        DeviceUpdate.notice(for: device, servedBuild: servedBuild, localError: localError)
    }

    /// The build is worth showing only while nothing louder replaces it.
    private var clientText: String {
        guard notice == nil, let build = device.clientBuild else {
            return L10n.string("client %@", device.clientVersion)
        }
        return L10n.string("client %@ · %@", device.clientVersion, DeviceUpdate.shortBuild(build))
    }
}

/// `docs/DESIGN.md` § "An update names its version": what the row and the
/// confirmation say about an update, written once so the two can never name
/// different versions of the same wheel.
public enum DeviceUpdateText {
    /// The notice that replaces the build on the client line. A gateway that
    /// serves no version — an older one, or one running from a checkout — can
    /// only say that there is something newer.
    public static func notice(_ notice: DeviceUpdate.Notice, servedVersion: String?) -> String {
        switch notice {
        case .available:
            guard let servedVersion else { return L10n.string("Update available") }
            return L10n.string("Update available · %@", servedVersion)
        case .updating: return L10n.string("Updating…")
        case .failed(let message): return L10n.string("Update failed · %@", message)
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
