import RCCore
import SwiftUI

/// `AgentCard.tsx`: one agent on a device page — its logo, its name and
/// version, how it is signed in, and the quota windows of every account.
///
/// Nothing is drawn for what the agent does not report. An agent whose
/// `accounts` is absent came from a device that never looked, and says nothing
/// at all; an empty list is an agent installed and signed in nowhere. A key has
/// no meter, because a key has no plan window to measure.
struct AgentCard: View {
    let agent: AgentInfo
    /// The accounts to draw: the fresh reply's when it has arrived, else the stored ones.
    let accounts: [AgentAccount]?
    let quota: DeviceQuota
    @Environment(\.layoutClass) private var layout

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Space.sp2) {
                AgentLogo(agent: agent.agent, size: FontSize.fs15)
                Text(S.agentLabel(agent.agent))
                    .css(FontSize.fs15, weight: .semibold, tracking: -0.01)
                if let version = agent.version, !version.isEmpty {
                    Text(version)
                        .css(FontSize.fs12, mono: true)
                        .foregroundStyle(Palette.inkTertiary)
                }
            }
            if let accounts {
                if accounts.isEmpty {
                    AccountNote(S.devicePage.notSignedIn, ink: Palette.inkSecondary)
                } else {
                    VStack(alignment: .leading, spacing: Space.sp4) {
                        ForEach(Array(accounts.enumerated()), id: \.offset) { index, account in
                            AccountBlock(account: account, quota: quota, parted: index > 0)
                        }
                    }
                }
            }
        }
        .padding(.vertical, Space.sp4)
        .padding(.horizontal, layout.maxWidth480 ? Space.sp4 : Space.sp5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }
}

/// One account: its sign-in line, then the meters or the one line that stands
/// where they would have been. pi signs in per provider, so an agent can carry
/// more than one, parted by a hairline.
private struct AccountBlock: View {
    let account: AgentAccount
    let quota: DeviceQuota
    let parted: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if parted {
                Rectangle().fill(Palette.hairline).frame(height: 1).padding(.bottom, Space.sp4)
            }
            AccountNote(AccountWords.signInLine(account), ink: Palette.inkSecondary)
            quotaLine
        }
    }

    @ViewBuilder private var quotaLine: some View {
        if account.method == .apiKey {
            EmptyView()
        } else if quota.status == .checking {
            AccountNote(S.devicePage.checking, ink: Palette.inkTertiary)
        } else if quota.status == .offline {
            AccountNote(S.devicePage.offlineQuota, ink: Palette.inkTertiary)
        } else if quota.status == .failed {
            AccountNote(quota.error ?? "", ink: Palette.inkTertiary)
        } else if let failure = account.limitsError, !failure.isEmpty {
            AccountNote(failure, ink: Palette.inkTertiary)
        } else if let limits = account.limits, !limits.isEmpty {
            VStack(alignment: .leading, spacing: Space.sp3) {
                ForEach(Array(limits.enumerated()), id: \.offset) { _, limit in QuotaMeter(limit: limit) }
            }
            .frame(maxWidth: 520, alignment: .leading)
            .padding(.top, Space.sp3)
        }
    }
}

/// `.device-page-signin` and `.device-page-note`: 13 points on 1.5 lines, 8
/// under what comes before, breaking long words rather than overflowing.
private struct AccountNote: View {
    let text: String
    let ink: Color

    init(_ text: String, ink: Color) {
        self.text = text
        self.ink = ink
    }

    var body: some View {
        Text(text)
            .css(FontSize.fs13, lineHeight: 1.5)
            .foregroundStyle(ink)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, Space.sp2)
    }
}
