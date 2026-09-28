import SwiftUI

/// `.attachment-list`: the files the next message carries, one capsule each —
/// the paperclip, the name in the monospaced face, the size at the trailing
/// edge, and the × that takes the file off.
struct AttachmentChips: View {
    let attachments: [ComposerAttachment]
    let onRemove: (Int) -> Void

    var body: some View {
        VStack(spacing: Space.sp1) {
            ForEach(Array(attachments.enumerated()), id: \.element.id) { index, file in
                AttachmentChip(file: file) { onRemove(index) }
            }
        }
    }
}

private struct AttachmentChip: View {
    let file: ComposerAttachment
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: Space.sp2) {
            Icon(.paperclip, size: 12)
            Text(file.name).css(FontSize.fs12, mono: true).lineLimit(1)
            Spacer(minLength: 0)
            Text(Format.bytes(file.size)).css(FontSize.fs11).foregroundStyle(Palette.inkSecondary)
            IconBtn(.x, size: 13, label: S.common.remove, action: onRemove)
        }
        .padding(.vertical, 5 + 1)
        .padding(.leading, Space.sp3 + 1)
        .padding(.trailing, Space.sp2 + 1)
        .background(Capsule().fill(Palette.surfaceSunken))
        .overlay(Capsule().strokeBorder(Palette.line, lineWidth: 1))
    }
}
