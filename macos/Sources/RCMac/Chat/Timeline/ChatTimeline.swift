import RCCore
import SwiftUI

/// `web/src/features/chat/Timeline.tsx`: the transcript, 760 points at most and
/// centred, its rows 16 apart, the status line as its last row, and the way
/// back down whenever the reader is away from the tail (`docs/DESIGN.md` §
/// "Reading position"). Rows are laid out lazily, so a long day of work costs
/// what is on screen.
struct ChatTimeline: View {
    let chat: ChatStore
    let statusLine: StatusLineModel?
    let onTakeover: () -> Void
    let handlers: TranscriptHandlers
    @Environment(MacAppModel.self) private var model
    @Environment(\.layoutClass) private var layout
    @State private var scroll = TranscriptScroll()
    @State private var position = ScrollPosition(edge: .bottom)
    @State private var selections = TranscriptSelectionCache()
    @Environment(\.previewStage) private var stage

    var body: some View {
        let detail = model.settings.timelineDetail
        let selection = selections.selection(chat.timeline, detail: detail)
        let rows = selection.roots.filter(TranscriptRow.draws)
        ScrollViewReader { proxy in
            ZStack(alignment: .bottom) {
                ScrollView {
                    VStack(spacing: 0) {
                        LazyVStack(alignment: .leading, spacing: Space.sp4) {
                            TranscriptNotes(loading: chat.isLoadingHistory, hasMore: chat.timeline.hasMoreHistory,
                                            rowCount: selection.rowCount)
                            ForEach(Array(rows.enumerated()), id: \.element.id) { index, item in
                                TranscriptRow(item: item, children: selection.children[item.id] ?? [],
                                              followsTool: index > 0 && TranscriptRow.isTool(rows[index - 1]),
                                              chat: chat, handlers: handlers)
                            }
                            if let statusLine { StatusLineView(line: statusLine, onTakeover: onTakeover) }
                        }
                        .padding(.top, layout.maxWidth1023 ? Space.sp4 : Space.sp6)
                        .padding(.horizontal, layout.maxWidth1023 ? Space.sp4 : Space.sp5)
                        // The tail marker below takes the last point of the padding.
                        .padding(.bottom, (layout.maxWidth1023 ? Space.sp3 : Space.sp4) - 1)
                        Color.clear.frame(height: 1).id(Self.tail)
                    }
                    .frame(maxWidth: 760)
                    .frame(maxWidth: .infinity)
                    .scrollThin()
                }
                .scrollPosition($position)
                .defaultScrollAnchor(.bottom, for: .initialOffset)
                .onScrollGeometryChange(for: TranscriptGeometry.self) { TranscriptGeometry($0) } action: { _, next in
                    apply(scroll.geometryChanged(to: next), proxy: proxy)
                }
                .onScrollPhaseChange { _, phase in scroll.phaseChanged(phase) }
                .onChange(of: rowsKey(selection, detail: detail), initial: true) { _, key in
                    apply(scroll.rowsChanged(key), proxy: proxy)
                }
                if !scroll.following {
                    BackToLatest(missed: scroll.missed) { apply(scroll.jump(), proxy: proxy) }
                        .padding(.bottom, Space.sp4)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task {
            // A render shows the way back down from the top of the transcript,
            // where a reader who scrolled up would see it — and the open tool
            // rows from there, where the first of them are.
            guard stage == "chat.jump" || stage == "chat.tools.open" else { return }
            try? await Task.sleep(for: .milliseconds(1200))
            position.scrollTo(y: 0)
        }
    }

    private static let tail = "chat.tail"

    /// What the follow rule reads of the rows. A pending send carries no
    /// `seq`, so it is counted into the revision as well.
    private func rowsKey(_ selection: TranscriptSelection, detail: TimelineDetail) -> ScrollFollow.Rows {
        ScrollFollow.Rows(revision: "\(selection.newestSeq).\(chat.timeline.optimistic.count)",
                          redraw: detail, firstKey: selection.firstKey, lastKey: selection.lastKey)
    }

    private func apply(_ actions: [TranscriptScroll.Action], proxy: ScrollViewProxy) {
        for action in actions {
            switch action {
            case .scrollToBottom(let animated):
                if animated {
                    withAnimation(.timingCurve(0.22, 0.61, 0.36, 1, duration: 0.3)) {
                        proxy.scrollTo(Self.tail, anchor: .bottom)
                    }
                } else {
                    proxy.scrollTo(Self.tail, anchor: .bottom)
                }
            case .scrollTo(let offset):
                position.scrollTo(y: offset)
            case .loadOlder:
                guard chat.timeline.hasMoreHistory, !chat.isLoadingHistory else { continue }
                Task { await chat.loadHistory() }
            }
        }
    }
}

/// `.timeline-note` and `.timeline-empty`: where the conversation starts, or
/// that there is nothing in it yet, in the tertiary ink.
private struct TranscriptNotes: View {
    let loading: Bool
    let hasMore: Bool
    let rowCount: Int

    var body: some View {
        if loading {
            note(S.chat.loadingHistory)
        } else if !hasMore && rowCount > 0 {
            note(S.chat.historyStart)
        }
        if rowCount == 0 && !loading {
            note(S.chat.emptyTimeline)
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .css(FontSize.fs12)
            .foregroundStyle(Palette.inkTertiary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }
}
