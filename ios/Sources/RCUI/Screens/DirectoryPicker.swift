import SwiftUI
import RCCore

/// Browses directories on a device through `device.dirs`, and makes one with
/// `device.mkdir` (A37).
///
/// Select stays disabled until the listing on screen is the path it would
/// return, so a slow reply can never hand back a directory the user did not see.
/// A folder made here is the listing on screen the moment the device answers,
/// which is what lets the same Select pick it.
struct DirectoryPicker: View {
    let deviceID: String
    let onSelect: (String) -> Void

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var listing: DirectoryListing?
    @State private var isBusy = false
    @State private var error: String?
    /// Amendment A37: the name being typed, and why the device refused the last
    /// one. Both live in a row at the head of the list rather than in an alert,
    /// so the refusal is read beside the name that caused it and the name is
    /// still there to be corrected.
    @State private var isNaming = false
    @State private var folderName = ""
    @State private var nameError: String?
    @FocusState private var isNameFocused: Bool

    var body: some View {
        NavigationStack {
            List {
                if let listing {
                    if isNaming { newFolderRow }
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
                ToolbarItem(placement: .primaryAction) { newFolderButton }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Select") {
                        if let path = listing?.path { onSelect(path) }
                        dismiss()
                    }
                    .disabled(listing == nil || isBusy)
                    .accessibilityIdentifier("dirs.select")
                }
            }
            .task { await load(nil) }
        }
        .sheetSize()
    }

    /// Amendment A37: offered wherever a listing is shown, and only while there
    /// is one to make a folder in.
    private var newFolderButton: some View {
        Button {
            folderName = ""
            nameError = nil
            isNaming = true
            isNameFocused = true
        } label: {
            Label("New folder", systemImage: "folder.badge.plus").labelStyle(.iconOnly)
        }
        .disabled(listing == nil || isBusy || isNaming)
        .accessibilityIdentifier("dirs.newFolder")
    }

    /// The name, what the device said about the last one, and the two actions —
    /// at the head of the listing the folder would be made in.
    private var newFolderRow: some View {
        VStack(alignment: .leading, spacing: Theme.Space.small) {
            TextField("Folder name", text: $folderName)
                .font(Theme.monoBody)
                .plainTextEntry()
                .focused($isNameFocused)
                .submitLabel(.done)
                .onSubmit { Task { await makeFolder() } }
                .padding(.horizontal, Theme.Space.small)
                .frame(minHeight: Theme.Touch.minimum)
                .background(Theme.surfaceSunken,
                            in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
                .accessibilityIdentifier("dirs.folderName")
            if let nameError {
                Text(nameError)
                    .font(.footnote)
                    .foregroundStyle(Theme.danger)
                    .accessibilityIdentifier("dirs.folderError")
            }
            HStack(spacing: Theme.Space.large) {
                Button("Create") { Task { await makeFolder() } }
                    .disabled(folderName.trimmed.isEmpty || isBusy)
                    .accessibilityIdentifier("dirs.create")
                Button("Cancel") { stopNaming() }
                    .foregroundStyle(Theme.inkSecondary)
                    .accessibilityIdentifier("dirs.cancelFolder")
            }
            .font(.callout.weight(.medium))
            .buttonStyle(.plain)
            .frame(minHeight: Theme.Touch.minimum)
        }
        .padding(.vertical, Theme.Space.tight)
    }

    private func load(_ path: String?) async {
        guard let channel = model.connection.channel else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            listing = try await channel.request(.dirs(deviceID: deviceID, path: path),
                                                as: DirectoryListing.self)
            error = nil
            stopNaming()
        } catch {
            self.error = model.connection.message(for: error)
        }
    }

    /// Makes the folder inside the directory on screen and stands in it, which
    /// is the listing the device replies with. A refusal keeps the row up with
    /// what the device said and the name left to correct.
    private func makeFolder() async {
        guard let channel = model.connection.channel, let path = listing?.path else { return }
        let name = folderName.trimmed
        guard !name.isEmpty, !isBusy else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            listing = try await channel.request(.mkdir(deviceID: deviceID, path: path, name: name),
                                                as: DirectoryListing.self)
            error = nil
            stopNaming()
        } catch {
            nameError = DirectoryError.makeFolder(error)
        }
    }

    private func stopNaming() {
        isNaming = false
        isNameFocused = false
        folderName = ""
        nameError = nil
    }
}

#Preview("Directory picker") {
    DemoPreview {
        DirectoryPicker(deviceID: DemoFixtures.macDeviceID) { _ in }
    }
}
