import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The field a message is written in: one line while the draft is short,
/// growing with it to `ComposerLayout.maximumLines`, then scrolling inside
/// itself with the system indicator down its trailing edge.
///
/// SwiftUI's `TextField(axis: .vertical)` grows and scrolls exactly like this
/// but draws no indicator, and `.scrollIndicators(.visible)` does not reach the
/// text view it keeps inside — verified on iOS 27 by screenshot, against a
/// `ScrollView` scrolled in the same burst that did show one. So the composer
/// and the answer field on an agent's question own a `UITextView` instead, and
/// the indicator is the stock one: it runs while the draft is being scrolled
/// and fades when scrolling stops.
///
/// Focus is a plain `Bool` binding rather than `@FocusState`, because a
/// `UIViewRepresentable` is not a focus target SwiftUI can move to. It reads
/// the same in both directions: setting it puts the keyboard up, and the field
/// clears it when editing ends. A field nothing drives from outside leaves it
/// out and is focused by touch alone.
public struct GrowingTextField: View {
    let placeholder: String
    @Binding var text: String
    let isFocused: Binding<Bool>?
    let identifier: String?

    public init(_ placeholder: String, text: Binding<String>,
                isFocused: Binding<Bool>? = nil, identifier: String? = nil) {
        self.placeholder = placeholder
        _text = text
        self.isFocused = isFocused
        self.identifier = identifier
    }

    public var body: some View {
        field
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(alignment: .topLeading) {
                if text.isEmpty {
                    Text(placeholder)
                        .font(.body)
                        .foregroundStyle(Theme.inkSecondary)
                        .lineLimit(1)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
    }

    @ViewBuilder
    private var field: some View {
        #if os(iOS)
        ScrollingTextView(placeholder: placeholder, text: $text,
                          isFocused: isFocused, identifier: identifier)
        #else
        TextField("", text: $text, axis: .vertical)
            .lineLimit(ComposerLayout.growth)
            .textFieldStyle(.plain)
            .accessibilityLabel(placeholder)
            .accessibilityIdentifier(identifier ?? "")
        #endif
    }
}

#if os(iOS)
/// The `UITextView` behind `GrowingTextField`.
///
/// It reports its own height, so the range in `ComposerLayout` is measured in
/// real laid-out lines rather than in line breaks: a single wrapped sentence
/// grows the field exactly as three short ones do. Scrolling is switched on
/// only once the text has passed the cap, so a short draft can never be left
/// scrolled away from its own first line.
private struct ScrollingTextView: UIViewRepresentable {
    let placeholder: String
    @Binding var text: String
    let isFocused: Binding<Bool>?
    let identifier: String?

    @Environment(\.isEnabled) private var isEnabled

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text, isFocused: isFocused)
    }

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.delegate = context.coordinator
        view.backgroundColor = .clear
        // The rounded surface and its padding belong to the composer, so the
        // text view carries no inset of its own and the indicator runs the
        // full height of the text.
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.font = .preferredFont(forTextStyle: .body)
        view.adjustsFontForContentSizeCategory = true
        view.textColor = UIColor(Theme.ink)
        view.tintColor = UIColor(Theme.accent)
        view.isScrollEnabled = false
        view.showsVerticalScrollIndicator = true
        view.showsHorizontalScrollIndicator = false
        view.alwaysBounceHorizontal = false
        view.accessibilityIdentifier = identifier
        // UIKit has no placeholder on a text view and no public placeholder
        // value to report, so the drawn placeholder is the field's name: read
        // out before whatever has been typed, and never read out twice, since
        // the visible copy is hidden from accessibility.
        view.accessibilityLabel = placeholder
        view.text = text
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        if view.text != text { view.text = text }
        if view.accessibilityLabel != placeholder { view.accessibilityLabel = placeholder }
        view.isEditable = isEnabled
        view.isSelectable = isEnabled
        applyScrolling(to: view, coordinator: context.coordinator)
        applyFocus(to: view)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView,
                      context: Context) -> CGSize? {
        let offered = proposal.width ?? uiView.bounds.width
        guard offered > 0, offered.isFinite else { return nil }
        let ruler = context.coordinator.ruler(like: uiView)
        let content = Self.contentHeight(of: uiView, width: offered)
        let smallest = ruler.height(lines: ComposerLayout.minimumLines, width: offered)
        let largest = ruler.height(lines: ComposerLayout.maximumLines, width: offered)
        return CGSize(width: offered, height: min(max(content, smallest), largest))
    }

    /// Past the cap the text moves instead of the field, and the indicator
    /// flashes once at that moment so the change is not silent.
    private func applyScrolling(to view: UITextView, coordinator: Coordinator) {
        guard view.bounds.width > 0 else { return }
        let largest = coordinator.ruler(like: view)
            .height(lines: ComposerLayout.maximumLines, width: view.bounds.width)
        let scrolls = Self.contentHeight(of: view, width: view.bounds.width) > largest + 0.5
        guard view.isScrollEnabled != scrolls else { return }
        view.isScrollEnabled = scrolls
        if scrolls { view.flashScrollIndicators() }
    }

    /// First responder changes land on the next turn of the run loop: asking
    /// for the keyboard inside a SwiftUI update would write the binding back
    /// while that update is still running.
    private func applyFocus(to view: UITextView) {
        guard let isFocused else { return }
        let wanted = isFocused.wrappedValue && isEnabled
        guard wanted != view.isFirstResponder else { return }
        DispatchQueue.main.async {
            guard wanted != view.isFirstResponder else { return }
            if wanted {
                guard view.window != nil else { return }
                view.becomeFirstResponder()
            } else {
                view.resignFirstResponder()
            }
        }
    }

    private static func contentHeight(of view: UITextView, width: CGFloat) -> CGFloat {
        view.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
            .height.rounded(.up)
    }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate {
        private let text: Binding<String>
        private let isFocused: Binding<Bool>?
        private let measuring = TextRuler()

        init(text: Binding<String>, isFocused: Binding<Bool>?) {
            self.text = text
            self.isFocused = isFocused
        }

        /// A text view laid out exactly like the field, for asking what a
        /// given number of lines is worth in points.
        func ruler(like view: UITextView) -> TextRuler {
            measuring.match(view)
            return measuring
        }

        func textViewDidChange(_ view: UITextView) {
            if text.wrappedValue != view.text { text.wrappedValue = view.text }
        }

        func textViewDidBeginEditing(_ view: UITextView) {
            guard let isFocused, !isFocused.wrappedValue else { return }
            isFocused.wrappedValue = true
        }

        func textViewDidEndEditing(_ view: UITextView) {
            guard let isFocused, isFocused.wrappedValue else { return }
            isFocused.wrappedValue = false
        }
    }
}

/// What a number of lines is worth in points, measured rather than multiplied:
/// a text view lays its lines out further apart than the font's own line
/// height, so eight times `font.lineHeight` cuts the eighth line in half.
@MainActor
private final class TextRuler {
    private let view = UITextView()
    private var measured: [Int: CGFloat] = [:]

    init() {
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.isScrollEnabled = false
    }

    /// Take the field's font; the measurements are dropped with it, so Dynamic
    /// Type is measured afresh. Width is not part of the cache because the
    /// sample never wraps.
    func match(_ field: UITextView) {
        guard view.font != field.font else { return }
        view.font = field.font
        measured.removeAll()
    }

    func height(lines: Int, width: CGFloat) -> CGFloat {
        if let known = measured[lines] { return known }
        view.text = Array(repeating: "M", count: max(lines, 1)).joined(separator: "\n")
        let points = view
            .sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
            .height.rounded(.up)
        measured[lines] = points
        return points
    }
}
#endif
