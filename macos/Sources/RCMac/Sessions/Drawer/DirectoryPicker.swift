import RCCore
import SwiftUI

/// `DirectoryPicker.tsx`: the modal Browse opens over the New session drawer —
/// the path on screen with New folder beside it (A37), Up one level, the
/// sub-directories with a branch mark on repositories, and Cancel and Use this
/// directory. It browses and makes, and never deletes, renames or moves.
struct DirectoryPicker: ViewModifier {
    @Binding var browser: DirectoryBrowser?
    let onPick: (String) -> Void

    func body(content: Content) -> some View {
        content.modal(isPresented: ListPresence.of($browser), title: S.newSession.browseTitle, width: 520,
                      showClose: true) {
            if let browser { DirectoryListingView(browser: browser) }
        } footer: {
            Btn(S.common.cancel) { browser = nil }
            Btn(S.newSession.browseUse, variant: .primary) {
                if let path = browser?.listing?.path { onPick(path) }
                browser = nil
            }
            .disabled(browser?.listing == nil)
        }
    }
}

/// What the picker shows between its title and its buttons.
private struct DirectoryListingView: View {
    let browser: DirectoryBrowser

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: Space.sp3) {
                // `word-break: break-all`, so a deep path wraps where it must.
                Text(browser.listing.map { TextMeasure.breakAll(Format.tildePath($0.path)) } ?? S.common.loading)
                    .css(FontSize.fs13, mono: true)
                    .foregroundStyle(Palette.inkSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Btn(S.newSession.newFolder, icon: .folderPlus, size: .small) { browser.startNaming() }
                    .disabled(browser.listing == nil || browser.naming)
            }
            .padding(.bottom, Space.sp3)
            if browser.naming {
                NewFolderRow(browser: browser).padding(.bottom, Space.sp3)
            }
            if let error = browser.error {
                // `.login-error`'s -6 top margin, collapsed into the 12 above it.
                FormError(error).padding(.top, -6).padding(.bottom, Space.sp4)
            }
            DirectoryEntries(browser: browser)
        }
    }
}

/// `.picker-list`: the rows on a 1-point `--line` edge, parted by the same
/// line, scrolling past 320 points.
private struct DirectoryEntries: View {
    let browser: DirectoryBrowser

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Radius.md, style: .circular)
        FittingHeight(maximum: 320) {
            VStack(spacing: 0) {
                if let parent = browser.listing?.parent {
                    DirectoryRow(icon: .chevronUp) {
                        Text(S.newSession.browseUp).css(FontSize.fs13)
                    } action: {
                        Task { await browser.open(parent) }
                    }
                }
                ForEach(Array((browser.listing?.entries ?? []).enumerated()), id: \.element.id) { index, entry in
                    if index > 0 || browser.listing?.parent != nil { DirectoryRule() }
                    DirectoryRow(icon: .folder) {
                        Text(entry.name).css(FontSize.fs13, mono: true).foregroundStyle(Palette.ink)
                        if entry.isGit {
                            Spacer(minLength: 0)
                            Icon(.gitBranch, size: 13).foregroundStyle(Palette.inkTertiary)
                        }
                    } action: {
                        Task { await browser.open(entry.path) }
                    }
                }
                if let listing = browser.listing, listing.entries.isEmpty, !browser.loading {
                    if listing.parent != nil { DirectoryRule() }
                    Hint(S.newSession.browseEmpty)
                        .frame(maxWidth: .infinity)
                        .padding(Space.sp4)
                }
            }
            .padding(1)
        }
        .clipShape(shape)
        .overlay(shape.strokeBorder(Palette.line, lineWidth: 1))
    }
}

private struct DirectoryRule: View {
    var body: some View { Rectangle().fill(Palette.line).frame(height: 1) }
}

/// One row of the listing: its icon and label in the secondary ink, a tint and
/// the ink under the pointer.
private struct DirectoryRow<Label: View>: View {
    let icon: LucideIcon
    @ViewBuilder let label: Label
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Space.sp3) {
                Icon(icon, size: 15)
                label
            }
            .padding(.vertical, 10)
            .padding(.horizontal, Space.sp3)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(DirectoryRowStyle())
    }
}

private struct DirectoryRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { DirectoryRowBody(configuration: configuration) }
}

private struct DirectoryRowBody: View {
    let configuration: ButtonStyleConfiguration
    @State private var isHovered = false

    var body: some View {
        configuration.label
            .foregroundStyle(isHovered ? Palette.ink : Palette.inkSecondary)
            .background(isHovered ? Palette.surfaceHover : Color.clear)
            .contentShape(Rectangle())
            .onHover { isHovered = $0 }
            .pointerStyle(.link)
    }
}

/// A scrolling area as tall as what it holds up to `maximum`, and scrolling
/// past it: `max-height` with `overflow-y: auto`.
private struct FittingHeight<Content: View>: View {
    let maximum: CGFloat
    @ViewBuilder let content: Content
    @State private var height: CGFloat = 0

    var body: some View {
        ThinScrollView {
            content.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(height: min(max(height, 1), maximum))
    }
}
