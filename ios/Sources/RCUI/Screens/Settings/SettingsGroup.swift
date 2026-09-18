import SwiftUI
import RCCore

/// A group of settings rows: a caption on the canvas, and the rows on one soft
/// surface with nothing but spacing between them.
///
/// `docs/DESIGN.md` § "The Settings screen". The rows are one `List` cell, not
/// one cell each: `List` draws a separator of its own between two cells under
/// conditions no `listRowSeparator` reaches on this screen, and a surface that
/// is a single cell has no boundary for it to draw on. The insets are the rows'
/// own (`settingsRowPadding()`), so a row that is a button or a menu is its
/// whole padded width.
struct SettingsGroup<Rows: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder let rows: Rows

    init(_ title: LocalizedStringKey, @ViewBuilder rows: () -> Rows) {
        self.title = title
        self.rows = rows()
    }

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 0) { rows }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Theme.surface)
                .listRowSeparator(.hidden)
        } header: {
            FieldLabel(title)
        }
    }
}

extension View {
    /// The inset every settings row shares, so titles, switches and menus line
    /// up and the surface has room around them. On the row itself rather than
    /// on the cell: the rows of a group share one cell (`SettingsGroup`).
    func settingsRowPadding() -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
            .padding(.horizontal, Theme.Space.medium)
    }
}
