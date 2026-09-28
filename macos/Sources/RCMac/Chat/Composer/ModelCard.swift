import RCCore
import SwiftUI

/// A21 — the card the model chip opens: the speed tier and the model on one
/// row, the effort slider under it. It stays open until it is dismissed, so
/// several changes can be made in one visit, and the model list takes the card
/// over rather than stacking a second popover on it. It reads the session
/// live, because a change is drawn the moment it is made and the device's
/// reply confirms it (A40: on a shared session, only the reply).
struct ModelCard: View {
    let chat: ChatStore
    let models: [AgentOption]
    let efforts: [AgentOption]
    let speeds: [AgentOption]
    let gates: ComposerGates
    let onSet: (SessionOptions) -> Void
    @State private var picking: Bool
    /// The stop the thumb is on, which the word beside the model name reads:
    /// the card says what was chosen the moment it is chosen.
    @State private var index: Int

    init(chat: ChatStore, models: [AgentOption], efforts: [AgentOption], speeds: [AgentOption],
         gates: ComposerGates, picking: Bool = false, onSet: @escaping (SessionOptions) -> Void) {
        self.chat = chat
        self.models = models
        self.efforts = efforts
        self.speeds = speeds
        self.gates = gates
        self.onSet = onSet
        _picking = State(initialValue: picking)
        _index = State(initialValue: efforts.firstIndex { $0.id == chat.session.effort } ?? -1)
    }

    private var stop: Int { efforts.firstIndex { $0.id == chat.session.effort } ?? -1 }

    var body: some View {
        Group {
            if picking { modelList } else { card }
        }
        // A change from anywhere else — a `/model` in the terminal, another
        // window — wins over the position the thumb was left in.
        .onChange(of: stop) { _, next in index = next }
    }

    private var modelList: some View {
        MenuList {
            ForEach(models) { model in
                MenuItemRow(model.label, selected: chat.session.model == model.id) {
                    picking = false
                    if chat.session.model != model.id { onSet(SessionOptions(model: model.id)) }
                }
            }
        }
        .accessibilityLabel(S.composer.model)
    }

    private var card: some View {
        let session = chat.session
        let modelText = ComposerLabels.label(models, session.model) ?? S.agentLabel(session.agent)
        let effortText = efforts.indices.contains(index) ? efforts[index].label
            : ComposerLabels.label(efforts, session.effort)
        let nameRow = SizedBox(alternatives: LabelPair.pairs(models: models, efforts: efforts)) { pair in
            ModelNameLabel(pair: pair)
        } shown: {
            ModelNameLabel(pair: LabelPair(model: modelText, effort: effortText))
        }
        return VStack(alignment: .leading, spacing: Space.sp3) {
            HStack(spacing: Space.sp1) {
                if !speeds.isEmpty, gates.canSet(.speed) {
                    SpeedToggle(speeds: speeds, current: session.speed, onSet: onSet)
                }
                if !models.isEmpty, gates.canSet(.model) {
                    Button { picking = true } label: {
                        HStack(spacing: 6) {
                            nameRow
                            Icon(.chevronRight, size: 14).foregroundStyle(Palette.inkTertiary)
                        }
                    }
                    .buttonStyle(ModelNameStyle())
                    .accessibilityLabel(S.composer.option(S.composer.model, modelText))
                } else {
                    nameRow.modifier(ModelNameChrome(highlighted: false))
                }
            }
            if !efforts.isEmpty, gates.canSet(.effort) {
                EffortSlider(efforts: efforts, index: $index) { picked in
                    let effort = efforts[picked]
                    if effort.id != chat.session.effort { onSet(SessionOptions(effort: effort.id)) }
                }
            }
        }
        .padding(EdgeInsets(top: Space.sp1, leading: Space.sp2, bottom: Space.sp3, trailing: Space.sp2))
        .frame(minWidth: 220, alignment: .leading)
    }
}

/// The model name and, beside it, the effort word the thumb is on.
private struct ModelNameLabel: View {
    let pair: LabelPair

    var body: some View {
        HStack(spacing: 6) {
            Text(pair.model ?? "").css(FontSize.fs14)
            if let effort = pair.effort {
                Text(effort).css(FontSize.fs13).foregroundStyle(Palette.inkSecondary)
            }
        }
        .lineLimit(1)
        .fixedSize()
    }
}

/// `.model-card-name`: the row that opens the model list, tinted under the
/// pointer; `.static` where the model is not the device's to change.
private struct ModelNameStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        ModelNameBody(configuration: configuration)
    }
}

private struct ModelNameBody: View {
    let configuration: ButtonStyleConfiguration
    @State private var isHovered = false

    var body: some View {
        configuration.label
            .modifier(ModelNameChrome(highlighted: isHovered || configuration.isPressed))
            .onHover { isHovered = $0 }
            .pointerStyle(.link)
    }
}

private struct ModelNameChrome: ViewModifier {
    let highlighted: Bool

    func body(content: Content) -> some View {
        content
            .padding(.vertical, 6)
            .padding(.horizontal, Space.sp2)
            .background(RoundedRectangle(cornerRadius: Radius.sm, style: .circular)
                .fill(highlighted ? Palette.surfaceHover : Color.clear))
            .contentShape(Rectangle())
    }
}
