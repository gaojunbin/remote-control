import SwiftUI
import RCCore

/// A circle that breathes between full and half opacity while it is asked to,
/// and stands still at full opacity when it is not. Both dots draw themselves
/// with it; which one moves is each dot's own rule.
private struct BreathingCircle: View {
    let color: Color
    let size: CGFloat
    let pulsing: Bool

    @State private var breathing = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            // A trough deep enough to read as motion and shallow enough that a
            // still frame never shows a washed-out colour.
            .opacity(breathing ? 0.5 : 1)
            .animation(pulsing ? .easeInOut(duration: 1.1).repeatForever(autoreverses: true) : nil,
                       value: breathing)
            .onAppear { breathing = pulsing }
            .onChange(of: pulsing) { _, now in breathing = now }
    }
}

/// A session status dot. The colour is a shortcut; the label next to it always
/// says the same thing in words.
///
/// `DotTone` in `RCCore` decides what the dot looks like from the state, who
/// owns the session and whether the machine is reachable. Green means working —
/// leave it; amber means there is something for you. Only a session blocked on
/// the user pulses, so the one state that needs an answer is the one that moves
/// and a running session asks for nothing.
public struct StatusDot: View {
    let tone: DotTone
    var size: CGFloat = 8

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(tone: DotTone, size: CGFloat = 8) {
        self.tone = tone
        self.size = size
    }

    /// Which tone moves. Reduce Motion holds it still, where the amber alone
    /// still says it. Static so the check suites can read the rule without
    /// building a view.
    public static func pulses(tone: DotTone, reduceMotion: Bool) -> Bool {
        tone == .waiting && !reduceMotion
    }

    public var body: some View {
        BreathingCircle(color: Theme.dotColor(tone), size: size,
                        pulsing: StatusDot.pulses(tone: tone, reduceMotion: reduceMotion))
            .accessibilityHidden(true)
    }
}

/// A device's own dot, which is not a session dot and does not follow the tone
/// table (`docs/DESIGN.md` § "The status dot"): green while the machine
/// answers, grey when it is gone, and breathing while it updates itself (A22).
/// Kept apart from `StatusDot` so the session amber can mean "there is
/// something for you" without a row of healthy machines turning amber with it.
public struct OnlineDot: View {
    let online: Bool
    var updating: Bool = false
    var size: CGFloat = 8

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(online: Bool, updating: Bool = false, size: CGFloat = 8) {
        self.online = online
        self.updating = updating
        self.size = size
    }

    public var body: some View {
        BreathingCircle(color: online || updating ? Theme.running : Theme.resting,
                        size: size, pulsing: updating && !reduceMotion)
            .accessibilityHidden(true)
    }
}

extension SessionState {
    /// The words shown beside the dot.
    public var label: String {
        switch self {
        case .starting: L10n.string("starting")
        case .running: L10n.string("running")
        case .needsApproval: L10n.string("needs approval")
        case .needsInput: L10n.string("needs input")
        case .error: L10n.string("error")
        case .stopped: L10n.string("stopped")
        case .readonly: L10n.string("terminal")
        case .idle: L10n.string("done")
        default: rawValue
        }
    }
}

extension Session {
    /// What the session list and the chat header say beside the dot.
    /// Amendment A10: an attached session names the terminal that owns it.
    public var statusLabel: String {
        isAttached ? L10n.string("terminal · attached") : state.label
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
public struct StaticChip<Content: View>: View {
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        content
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

/// The agent a session runs, as a tinted pill: its logo, then its name. Tinted
/// and never outlined, so a row keeps its one edge budget for the surface it
/// sits on, and no agent carries a colour of its own.
public struct AgentChip: View {
    let agent: String

    public init(agent: String) { self.agent = agent }

    public var body: some View {
        HStack(spacing: Theme.Space.hair + 2) {
            AgentLogo(agent: agent)
            Text(AgentLabel.name(agent))
                .font(Theme.Text.caption)
                .foregroundStyle(Theme.inkSecondary)
                .lineLimit(1)
        }
        .padding(.horizontal, Theme.Space.tight)
        .padding(.vertical, 2)
        .background(Theme.quietFill, in: Capsule())
    }
}

/// The caption above a group of fields, with an optional trailing count or
/// control on the same line.
///
/// Sentence case, and the one place that says so: a `Section` inside a `Form`
/// re-cases its header to capitals by default, and `.textCase(nil)` here — on
/// the view the `Section` is handed — turns that off for every call site at
/// once. `docs/DESIGN.md` § "Surfaces, rows and controls": nothing is re-cased.
public struct FieldLabel: View {
    // A key rather than a string: `Text(someString)` is verbatim, so a caption
    // typed as a `String` would be the one word on the screen that never
    // followed the interface language.
    let key: LocalizedStringKey
    var trailing: AnyView?

    public init(_ key: LocalizedStringKey) {
        self.key = key
        trailing = nil
    }

    public init<Trailing: View>(_ key: LocalizedStringKey, @ViewBuilder trailing: () -> Trailing) {
        self.key = key
        self.trailing = AnyView(trailing())
    }

    public var body: some View {
        HStack {
            Text(key)
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

/// A one-line banner used for errors, for unconfirmed delivery and for a
/// session waiting on a resume (A35). It offers at most two actions, because a
/// bar above the transcript that offers three is a toolbar.
public struct NoticeBanner: View {
    let text: String
    var tint: Color = Theme.danger
    var actionTitle: String?
    var action: (() -> Void)?
    /// False while the action this banner offers is still out, so a second tap
    /// cannot start a second one.
    var actionEnabled = true
    /// Amendment A35: the resume notice carries Change and Cancel, in that
    /// order, so the banner takes a second action rather than being forked.
    var secondaryActionTitle: String?
    var secondaryAction: (() -> Void)?
    var dismiss: (() -> Void)?
    /// Names the banner's own line. It goes on the text rather than on the bar,
    /// because an identifier on the bar is inherited by every control inside it
    /// and the two actions would stop being reachable by name.
    var identifier = "notice.text"

    public init(text: String, tint: Color = Theme.danger, actionTitle: String? = nil,
                action: (() -> Void)? = nil, actionEnabled: Bool = true,
                secondaryActionTitle: String? = nil, secondaryAction: (() -> Void)? = nil,
                dismiss: (() -> Void)? = nil, identifier: String = "notice.text") {
        self.text = text
        self.tint = tint
        self.actionTitle = actionTitle
        self.action = action
        self.actionEnabled = actionEnabled
        self.secondaryActionTitle = secondaryActionTitle
        self.secondaryAction = secondaryAction
        self.dismiss = dismiss
        self.identifier = identifier
    }

    public var body: some View {
        HStack(spacing: Theme.Space.small) {
            Circle().fill(tint).frame(width: 6, height: 6).accessibilityHidden(true)
            Text(text)
                .font(.footnote)
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier(identifier)
            Spacer(minLength: Theme.Space.tight)
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.footnote.weight(.medium))
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.ink)
                    .frame(minHeight: Theme.Touch.minimum)
                    .disabled(!actionEnabled)
                    .opacity(actionEnabled ? 1 : 0.4)
                    .accessibilityIdentifier("notice.action")
            }
            if let secondaryActionTitle, let secondaryAction {
                Button(secondaryActionTitle, action: secondaryAction)
                    .font(.footnote.weight(.medium))
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.ink)
                    .frame(minHeight: Theme.Touch.minimum)
                    .disabled(!actionEnabled)
                    .opacity(actionEnabled ? 1 : 0.4)
                    .accessibilityIdentifier("notice.secondaryAction")
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
