import SwiftUI
import RCCore

/// A session status dot. The colour is a shortcut; the label next to it always
/// says the same thing in words.
///
/// `DotTone` in `RCCore` decides what the dot looks like from the state, who
/// owns the session and whether the machine is reachable. Only a turn under way
/// pulses, so a session blocked on the user is told from a running one at a
/// glance rather than by reading the word.
public struct StatusDot: View {
    let tone: DotTone
    var size: CGFloat = 8

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var breathing = false

    public init(tone: DotTone, size: CGFloat = 8) {
        self.tone = tone
        self.size = size
    }

    private var pulses: Bool { tone == .working && !reduceMotion }

    public var body: some View {
        Circle()
            .fill(Theme.dotColor(tone))
            .frame(width: size, height: size)
            // A trough deep enough to read as motion and shallow enough that a
            // still frame never shows a washed-out green.
            .opacity(breathing ? 0.5 : 1)
            .animation(pulses ? .easeInOut(duration: 1.1).repeatForever(autoreverses: true) : nil,
                       value: breathing)
            .onAppear { breathing = pulses }
            .onChange(of: pulses) { _, now in breathing = now }
            .accessibilityHidden(true)
    }
}

extension SessionState {
    /// The words shown beside the dot.
    public var label: String {
        switch self {
        case .starting: "starting"
        case .running: "running"
        case .needsApproval: "needs approval"
        case .needsInput: "needs input"
        case .error: "error"
        case .stopped: "stopped"
        case .readonly: "terminal"
        case .idle: "done"
        default: rawValue
        }
    }
}

extension Session {
    /// What the session list and the chat header say beside the dot.
    /// Amendment A10: an attached session names the terminal that owns it.
    public var statusLabel: String { isAttached ? "terminal · attached" : state.label }
}

/// The black pill used for the one primary action on a screen.
public struct PrimaryButtonStyle: ButtonStyle {
    var fullWidth = true

    public init(fullWidth: Bool = true) { self.fullWidth = fullWidth }

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.medium))
            .foregroundStyle(Theme.onAccent)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: Theme.Touch.primary)
            .padding(.horizontal, Theme.Space.large)
            .background(Theme.accent, in: Capsule())
            .opacity(configuration.isPressed ? 0.82 : 1)
    }
}

/// The pill itself: the tint, the type ramp and the height every chip shares,
/// whether it acts when tapped or only shows a value.
public struct ChipPill: ViewModifier {
    public init() {}

    public func body(content: Content) -> some View {
        content
            .font(Theme.Text.meta)
            .foregroundStyle(Theme.ink)
            .padding(.horizontal, Theme.Space.small + 2)
            .frame(minHeight: 32)
            .background(Theme.quietFill, in: Capsule())
    }
}

/// A quiet pill for secondary actions and chips: tinted, never outlined, so a
/// screen carries one filled button and nothing else with an edge.
public struct ChipButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .modifier(ChipPill())
            .opacity(configuration.isPressed ? 0.6 : 1)
            // The pill stays 32 pt tall; the tappable area is 44.
            .frame(minHeight: Theme.Touch.minimum)
            .contentShape(Rectangle())
    }
}

/// Amendment A17: a chip that shows rather than offers. It is the same pill as
/// the menus beside it, and deliberately not a button: there is no request
/// that would change what it says, and a control that does nothing when tapped
/// is worse than one that was never offered. The 44 pt row keeps it aligned
/// with the chips that are tappable.
public struct StaticChip: View {
    let text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        Text(text)
            .modifier(ChipPill())
            .frame(minHeight: Theme.Touch.minimum)
    }
}

/// A dot and a word, in that order, always both. The dot alone is never the
/// signal, and the word alone loses the glanceable colour.
public struct StatusLabel: View {
    let tone: DotTone
    let text: String

    public init(tone: DotTone, text: String) {
        self.tone = tone
        self.text = text
    }

    public var body: some View {
        HStack(spacing: 5) {
            StatusDot(tone: tone)
            Text(text)
                .font(Theme.Text.meta)
                .foregroundStyle(tone == .waiting ? Theme.attention : Theme.inkSecondary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(text)
    }
}

/// The agent a session runs, as a tinted pill. Tinted and never outlined, so a
/// row keeps its one edge budget for the surface it sits on.
public struct AgentChip: View {
    let agent: String

    public init(agent: String) { self.agent = agent }

    public var body: some View {
        Text(AgentLabel.name(agent))
            .font(Theme.Text.caption)
            .foregroundStyle(Theme.inkSecondary)
            .lineLimit(1)
            .padding(.horizontal, Theme.Space.tight)
            .padding(.vertical, 2)
            .background(Theme.quietFill, in: Capsule())
    }
}

/// The caption above a group of rows: uppercase, tracked, secondary, with an
/// optional trailing count or control on the same line.
public struct ListGroupHeader<Trailing: View>: View {
    let title: String
    let leading: Color?
    let trailing: Trailing

    public init(_ title: String, dot: Color? = nil,
                @ViewBuilder trailing: () -> Trailing = { EmptyView() }) {
        self.title = title
        leading = dot
        self.trailing = trailing()
    }

    public var body: some View {
        HStack(spacing: Theme.Space.tight) {
            if let leading {
                Circle().fill(leading).frame(width: 6, height: 6).accessibilityHidden(true)
            }
            Text(title.uppercased())
                .font(Theme.Text.groupHeader)
                .kerning(Theme.headerKerning)
                .foregroundStyle(Theme.inkSecondary)
            Spacer(minLength: Theme.Space.tight)
            trailing
        }
    }
}

/// A small label used above a group of fields.
public struct FieldLabel: View {
    let text: String
    var trailing: AnyView?

    public init(_ text: String) {
        self.text = text
        trailing = nil
    }

    public init<Trailing: View>(_ text: String, @ViewBuilder trailing: () -> Trailing) {
        self.text = text
        self.trailing = AnyView(trailing())
    }

    public var body: some View {
        HStack {
            Text(text.uppercased())
                .font(Theme.Text.groupHeader)
                .kerning(Theme.headerKerning)
                .foregroundStyle(Theme.inkSecondary)
            Spacer(minLength: Theme.Space.small)
            trailing
        }
    }
}

/// The name of a field the reader fills in. Sentence case, because a form label
/// is read as a word rather than as the eyebrow above a section.
public struct FormLabel: View {
    let text: String
    var trailing: AnyView?

    public init(_ text: String) {
        self.text = text
        trailing = nil
    }

    public init<Trailing: View>(_ text: String, @ViewBuilder trailing: () -> Trailing) {
        self.text = text
        self.trailing = AnyView(trailing())
    }

    public var body: some View {
        HStack {
            Text(text)
                .font(Theme.Text.meta)
                .foregroundStyle(Theme.inkSecondary)
            Spacer(minLength: Theme.Space.small)
            trailing
        }
        .textCase(nil)
    }
}

/// Monospace text for a path, a command or a tool title.
public struct CodeText: View {
    let text: String
    var color: Color = Theme.inkSecondary
    var font: Font = Theme.mono

    public init(_ text: String, color: Color = Theme.inkSecondary, font: Font = Theme.mono) {
        self.text = text
        self.color = color
        self.font = font
    }

    public var body: some View {
        Text(text)
            .font(font)
            .foregroundStyle(color)
            .lineLimit(1)
            .truncationMode(.head)
    }
}

public struct EmptyStateView: View {
    let symbol: String
    let title: String
    let message: String

    public init(symbol: String, title: String, message: String) {
        self.symbol = symbol
        self.title = title
        self.message = message
    }

    public var body: some View {
        VStack(spacing: Theme.Space.small + 2) {
            Image(systemName: symbol)
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(Theme.inkSecondary)
            Text(title).font(.headline).foregroundStyle(Theme.ink)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(Theme.inkSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, Theme.Space.large)
        .padding(.vertical, 44)
        .accessibilityElement(children: .combine)
    }
}

/// The app mark: a black rounded square with connected dots.
public struct AppMark: View {
    var size: CGFloat = 44

    public init(size: CGFloat = 44) { self.size = size }

    public var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.26, style: .continuous).fill(Theme.accent)
            Image(systemName: "point.3.connected.trianglepath.dotted")
                .font(.system(size: size * 0.5, weight: .medium))
                .foregroundStyle(Theme.onAccent)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A one-line banner used for errors and for unconfirmed delivery.
public struct NoticeBanner: View {
    let text: String
    var tint: Color = Theme.danger
    var actionTitle: String?
    var action: (() -> Void)?
    var dismiss: (() -> Void)?

    public init(text: String, tint: Color = Theme.danger, actionTitle: String? = nil,
                action: (() -> Void)? = nil, dismiss: (() -> Void)? = nil) {
        self.text = text
        self.tint = tint
        self.actionTitle = actionTitle
        self.action = action
        self.dismiss = dismiss
    }

    public var body: some View {
        HStack(spacing: Theme.Space.small) {
            Circle().fill(tint).frame(width: 6, height: 6).accessibilityHidden(true)
            Text(text)
                .font(.footnote)
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: Theme.Space.tight)
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.footnote.weight(.medium))
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.ink)
                    .frame(minHeight: Theme.Touch.minimum)
            }
            if let dismiss {
                Button(action: dismiss) {
                    Image(systemName: "xmark").font(.footnote)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Theme.inkSecondary)
                .frame(width: Theme.Touch.minimum, height: Theme.Touch.minimum)
                .accessibilityLabel("Dismiss")
            }
        }
        .padding(.horizontal, Theme.Space.medium)
        .padding(.vertical, Theme.Space.small)
        .background(Theme.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(Theme.hairline).frame(height: 0.5) }
    }
}
