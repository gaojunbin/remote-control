import RCCore
import SwiftUI

/// `.device-glyph`: one computer glyph per device, whatever its platform — a
/// minimal outline laptop in the ink, sized to the title (`docs/DESIGN.md`
/// § "The device row"). It is what keeps two rows apart now that nothing is
/// drawn between them.
struct DeviceGlyph: View {
    /// `--device-glyph`, which the agents on a narrow screen indent past.
    static let side: CGFloat = 20

    var body: some View {
        Icon(.laptopMinimal, size: Self.side, strokeWidth: 1.5)
            .foregroundStyle(Palette.ink)
    }
}

/// `.device-main`: the name once, the status line, and the third line only
/// while an update runs or has failed (A36) — or, for four seconds, why a tap
/// opened nothing (A38).
struct DeviceRowMain: View {
    let device: Device
    let sessionCount: Int
    let notice: (text: String, failed: Bool)?
    let note: String?
    let wide: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(device.name)
                .lineLimit(1)
                .truncationMode(.tail)
                .css(FontSize.fs15, weight: .semibold, lineHeight: 1.4, tracking: -0.01)
            DeviceMetaLine(device: device, sessionCount: sessionCount, wide: wide)
                .padding(.top, 1)
            if let notice {
                line(notice.text, ink: notice.failed ? Palette.danger : Palette.inkSecondary)
            }
            if let note {
                line(note, ink: Palette.attention)
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func line(_ text: String, ink: Color) -> some View {
        Text(text)
            .css(FontSize.fs12, lineHeight: 1.45)
            .foregroundStyle(ink)
            .padding(.top, 1)
    }
}

/// `.device-meta`: the online dot with its word and the platform as a word,
/// then the session count and the latency or last seen. The two halves share a
/// line where they fit and part where they do not; the dot between them is
/// drawn on a wide screen only.
struct DeviceMetaLine: View {
    let device: Device
    let sessionCount: Int
    let wide: Bool

    var body: some View {
        WrapRow(spacing: Space.sp2, lineSpacing: 2) {
            HStack(spacing: Space.sp2) {
                OnlineDot(online: device.online, pulses: device.updateState == .updating)
                Text("\(device.online ? S.devices.online : S.devices.offline) · \(S.platformLabel(device.platform.rawValue))")
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .css(FontSize.fs12, lineHeight: 1.45)
                    .foregroundStyle(Palette.inkSecondary)
            }
            HStack(spacing: Space.sp2) {
                if wide {
                    Text("·").css(FontSize.fs12, lineHeight: 1.45).foregroundStyle(Palette.lineStrong)
                }
                Text(reach)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .css(FontSize.fs12, lineHeight: 1.45)
                    .foregroundStyle(Palette.inkTertiary)
            }
        }
    }

    private var reach: String {
        let count = sessionCount > 0 ? S.devices.sessionsCount(sessionCount) : S.devices.noSessions
        let seen = device.online
            ? Format.latency(device.latencyMS.map(Double.init))
            : S.devices.lastSeen(Format.relativeTime(device.lastSeen))
        return "\(count) · \(seen)"
    }
}

/// `.device-agents`: what the device found, as logos alone, evenly spaced and
/// with no name or version beside them; each names its agent for a reader and a
/// hover. The logo is one em of the strip's 16 points.
struct DeviceAgentStrip: View {
    let agents: [AgentInfo]

    var body: some View {
        Group {
            if agents.isEmpty {
                Hint(S.devices.noAgents)
            } else {
                HStack(spacing: Space.sp3) {
                    ForEach(agents) { agent in
                        AgentLogo(agent: agent.agent, size: FontSize.fs16)
                            .help(S.agentLabel(agent.agent))
                            .accessibilityElement()
                            .accessibilityLabel(S.agentLabel(agent.agent))
                            .accessibilityAddTraits(.isImage)
                    }
                }
            }
        }
        .foregroundStyle(Palette.inkSecondary)
    }
}
