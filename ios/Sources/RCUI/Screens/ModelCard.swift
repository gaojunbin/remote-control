import SwiftUI
import RCCore

/// The words on a model card, with the lightning glyph before them while a
/// faster tier is on. The live chip, the card's own first row and the
/// read-only chip on a terminal-held session all read the same way.
struct ModelCardLabel: View {
    let text: String
    let isFast: Bool

    var body: some View {
        HStack(spacing: 4) {
            if isFast {
                Image(systemName: "bolt.fill").font(.caption2)
            }
            Text(text)
        }
    }
}

/// The model name with the effort word after it, as the card's first row draws
/// it. The sizer behind that row stacks this same view, so the box it measures
/// is the box the words land in.
private struct ModelNameLabel: View {
    let model: String
    let effort: String?

    var body: some View {
        HStack(spacing: Theme.Space.tight) {
            Text(model).font(Theme.Text.label).foregroundStyle(Theme.ink)
            if let effort {
                Text(effort).font(Theme.Text.meta).foregroundStyle(Theme.inkSecondary)
            }
        }
    }
}

/// Amendment A21: the composer's one control for what runs and how hard.
///
/// The chip reads the model label with the effort word after it; a tap opens a
/// card over the keyboard holding the speed toggle, the model list and the
/// effort slider. The card stays up until it is dismissed, so several changes
/// can be made in one visit.
struct ModelCardChip: View {
    let chat: ChatStore
    let agent: AgentInfo?
    @State private var isOpen = false

    var body: some View {
        Button { isOpen = true } label: {
            ModelCardSizer(pairs: ModelCardSizing.pairs(for: agent,
                                                        model: AgentLabel.name(chat.session.agent))) { pair in
                ModelCardLabel(text: pair.joined,
                               isFast: ModelCardSizing.reservesLightning(for: agent))
            } content: {
                ModelCardLabel(text: ModelCardText.words(for: chat.session, agent: agent),
                               isFast: chat.session.speed != nil)
            }
        }
        .buttonStyle(ChipButtonStyle())
        .accessibilityLabel("Model")
        .accessibilityValue(ModelCardText.spoken(for: chat.session, agent: agent))
        .accessibilityIdentifier("composer.modelCard")
        .popover(isPresented: $isOpen, arrowEdge: .bottom) {
            ModelCard(chat: chat, agent: agent)
                .presentationCompactAdaptation(.popover)
        }
    }
}

/// What the chip says, in one place, so the live chip and the read-only chip
/// never word the same session differently.
enum ModelCardText {
    static func words(for session: Session, agent: AgentInfo?) -> String {
        let text = TerminalSetting.modelCardText(for: session, agent: agent)
        return text.isEmpty ? AgentLabel.name(session.agent) : text
    }

    /// The glyph says "faster tier" to the eye and nothing to a screen reader.
    static func spoken(for session: Session, agent: AgentInfo?) -> String {
        let words = words(for: session, agent: agent)
        guard let tier = agent?.speedLabel(session.speed) ?? session.speed else { return words }
        return "\(words), \(tier)"
    }
}

/// `docs/DESIGN.md` § "The model card": the chip and the card's name row are as
/// wide as the widest model-and-effort combination the agent offers, so nothing
/// beside them shifts while a level is chosen or a model is picked.
///
/// The width is measured, not guessed: every `models × efforts` pair is stacked
/// behind the visible label with `.hidden()`, which keeps the layout and drops
/// the drawing, so the box takes the widest of them.
private struct ModelCardSizer<Sizer: View, Content: View>: View {
    let pairs: [ModelCardSizing.Pair]
    @ViewBuilder let sizer: (ModelCardSizing.Pair) -> Sizer
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack {
            ForEach(pairs, id: \.self) { pair in
                sizer(pair).hidden().accessibilityHidden(true)
            }
            content()
        }
    }
}

/// The combinations a sizer measures. An agent with no efforts or no models
/// measures whatever it has, which is what the row would draw for it.
enum ModelCardSizing {
    struct Pair: Hashable {
        let model: String
        let effort: String?

        /// The two words as one string, exactly the way
        /// `TerminalSetting.modelCardText` words a session for the chip.
        var joined: String { effort.map { "\(model) \($0)" } ?? model }
    }

    /// Whether the lightning glyph takes a place on the row. It is reserved
    /// wherever the agent lists a tier, so turning the tier on moves nothing.
    static func reservesLightning(for agent: AgentInfo?) -> Bool {
        agent?.speeds.isEmpty == false
    }

    /// Every model label with every effort label after it. The fallback stands
    /// in where the agent lists no model, because that is what the row draws
    /// there.
    static func pairs(for agent: AgentInfo?, model fallback: String) -> [Pair] {
        let models = agent?.models.map(\.label) ?? []
        let efforts = agent?.supports(.effort) == true ? agent?.efforts.map(\.label) ?? [] : []
        let names = models.isEmpty ? [fallback] : models
        guard !efforts.isEmpty else { return names.map { Pair(model: $0, effort: nil) } }
        return names.flatMap { name in efforts.map { Pair(model: name, effort: $0) } }
    }
}

/// The card itself: speed and model on the first row, the effort slider on the
/// second, and the model list under them once the name is tapped.
private struct ModelCard: View {
    let chat: ChatStore
    let agent: AgentInfo?

    @State private var stop = 0
    @State private var showsModels = false

    /// One stop per level the agent offers, and nothing to slide when it
    /// offers one level or none.
    private var efforts: [AgentOption] {
        guard agent?.supports(.effort) == true else { return [] }
        return agent?.efforts ?? []
    }

    private var models: [AgentOption] { agent?.models ?? [] }

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            HStack(spacing: Theme.Space.small) {
                if agent?.speeds.isEmpty == false { speedToggle }
                modelButton
            }
            if efforts.count > 1 { effortSlider }
            if showsModels { modelList }
        }
        .padding(.horizontal, Theme.Space.medium)
        .padding(.vertical, Theme.Space.small)
        // The name row sizes itself to the widest combination, so the card
        // takes its width from its content and never less than it had.
        .frame(minWidth: 280)
        .onAppear { stop = currentEffortIndex }
        .onChange(of: chat.session.effort) { _, _ in stop = currentEffortIndex }
    }

    // MARK: - Row one

    /// A tap cycles standard → each tier the agent lists → standard. The
    /// lightning fills on the tap, because the store draws the change before
    /// the request leaves.
    private var speedToggle: some View {
        Button {
            guard let next = chat.nextSpeed else { return }
            Task { await chat.set(speed: next) }
        } label: {
            Image(systemName: isFast ? "bolt.fill" : "bolt")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(isFast ? Theme.onAccent : Theme.ink)
                .frame(width: 32, height: 32)
                .background(isFast ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Theme.quietFill),
                            in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Speed")
        .accessibilityValue(speedValue)
        .accessibilityIdentifier("composer.speed")
    }

    private var modelButton: some View {
        Button { showsModels.toggle() } label: {
            HStack(spacing: Theme.Space.tight) {
                ModelCardSizer(pairs: ModelCardSizing.pairs(for: agent, model: modelLabel)) { pair in
                    ModelNameLabel(model: pair.model, effort: pair.effort)
                } content: {
                    ModelNameLabel(model: modelLabel, effort: effortLabel)
                }
                Spacer(minLength: Theme.Space.tight)
                Image(systemName: showsModels ? "chevron.up" : "chevron.down")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.inkSecondary)
            }
            .frame(minHeight: Theme.Touch.minimum)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(models.isEmpty)
        .accessibilityLabel("Model")
        .accessibilityValue(modelLabel)
        .accessibilityIdentifier("composer.model")
    }

    // MARK: - Row two

    /// The word above follows the thumb; the request waits for the release, so
    /// dragging across four levels is one `session.set` and not four.
    private var effortSlider: some View {
        StopSlider(stops: efforts.count, index: $stop, value: effortLabel ?? "") { landed in
            guard let option = efforts[safe: landed] else { return }
            Task { await chat.set(effort: option.id) }
        }
        .accessibilityLabel("Effort")
        .accessibilityIdentifier("composer.effort")
    }

    private var modelList: some View {
        VStack(alignment: .leading, spacing: 0) {
            Divider().overlay(Theme.hairline)
            ForEach(models) { option in
                Button {
                    showsModels = false
                    Task { await chat.set(model: option.id) }
                } label: {
                    HStack(spacing: Theme.Space.tight) {
                        Text(option.label).font(Theme.Text.label).foregroundStyle(Theme.ink)
                        Spacer(minLength: Theme.Space.tight)
                        if option.id == currentModelID {
                            Image(systemName: "checkmark").font(.caption.weight(.semibold))
                        }
                    }
                    .frame(minHeight: Theme.Touch.minimum)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("composer.model.\(option.id)")
            }
        }
    }

    // MARK: - Values

    private var isFast: Bool { chat.session.speed != nil }

    private var speedValue: String {
        agent?.speedLabel(chat.session.speed) ?? L10n.string("Standard")
    }

    private var currentModelID: String? { chat.session.model ?? agent?.defaultModel }

    private var modelLabel: String {
        agent?.modelLabel(currentModelID) ?? currentModelID ?? AgentLabel.name(chat.session.agent)
    }

    /// The level the thumb is on while it is moving, and the session's own
    /// level when there is no slider to move.
    private var effortLabel: String? {
        guard !efforts.isEmpty else { return agent?.effortLabel(chat.session.effort) }
        return efforts[safe: stop]?.label
    }

    private var currentEffortIndex: Int {
        let chosen = chat.session.effort ?? agent?.defaultEffort
        return efforts.firstIndex { $0.id == chosen } ?? 0
    }
}

private extension Array {
    /// The element at an index that may have drifted past the end, such as a
    /// slider stop left over from an agent with more levels.
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
