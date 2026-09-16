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
    /// Whether something outside is writing into the field and the last line
    /// is the one to keep in view — `docs/DESIGN.md` § "The composer" →
    /// **While dictation runs, the field follows the words**. Off for typing,
    /// where the caret keeps itself visible.
    let followsTail: Bool
    @State private var scroll = FieldScrollProbe.report(offset: 0, end: 0)

    public init(_ placeholder: String, text: Binding<String>,
                isFocused: Binding<Bool>? = nil, identifier: String? = nil,
                followsTail: Bool = false) {
        self.placeholder = placeholder
        _text = text
        self.isFocused = isFocused
        self.identifier = identifier
        self.followsTail = followsTail
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
            .overlay(alignment: .bottomTrailing) { scrollProbe }
    }

    /// The scroll position where a UI test can read it, and nowhere else:
    /// `FieldScrollProbe.isOn` is false in every build that was not launched
    /// by a test asking for it.
    @ViewBuilder
    private var scrollProbe: some View {
        if FieldScrollProbe.isOn, let identifier {
            // A point tall and drawn in nothing: the element is there to be
            // read, and a screenshot taken through the probe is the screenshot
            // without it.
            Text(verbatim: scroll)
                .font(.system(size: 1))
                .foregroundStyle(.clear)
                .fixedSize()
                .allowsHitTesting(false)
                .accessibilityIdentifier("\(identifier).scroll")
        }
    }

    @ViewBuilder
    private var field: some View {
        #if os(iOS)
        ScrollingTextView(placeholder: placeholder, text: $text,
                          isFocused: isFocused, identifier: identifier,
                          followsTail: followsTail,
                          scroll: FieldScrollProbe.isOn ? $scroll : nil)
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
    let followsTail: Bool
    let scroll: Binding<String>?

    @Environment(\.isEnabled) private var isEnabled

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text, isFocused: isFocused)
    }

    func makeUIView(context: Context) -> TailFollowingTextView {
        let view = TailFollowingTextView()
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

    func updateUIView(_ view: TailFollowingTextView, context: Context) {
        let changed = view.text != text
        if changed { view.text = text }
        if view.accessibilityLabel != placeholder { view.accessibilityLabel = placeholder }
        view.isEditable = isEnabled
        view.isSelectable = isEnabled
        view.followsTail = followsTail
        view.onScroll = report
        applyScrolling(to: view, coordinator: context.coordinator)
        // The tail is taken in `layoutSubviews`, once the view has the height
        // SwiftUI gave it: a text that just grew past the cap is still the
        // height of one line ago here, and a scroll aimed at that lands short.
        if changed, followsTail { view.setNeedsLayout() }
        applyFocus(to: view)
    }

    /// Hands the field's scroll position to the probe element beside it, on
    /// the turn after this one: the position is read while SwiftUI is laying
    /// the field out, and writing state inside that pass is undefined.
    private var report: ((String) -> Void)? {
        guard let scroll else { return nil }
        return { position in
            guard scroll.wrappedValue != position else { return }
            DispatchQueue.main.async { scroll.wrappedValue = position }
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: TailFollowingTextView,
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

/// The text view `GrowingTextField` draws, which can be asked to keep its last
/// line in view while something outside is filling it.
///
/// The tail is taken in `layoutSubviews` rather than where the text is written,
/// because that is the first moment the view has both the height SwiftUI gave
/// it and the size of the text now in it. The offset is set rather than the end
/// of the text scrolled to, so nothing here has an opinion about the selection
/// the field gets when it is later tapped, and it is never animated: the words
/// arrive in bursts and an animation would still be running when the next one
/// lands. Scrolling down is the only move it makes — a field already showing
/// its last line is left alone, and so is one that is not following at all.
final class TailFollowingTextView: UITextView {
    var followsTail = false
    var onScroll: ((String) -> Void)?

    /// How far the text can move inside the field: zero until it is longer
    /// than the field is tall.
    private var end: CGFloat {
        contentSize.height - bounds.height + adjustedContentInset.bottom
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if followsTail, isScrollEnabled, end > contentOffset.y + 0.5 {
            setContentOffset(CGPoint(x: 0, y: end), animated: false)
        }
        onScroll?(FieldScrollProbe.report(offset: contentOffset.y, end: end))
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
