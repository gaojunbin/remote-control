import SwiftUI

/// `.group-title`: the plain caption above a surface. 12 points, 600, the
/// tertiary ink, indented by the row padding. Sentence case: nothing in the
/// app is re-cased.
public struct GroupTitle: View {
    let text: String

    public init(_ text: String) { self.text = text }

    public var body: some View {
        Text(text)
            .css(FontSize.fs12, weight: .semibold, lineHeight: 1.4)
            .foregroundStyle(Palette.inkTertiary)
            .padding(.leading, Space.sp4)
            .padding(.bottom, Space.sp2)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// `.label`: a form field's label, 13 points, 500, the secondary ink, 8 above
/// its field. Sentence case, like every other caption in the app.
public struct FieldLabel: View {
    let text: String

    public init(_ text: String) { self.text = text }

    public var body: some View {
        Text(text)
            .css(FontSize.fs13, weight: .medium, lineHeight: 1.4)
            .foregroundStyle(Palette.inkSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, Space.sp2)
    }
}

/// `.hint`: secondary text at 13 points.
public struct Hint: View {
    let text: String

    public init(_ text: String) { self.text = text }

    public var body: some View {
        Text(text)
            .css(FontSize.fs13)
            .foregroundStyle(Palette.inkSecondary)
    }
}

/// `.form-error`: why a form was refused, under the fields it belongs to.
public struct FormError: View {
    let text: String

    public init(_ text: String) { self.text = text }

    public var body: some View {
        Text(text)
            .css(FontSize.fs13, lineHeight: 1.5)
            .foregroundStyle(Palette.danger)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isStaticText)
    }
}

/// `.empty`: the centred state a list shows when it has nothing, with an
/// optional first line in the primary ink (`<strong>`).
public struct EmptyState: View {
    let title: String?
    let text: String

    public init(title: String? = nil, _ text: String) {
        self.title = title
        self.text = text
    }

    public var body: some View {
        VStack(spacing: 0) {
            if let title {
                Text(title)
                    .css(FontSize.fs14, weight: .medium)
                    .foregroundStyle(Palette.ink)
                    .padding(.bottom, Space.sp1)
            }
            Text(text)
                .css(FontSize.fs14)
                .foregroundStyle(Palette.inkSecondary)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, Space.sp10)
        .padding(.horizontal, Space.sp4)
    }
}
