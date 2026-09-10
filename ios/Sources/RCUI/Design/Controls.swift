import SwiftUI
import RCCore

/// A session status dot. The colour is a shortcut; the label next to it always
/// says the same thing in words.
public struct StatusDot: View {
    let state: SessionState
    var size: CGFloat = 8

    public init(state: SessionState, size: CGFloat = 8) {
        self.state = state
        self.size = size
    }

    public var body: some View {
        Circle()
            .fill(Theme.statusColor(state.token))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

extension SessionState {
    public var token: SessionStateToken {
        switch self {
        case .running, .starting: .running
        case .needsApproval, .needsInput: .attention
        case .error: .error
        default: .resting
        }
    }

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

/// A bordered pill for secondary actions and toolbar chips.
public struct ChipButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.footnote)
            .foregroundStyle(Theme.ink)
            .padding(.horizontal, Theme.Space.small + 2)
            .frame(minHeight: 32)
            .background(Theme.surface, in: Capsule())
            .overlay(Capsule().strokeBorder(Theme.border, lineWidth: 0.5))
            .opacity(configuration.isPressed ? 0.7 : 1)
            // The pill stays 32 pt tall; the tappable area is 44.
            .frame(minHeight: Theme.Touch.minimum)
            .contentShape(Rectangle())
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
                .font(.caption2.weight(.semibold))
                .kerning(0.6)
                .foregroundStyle(Theme.inkSecondary)
            Spacer(minLength: Theme.Space.small)
            trailing
        }
    }
}

/// Monospace text for a path, a command or a tool title.
public struct CodeText: View {
    let text: String
    var color: Color = Theme.inkSecondary

    public init(_ text: String, color: Color = Theme.inkSecondary) {
        self.text = text
        self.color = color
    }

    public var body: some View {
        Text(text)
            .font(Theme.mono)
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
        .overlay(alignment: .bottom) { Rectangle().fill(Theme.border).frame(height: 0.5) }
    }
}
