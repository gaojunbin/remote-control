import SwiftUI

/// `SettingsGroup.tsx`: a caption on the canvas and its rows on one soft
/// surface. Nothing else.
struct SettingsGroup<Rows: View>: View {
    let title: String
    let rows: Rows

    init(_ title: String, @ViewBuilder rows: () -> Rows) {
        self.title = title
        self.rows = rows()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            GroupTitle(title)
            VStack(spacing: 0) { rows }
                .frame(maxWidth: .infinity)
                .surface()
        }
    }
}
