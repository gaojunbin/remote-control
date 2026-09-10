import SwiftUI
import RCCore

/// One timeline row. Sub-agent output is drawn indented under its parent tool
/// call rather than interleaved with the main answer.
struct TimelineRow: View {
    let entry: TimelineEntry
    let chat: ChatStore
    var nested = false

    var body: some View {
        switch entry.body {
        case .userMessage(let payload):
            UserMessageRow(payload: payload)
        case .assistantText:
            MarkdownText(entry.text)
                .padding(.vertical, 2)
        case .thinking(let payload):
            ThinkingRow(text: entry.text, durationMS: payload.durationMS, done: payload.done)
        case .toolCall(let payload):
            ToolCallRow(entry: entry, payload: payload, chat: chat, nested: nested)
        case .approval(let payload):
            ApprovalCard(entry: entry, payload: payload, chat: chat)
        case .question(let payload):
            QuestionCard(payload: payload, chat: chat)
        case .notice(let payload):
            NoticeRow(payload: payload)
        case .error(let payload):
            ErrorRow(payload: payload)
        case .turnCompleted(let payload):
            TurnFooter(payload: payload)
        case .turnStarted, .todos, .status, .meta, .queue:
            EmptyView()
        case .unknown(let kind, _):
            Text("This device sent a \(kind) block that this app version cannot show yet.")
                .font(.footnote)
                .foregroundStyle(Theme.inkSecondary)
        }
    }
}

private struct UserMessageRow: View {
    let payload: UserMessagePayload

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.tight) {
            Text(payload.text)
                .font(.body)
                .foregroundStyle(Theme.ink)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
            if !payload.attachments.isEmpty {
                HStack(spacing: Theme.Space.tight) {
                    Image(systemName: "paperclip").font(.caption)
                    Text(payload.attachments.map(\.name).joined(separator: ", "))
                        .font(.caption)
                        .lineLimit(1)
                }
                .foregroundStyle(Theme.inkSecondary)
            }
            if payload.delivery == .pending {
                DeliveryChip(label: Text("waiting for the terminal"))
            } else if payload.delivery == .absorbed {
                DeliveryChip(label: Text("will be re-sent"))
            } else if payload.source == .terminal {
                Text("sent from the terminal").font(.caption).foregroundStyle(Theme.inkSecondary)
            } else if payload.source == .queue {
                Text("sent from the queue").font(.caption).foregroundStyle(Theme.inkSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Theme.Space.small + 2)
        .background(Theme.surfaceSunken,
                    in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(spokenLabel)
        .accessibilityIdentifier(identifier)
    }

    /// The chip is inside a combined element, so its words have to reach
    /// VoiceOver through the bubble's own label.
    private var spokenLabel: Text {
        let said = Text("You said: \(payload.text)")
        switch payload.delivery {
        case .some(.pending): return said + Text(", waiting for the terminal")
        case .some(.absorbed): return said + Text(", will be re-sent")
        default: return said
        }
    }

    /// Amendment A10: the row names its delivery state, so a test and VoiceOver
    /// reach the chip even though the bubble is one combined element.
    private var identifier: String {
        guard let delivery = payload.delivery else { return "chat.message" }
        return "chat.message.\(delivery.rawValue)"
    }
}

/// Amendment A10: the small grey pill under a message the device is still
/// holding for the terminal, or has to inject again.
private struct DeliveryChip: View {
    let label: Text

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "clock").font(.caption2)
            label.font(.caption)
        }
        .foregroundStyle(Theme.inkSecondary)
        .padding(.horizontal, Theme.Space.tight)
        .padding(.vertical, 2)
        .background(Theme.surface, in: Capsule())
    }
}

/// "Thought for 12s", collapsed until asked for.
private struct ThinkingRow: View {
    let text: String
    let durationMS: Int?
    let done: Bool
    @State private var expanded = false

    private var title: String {
        guard done else { return "Thinking" }
        guard let durationMS, durationMS > 0 else { return "Thought about it" }
        return "Thought for \(RelativeTime.duration(milliseconds: durationMS))"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.tight) {
            Button {
                expanded.toggle()
            } label: {
                HStack(spacing: Theme.Space.tight) {
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.caption2)
                    Text(title).font(.footnote)
                    Spacer()
                }
                .foregroundStyle(Theme.inkSecondary)
                .frame(minHeight: Theme.Touch.minimum)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("chat.thinking")
            if expanded, !text.isEmpty {
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(Theme.inkSecondary)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, Theme.Space.medium)
            }
        }
    }
}

/// A single line that expands to the tool's input and output.
private struct ToolCallRow: View {
    let entry: TimelineEntry
    let payload: ToolCallPayload
    let chat: ChatStore
    var nested = false

    private var expanded: Bool { chat.isExpanded(entry.id) }
    private var children: [TimelineEntry] { chat.timeline.children(of: entry.id) }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.tight) {
            Button {
                chat.toggleExpanded(entry.id)
            } label: {
                HStack(spacing: Theme.Space.tight) {
                    Image(systemName: expanded ? "chevron.down" : "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(Theme.inkSecondary)
                    Text(payload.tool)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Theme.ink)
                    Text(payload.title)
                        .font(Theme.mono)
                        .foregroundStyle(Theme.inkSecondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer(minLength: Theme.Space.tight)
                    trailing
                }
                .frame(minHeight: Theme.Touch.minimum)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("chat.tool.\(entry.id)")
            .accessibilityLabel("\(payload.tool), \(payload.title), \(payload.status.rawValue)")

            if let diff = payload.diff {
                DiffSummary(diff: diff, expanded: expanded)
            }
            if expanded { detail }
            if !children.isEmpty { subagent }
            if payload.status == .running, let output = payload.output, !output.isEmpty, !expanded {
                OutputBlock(text: output, foldedLineLimit: 6)
            }
        }
        .padding(.leading, nested ? Theme.Space.medium : 0)
        .overlay(alignment: .leading) {
            if nested {
                Rectangle().fill(Theme.border).frame(width: 1).padding(.vertical, 2)
            }
        }
    }

    @ViewBuilder
    private var trailing: some View {
        if let summary = payload.summary, !summary.isEmpty {
            Text(summary)
                .font(.caption)
                .foregroundStyle(payload.status == .failed ? Theme.danger : Theme.inkSecondary)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(payload.status == .failed ? Theme.danger.opacity(0.1) : Theme.surfaceSunken,
                            in: Capsule())
        }
        if payload.status == .running {
            ProgressView().controlSize(.mini)
        } else if let duration = payload.durationMS {
            Text(RelativeTime.duration(milliseconds: duration))
                .font(.caption)
                .foregroundStyle(Theme.inkSecondary)
        }
    }

    @ViewBuilder
    private var detail: some View {
        VStack(alignment: .leading, spacing: Theme.Space.tight) {
            if let input = payload.input, let text = Self.render(input) {
                Text("Input").font(.caption2.weight(.semibold)).foregroundStyle(Theme.inkSecondary)
                OutputBlock(text: text, foldedLineLimit: 12, isTruncated: payload.inputTruncated) {
                    Task { await chat.loadFullBlock(entry.id) }
                }
            }
            if let output = payload.output, !output.isEmpty {
                Text("Output").font(.caption2.weight(.semibold)).foregroundStyle(Theme.inkSecondary)
                OutputBlock(text: output, isTruncated: payload.outputTruncated) {
                    Task { await chat.loadFullBlock(entry.id) }
                }
            }
            if let patch = payload.diff?.patch {
                PatchView(patch: patch)
            }
        }
        .padding(.leading, Theme.Space.medium)
    }

    private var subagent: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            ForEach(children) { child in
                TimelineRow(entry: child, chat: chat, nested: true)
            }
        }
        .padding(.leading, Theme.Space.medium)
    }

    private static func render(_ value: JSONValue) -> String? {
        if let text = value.stringValue { return text }
        guard let data = try? JSONEncoder().encode(value) else { return nil }
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: object,
                                                       options: [.prettyPrinted, .sortedKeys]) else {
            return String(decoding: data, as: UTF8.self)
        }
        return String(decoding: pretty, as: UTF8.self)
    }
}

private struct DiffSummary: View {
    let diff: DiffPayload
    let expanded: Bool

    var body: some View {
        HStack(spacing: Theme.Space.tight) {
            CodeText(diff.path, color: Theme.ink)
            Text("+\(diff.additions)").font(.caption).foregroundStyle(Theme.added)
            Text("−\(diff.deletions)").font(.caption).foregroundStyle(Theme.removed)
            Spacer()
        }
        .padding(.leading, Theme.Space.medium)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(diff.path), \(diff.additions) added, \(diff.deletions) removed")
    }
}

private struct NoticeRow: View {
    let payload: NoticePayload

    private var tint: Color {
        switch payload.level {
        case .error: Theme.danger
        case .warn: Theme.attention
        default: Theme.inkSecondary
        }
    }

    var body: some View {
        HStack(alignment: .top, spacing: Theme.Space.tight) {
            Circle().fill(tint).frame(width: 5, height: 5).padding(.top, 6)
            Text(payload.text)
                .font(.footnote)
                .foregroundStyle(Theme.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct ErrorRow: View {
    let payload: ErrorPayload

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(payload.message)
                .font(.footnote)
                .foregroundStyle(Theme.danger)
                .fixedSize(horizontal: false, vertical: true)
            if let code = payload.code {
                Text(code).font(.caption2).foregroundStyle(Theme.inkSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Theme.Space.small)
        .background(Theme.danger.opacity(0.08),
                    in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

private struct TurnFooter: View {
    let payload: TurnCompletedPayload

    private var text: String {
        let duration = RelativeTime.duration(milliseconds: payload.durationMS)
        return switch payload.stopReason {
        case .interrupted: "Stopped after \(duration)"
        case .error: "Ended with an error after \(duration)"
        default: "Finished in \(duration)"
        }
    }

    var body: some View {
        HStack(spacing: Theme.Space.tight) {
            Rectangle().fill(Theme.border).frame(height: 0.5)
            Text(text).font(.caption).foregroundStyle(Theme.inkSecondary).layoutPriority(1)
            Rectangle().fill(Theme.border).frame(height: 0.5)
        }
        .padding(.vertical, Theme.Space.tight)
    }
}
