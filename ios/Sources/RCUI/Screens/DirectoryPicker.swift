import SwiftUI
import RCCore

/// Browses directories on a device through `device.dirs`.
///
/// Select stays disabled until the listing on screen is the path it would
/// return, so a slow reply can never hand back a directory the user did not see.
struct DirectoryPicker: View {
    let deviceID: String
    let onSelect: (String) -> Void

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var listing: DirectoryListing?
    @State private var loadingPath: String?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if let listing {
                    if let parent = listing.parent {
                        Button {
                            Task { await load(parent) }
                        } label: {
                            Label("Up one level", systemImage: "arrow.up.left")
                                .frame(minHeight: Theme.Touch.minimum)
                        }
                        .buttonStyle(.plain)
                    }
                    ForEach(listing.entries) { entry in
                        Button {
                            Task { await load(entry.path) }
                        } label: {
                            HStack(spacing: Theme.Space.small) {
                                Image(systemName: entry.isGit ? "shippingbox" : "folder")
                                    .foregroundStyle(Theme.inkSecondary)
                                Text(entry.name).foregroundStyle(Theme.ink)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundStyle(Theme.inkSecondary)
                            }
                            .frame(minHeight: Theme.Touch.minimum)
                        }
                        .buttonStyle(.plain)
                    }
                    if listing.entries.isEmpty {
                        Text("No subdirectories here.")
                            .font(.footnote)
                            .foregroundStyle(Theme.inkSecondary)
                    }
                } else if let error {
                    Text(error).font(.footnote).foregroundStyle(Theme.danger)
                } else {
                    HStack { ProgressView(); Text("Loading").foregroundStyle(Theme.inkSecondary) }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle(listing.map { URL(fileURLWithPath: $0.path).lastPathComponent } ?? "Browse")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Select") {
                        if let path = listing?.path { onSelect(path) }
                        dismiss()
                    }
                    .disabled(listing == nil || loadingPath != nil)
                    .accessibilityIdentifier("dirs.select")
                }
            }
            .task { await load(nil) }
        }
        .sheetSize()
    }

    private func load(_ path: String?) async {
        guard let channel = model.connection.channel else { return }
        loadingPath = path ?? ""
        defer { loadingPath = nil }
        do {
            listing = try await channel.request(.dirs(deviceID: deviceID, path: path),
                                                as: DirectoryListing.self)
            error = nil
        } catch {
            self.error = model.connection.message(for: error)
        }
    }
}

#Preview("Directory picker") {
    DemoPreview {
        DirectoryPicker(deviceID: DemoFixtures.macDeviceID) { _ in }
    }
}
