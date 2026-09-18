import SwiftUI
import RCCore

/// Read the report first, then decide whether to share it.
struct DiagnosticsView: View {
    let report: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.medium) {
                    Text("This is everything the report contains. Read it before you share it.")
                        .font(.subheadline)
                        .foregroundStyle(Theme.inkSecondary)
                    Text(report)
                        .font(Theme.mono)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("diagnostics.report")
                    ShareLink(item: report) {
                        Label("Share this report", systemImage: "square.and.arrow.up")
                            .frame(minHeight: Theme.Touch.minimum)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(Theme.Space.page)
            }
            .pageBackground()
            .navigationTitle("Diagnostics")
            .inlineNavigationTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .sheetSize()
    }
}

#Preview("Diagnostics") {
    DiagnosticsView(report: """
    Remote Control for iOS — diagnostic snapshot
    App: 1.5.0
    Platform: iOS
    Protocol: v1
    Cache schema: v1
    Mode: offline demo
    Connection: connected
    """)
}
