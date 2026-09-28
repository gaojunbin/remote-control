import RCCore
import SwiftUI

/// One device's group on the Sessions page: its header with the online dot and
/// the disclosure chevron, then its active rows on one surface, then its own
/// Archive, captioned "Archive · N" and folded shut until the reader opens it.
/// The choices are kept per device id in the store both lists read.
struct SessionGroupSection: View {
    let group: DeviceGroup
    /// A preview stage's: the close question drawn open on this row.
    var asksToClose: String?
    @Environment(MacAppModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // A header's margin under it collapses into the gap between groups
            // when nothing follows it, so it is only kept above rows.
            DeviceGroupHeader(name: group.name, online: group.online, expanded: !group.collapsed) {
                model.sessions.toggleCollapsed(group.id)
            }
            .padding(.bottom, group.collapsed ? 0 : Space.sp2)
            if !group.collapsed {
                if !group.active.isEmpty { rows(group.active) }
                if !group.archive.isEmpty { archive }
            }
        }
    }

    /// The Archive's 12 above it collapses into the header's 8 when no active
    /// row stands between them.
    private var archive: some View {
        VStack(alignment: .leading, spacing: 0) {
            ArchiveGroupHeader(count: group.archive.count, expanded: group.archiveExpanded) {
                model.sessions.toggleArchive(group.id)
            }
            .padding(.bottom, group.archiveExpanded ? Space.sp2 : 0)
            if group.archiveExpanded { rows(group.archive) }
        }
        .padding(.top, group.active.isEmpty ? Space.sp1 : Space.sp3)
    }

    private func rows(_ sessions: [Session]) -> some View {
        VStack(spacing: 0) {
            ForEach(sessions) { session in
                SessionRow(session: session, online: group.online, asksToClose: asksToClose == session.id)
            }
        }
        .surface()
    }
}

/// `.card.empty` on the Sessions page: the first line in the ink, and the hint
/// under it only when there is no search to blame.
struct SessionsEmptyCard: View {
    let title: String
    let hint: String?

    var body: some View {
        VStack(spacing: 0) {
            Text(title)
                .css(FontSize.fs14, weight: .medium)
                .foregroundStyle(Palette.ink)
                .padding(.bottom, Space.sp1)
            if let hint {
                Text(hint)
                    .css(FontSize.fs14)
                    .foregroundStyle(Palette.inkSecondary)
            }
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, Space.sp10)
        .padding(.horizontal, Space.sp4)
        .card()
    }
}
