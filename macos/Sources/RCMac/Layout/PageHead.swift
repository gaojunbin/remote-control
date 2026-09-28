import SwiftUI

/// `.page-head`: a page's title — 30 points, 600, tightened by 2 %, 22 at 760
/// and narrower — with an optional `.hint` under it and the page's actions at
/// the trailing edge, 24 points above the page's content.
public struct PageHead<Actions: View>: View {
    let title: String
    let hint: String?
    let actions: Actions
    @Environment(\.layoutClass) private var layout

    public init(_ title: String, hint: String? = nil, @ViewBuilder actions: () -> Actions) {
        self.title = title
        self.hint = hint
        self.actions = actions()
    }

    public var body: some View {
        let size = layout.maxWidth760 ? FontSize.fs22 : FontSize.fs30
        HStack(alignment: .top, spacing: Space.sp4) {
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .css(size, weight: .semibold, tracking: -0.02)
                    .accessibilityAddTraits(.isHeader)
                if let hint {
                    Hint(hint).padding(.top, 2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            actions
        }
        .padding(.bottom, Space.sp6)
    }
}

extension PageHead where Actions == EmptyView {
    public init(_ title: String, hint: String? = nil) {
        self.init(title, hint: hint) { EmptyView() }
    }
}
