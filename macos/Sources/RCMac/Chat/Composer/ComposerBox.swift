import RCCore
import SwiftUI

/// `.composer-field` and `.composer`: the box the words are typed in, with the
/// controls at its trailing edge — or, while dictation runs, the voice controls
/// on a row of their own under the words — and the slash panel standing on it.
struct ComposerBox: View {
    let composer: ComposerModel
    let sendMenuOpen: Bool
    @State private var focused = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let gates = composer.gates
        let shape = RoundedRectangle(cornerRadius: Radius.lg, style: .circular)
        let listening = composer.voiceBusy
        // The field is the layout's first child in both forms, so it is the
        // same text view before, during and after a dictation.
        ComposerBoxLayout(wrapped: listening) {
            field(disabled: gates.disabled)
            if listening {
                VoiceControls(voice: composer.voice, slot: composer.slot) { composer.voice.done() }
            } else {
                ComposerButtons(composer: composer, sendMenuOpen: sendMenuOpen)
            }
        }
        .padding(.top, Space.sp2 + 1)
        .padding(.bottom, Space.sp2 + 1)
        .padding(.leading, Space.sp4 + 1)
        .padding(.trailing, Space.sp2 + 1)
        .background(shape.fill(gates.disabled ? Palette.surfaceSunken : Palette.surface))
        .overlay(shape.strokeBorder(border(disabled: gates.disabled), lineWidth: 1))
        .animation(Motion.ease(Motion.durFast, reduceMotion: reduceMotion), value: focused)
        .dropDestination(for: URL.self) { urls, _ in
            guard composer.acceptsFiles else { return false }
            composer.attach(urls.map(AttachmentSource.file))
            return true
        }
        // A line of no height along the box's top edge carries the panel,
        // which stands on it and grows upward from there.
        .overlay(alignment: .top) {
            Color.clear.frame(height: 0).overlay(alignment: .bottom) { panel.padding(.bottom, Space.sp2) }
        }
    }

    private func field(disabled: Bool) -> some View {
        ComposerField(composer: composer, text: composer.text, disabled: disabled,
                      readOnly: composer.returning) { focused = $0 }
            .overlay(alignment: .topLeading) {
                if composer.text.isEmpty {
                    Text(composer.placeholder)
                        .css(FontSize.fs14)
                        .foregroundStyle(placeholderInk)
                        .padding(.top, ComposerFieldText.padding)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
    }

    /// `.composer:focus-within` turns the edge ink; `.composer.disabled`, the
    /// quiet line, whatever else holds.
    private func border(disabled: Bool) -> Color {
        if disabled { return Palette.line }
        return focused ? Palette.ink : Palette.lineStrong
    }

    /// A27: the panel while the draft is a slash and a partial name, or the
    /// hint once the first word is complete — standing 8 points above the box,
    /// its full width, never moving the field.
    @ViewBuilder private var panel: some View {
        if composer.panelOpen {
            CommandPanel(rows: composer.panelRows, highlight: composer.highlightIndex,
                         running: composer.gates.running,
                         onHighlight: { composer.highlight = $0 },
                         onTake: { composer.take($0) })
                .fixedSize(horizontal: false, vertical: true)
        } else if let match = composer.commandMatch {
            CommandHint(command: match.command)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// `.composer` is a row aligned to the bottom with 8 between its children;
/// `.composer.listening` wraps, and the voice controls take a row of their own
/// under the field, 8 below it.
struct ComposerBoxLayout: Layout {
    let wrapped: Bool
    var spacing: CGFloat = Space.sp2

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard subviews.count == 2 else { return .zero }
        let width = proposal.width.flatMap { $0.isFinite ? $0 : nil } ?? 400
        let (field, trailing) = sizes(subviews, width: width)
        if wrapped { return CGSize(width: width, height: field.height + spacing + trailing.height) }
        return CGSize(width: width, height: max(field.height, trailing.height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 2 else { return }
        let (field, trailing) = sizes(subviews, width: bounds.width)
        if wrapped {
            subviews[0].place(at: bounds.origin, proposal: ProposedViewSize(field))
            subviews[1].place(at: CGPoint(x: bounds.minX, y: bounds.minY + field.height + spacing),
                              proposal: ProposedViewSize(trailing))
        } else {
            subviews[0].place(at: CGPoint(x: bounds.minX, y: bounds.maxY - field.height),
                              proposal: ProposedViewSize(field))
            subviews[1].place(at: CGPoint(x: bounds.maxX - trailing.width, y: bounds.maxY - trailing.height),
                              proposal: ProposedViewSize(trailing))
        }
    }

    private func sizes(_ subviews: Subviews, width: CGFloat) -> (CGSize, CGSize) {
        if wrapped {
            let field = subviews[0].sizeThatFits(ProposedViewSize(width: width, height: nil))
            let controls = subviews[1].sizeThatFits(ProposedViewSize(width: width, height: nil))
            return (CGSize(width: width, height: field.height), CGSize(width: width, height: controls.height))
        }
        let trailing = subviews[1].sizeThatFits(.unspecified)
        let fieldWidth = max(0, width - trailing.width - spacing)
        let field = subviews[0].sizeThatFits(ProposedViewSize(width: fieldWidth, height: nil))
        return (CGSize(width: fieldWidth, height: field.height), trailing)
    }
}
