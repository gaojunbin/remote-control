import SwiftUI
import RCCore

/// The dot, the word and the machine. It is the second line of a device's row
/// and the first line of its page, written once so the two never drift.
struct DeviceStatusLine: View {
    let device: Device

    var body: some View {
        HStack(spacing: 5) {
            StatusDot(tone: tone)
            Text(device.online ? "online" : "offline")
                .font(Theme.Text.meta)
                .foregroundStyle(Theme.inkSecondary)
            Text("·").font(Theme.Text.caption).foregroundStyle(Theme.inkSecondary)
            CodeText("\(device.hostname) · \(device.platform.rawValue) \(device.arch)",
                     font: Theme.Text.metaMono)
            Spacer(minLength: 0)
        }
    }

    private var tone: DotTone {
        if device.updateState == .updating { return .working }
        return device.online ? .live : .off
    }
}

/// Amendment A22: the build this machine runs, and the one line that replaces
/// it whenever there is something to say about an update.
struct DeviceClientLine: View {
    let device: Device
    var servedBuild: String?
    var localError: String?

    var body: some View {
        HStack(spacing: 5) {
            CodeText(clientText, font: Theme.Text.metaMono)
            if let notice {
                Text("·").font(Theme.Text.caption).foregroundStyle(Theme.inkSecondary)
                Text(Self.text(of: notice))
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

    private static func text(of notice: DeviceUpdate.Notice) -> String {
        switch notice {
        case .available: L10n.string("Update available")
        case .updating: L10n.string("Updating…")
        case .failed(let message): L10n.string("Update failed · %@", message)
        }
    }
}

extension DeviceUpdate.Notice {
    var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }
}
