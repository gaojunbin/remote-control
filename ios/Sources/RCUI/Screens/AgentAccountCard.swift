import SwiftUI
import RCCore

/// Amendment A33: how far the page has got with the vendors' rate limits.
///
/// The credentials themselves come from the device list and are on screen at
/// once; the windows are a question asked the moment the page opens, and this
/// is the answer's progress.
enum QuotaPhase: Hashable {
    /// The reply to `device.agents` has not arrived yet.
    case checking
    /// It arrived: each account carries its own windows, its own reason, or
    /// neither.
    case ready
    /// The machine is not there to ask.
    case offline
    /// Nothing can be said about the windows, and the page says why elsewhere.
    case unavailable
}

/// One coding agent on a device's page: the logo and the name with its version,
/// how it is signed in, and what is left of each account's quota.
///
/// `docs/DESIGN.md` § "A device has a page" and § "Quota is a meter, drawn for
/// accounts only". Nothing is drawn for what the device did not report: an
/// agent whose `accounts` is absent says nothing about signing in, and an
/// account whose vendor exposes no windows shows its line alone.
struct AgentAccountCard: View {
    let info: AgentInfo
    /// The credentials to draw: the fresh ones where a reply has brought them,
    /// the stored ones until then, nil where the device did not look.
    let accounts: [AgentAccount]?
    let phase: QuotaPhase

    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            HStack(spacing: Theme.Space.tight) {
                AgentLogo(agent: info.agent)
                Text(info.displayName)
                    .font(Theme.Text.title)
                    .foregroundStyle(Theme.ink)
                    // On the name rather than on the card: an identifier on a
                    // container reaches every text inside it, and the lines
                    // under this one have identifiers of their own.
                    .accessibilityIdentifier("device.agent.\(info.agent)")
                Spacer(minLength: 0)
                if let version = info.version, !version.isEmpty {
                    CodeText(version, font: Theme.Text.metaMono)
                }
            }
            if let accounts {
                if accounts.isEmpty {
                    Text(AccountLine.notSignedIn)
                        .font(Theme.Text.meta)
                        .foregroundStyle(Theme.inkSecondary)
                        .accessibilityIdentifier("device.agent.\(info.agent).signIn")
                } else {
                    // pi signs in per provider, so a card can carry more than
                    // one line; two credentials can otherwise look alike, so
                    // the position is what tells them apart.
                    ForEach(Array(accounts.enumerated()), id: \.offset) { _, account in
                        credential(account)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func credential(_ account: AgentAccount) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            Text(AccountLine.text(for: account))
                .font(Theme.Text.meta)
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("device.agent.\(info.agent).signIn")
            quota(for: account)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// A key has no plan window to measure, so it never has a meter — not even
    /// a waiting one.
    @ViewBuilder
    private func quota(for account: AgentAccount) -> some View {
        if account.method != .apiKey {
            switch phase {
            case .checking:
                note(L10n.string("Checking…")).accessibilityIdentifier("device.quota.checking")
            case .offline:
                note(L10n.string("Offline · quota unavailable"))
                    .accessibilityIdentifier("device.quota.offline")
            case .unavailable:
                EmptyView()
            case .ready:
                if let reason = account.limitsError, !reason.isEmpty {
                    note(reason).accessibilityIdentifier("device.quota.error")
                } else if let limits = account.limits, !limits.isEmpty {
                    VStack(alignment: .leading, spacing: Theme.Space.small) {
                        ForEach(Array(limits.enumerated()), id: \.offset) { _, limit in
                            QuotaMeterRow(limit: limit, locale: locale)
                        }
                    }
                    .accessibilityIdentifier("device.quota.meters")
                }
            }
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(Theme.Text.caption)
            .foregroundStyle(Theme.inkSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// One rate-limit window: its name, the share used, and when it comes back.
struct QuotaMeterRow: View {
    let limit: AgentLimit
    let locale: Locale

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.tight) {
                Text(QuotaWindow.name(limit))
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.ink)
                Spacer(minLength: 0)
                Text(QuotaWindow.percentage(limit.usedPercent))
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
                    .monospacedDigit()
            }
            QuotaMeter(limit: limit)
            if let resetsAt = limit.resetsAt {
                Text(QuotaWindow.resets(at: resetsAt, locale: locale))
                    .font(Theme.Text.caption)
                    .foregroundStyle(Theme.inkSecondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The bar itself: the ink colour until the window is nearly spent, the warning
/// colour past 80 % and the danger colour at 100 %. No other colour appears on
/// the page.
struct QuotaMeter: View {
    let limit: AgentLimit

    private let height: CGFloat = 5

    var body: some View {
        GeometryReader { geometry in
            let fill = QuotaWindow.fill(usedPercent: limit.usedPercent)
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.quietFill)
                // A window barely touched is still a mark rather than nothing:
                // a bar that draws no fill at all reads as a missing meter.
                Capsule()
                    .fill(colour)
                    .frame(width: fill > 0 ? max(height, geometry.size.width * fill) : 0)
            }
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }

    private var colour: Color {
        switch QuotaWindow.band(usedPercent: limit.usedPercent) {
        case .normal: Theme.ink
        case .warning: Theme.attention
        case .danger: Theme.danger
        }
    }
}
