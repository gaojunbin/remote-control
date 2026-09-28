import RCCore
import SwiftUI

/// `ComposerBottomRow` (`docs/DESIGN.md` § "The control row", A43, A44): from
/// the leading edge, Up next, the model card, the permission mode. The web
/// transcribes only on the gateway, whose provider detects the language, so
/// the row has no dictation language to offer. What the device cannot change
/// is drawn as the value the terminal chose (A17, A40).
struct ComposerControlRow: View {
    let composer: ComposerModel
    /// A preview's way of showing one of the row's panels open.
    let opening: ComposerStage.Opening?

    var body: some View {
        let session = composer.session
        let agent = composer.agent
        let gates = composer.gates
        let models = agent?.models ?? []
        let efforts = agent?.efforts ?? []
        let speeds = agent?.speeds ?? []
        let modes = agent?.permissionModes ?? []
        let modelText = ComposerLabels.label(models, session.model)
        let effortText = ComposerLabels.label(efforts, session.effort)
        let speedText = ComposerLabels.label(speeds, session.speed)
        // A40: the card is the one control for three settings, so it opens as
        // soon as one of them is the device's; the rows inside it decide.
        let cardLive = gates.canSet(.model) || gates.canSet(.effort) || gates.canSet(.speed)
        let hasCard = !models.isEmpty || !efforts.isEmpty || !speeds.isEmpty
        let cardText = ComposerLabels.line(LabelPair(model: modelText, effort: effortText))
        let modeText = ComposerLabels.label(modes, session.permissionMode)

        ChipFlow(spacing: Space.sp2) {
            UpNext(composer: composer, initiallyOpen: opening == .upNext)
            if cardLive {
                if hasCard {
                    Popover(align: .start, side: .top, chevron: false, triggerStyle: ComposerChipStyle(),
                            ariaLabel: S.composer.modelCard,
                            initiallyOpen: opening == .modelCard || opening == .modelList) {
                        SizedBox(alternatives: LabelPair.pairs(models: models, efforts: efforts)) { pair in
                            ModelChipLabel(pair: pair, glyph: !speeds.isEmpty)
                        } shown: {
                            ModelChipLabel(pair: LabelPair(model: modelText, effort: effortText),
                                           glyph: speedText != nil, fallback: S.agentLabel(session.agent))
                        }
                    } content: { _ in
                        ModelCard(chat: composer.chat, models: models, efforts: efforts, speeds: speeds,
                                  gates: gates, picking: opening == .modelList, onSet: composer.setOption)
                    }
                }
            } else if !cardText.isEmpty {
                TerminalSettingChip(name: S.composer.modelCard, text: cardText, speed: speedText)
            }
            if gates.canSet(.permissionMode) {
                if !modes.isEmpty {
                    Popover(align: .start, side: .top, triggerStyle: ComposerChipStyle(),
                            ariaLabel: S.composer.permissionMode, initiallyOpen: opening == .permissions) {
                        Text(modeText ?? S.composer.permissionMode).css(FontSize.fs12)
                    } content: { close in
                        MenuList {
                            ForEach(modes) { mode in
                                MenuItemRow(mode.label, selected: session.permissionMode == mode.id) {
                                    composer.setOption(SessionOptions(permissionMode: mode.id))
                                    close()
                                }
                            }
                        }
                        .accessibilityLabel(S.composer.permissionMode)
                    }
                }
            } else if let modeText {
                TerminalSettingChip(name: S.composer.permissionMode, text: modeText, speed: nil)
            }
        }
    }
}

extension ComposerModel {
    /// Every change the card and the menu make goes through the store, which
    /// draws it at once and puts the previous value back on a refusal (A21) —
    /// except on a shared session, where the reply is what is drawn (A40). A
    /// refusal is the page's banner, as it is on the web.
    func setOption(_ patch: SessionOptions) {
        Task {
            await chat.set(model: patch.model, permissionMode: patch.permissionMode, effort: patch.effort,
                           speed: patch.speed.map { SpeedChange(id: $0) })
        }
    }
}
