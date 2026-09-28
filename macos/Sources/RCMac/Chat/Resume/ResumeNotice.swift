import RCCore
import SwiftUI

/// `web/src/features/chat/ResumeNotice.tsx` — A35: the bar a session with a
/// pending resume carries above its transcript, what happened and when it
/// resumes, with the two actions the ruling allows and no more (`docs/
/// DESIGN.md` § "Paused by the usage limit"). It takes the attention tint the
/// amber dot uses — a pause, not a failure — and the transcript's own width,
/// with a gutter either side.
struct ResumeNotice: View {
    let resume: SessionResume
    let onSet: (Int64) async -> Bool
    let onCancel: () -> Void
    @Environment(\.previewStage) private var stage

    var body: some View {
        HStack(spacing: Space.sp3) {
            Text(verbatim: ResumeWords.noticeText(resume))
                .css(FontSize.fs13)
                .frame(maxWidth: .infinity, alignment: .leading)
            Popover(align: .end, chevron: false, ariaLabel: S.chat.resumeChange,
                    initiallyOpen: stage == "chat.resume.change") {
                Text(S.chat.resumeChange)
            } content: { close in
                ResumeChangeForm(resume: resume, onSet: onSet, onDone: close)
            }
            Button(S.chat.resumeCancel, action: onCancel)
                .buttonStyle(.btn(.ghost, size: .small))
        }
        .foregroundStyle(Color(hex: 0x7A5600))
        .padding(.vertical, Space.sp2)
        .padding(.horizontal, Space.sp4)
        .padding(1)
        .background(RoundedRectangle(cornerRadius: Radius.md, style: .circular).fill(Palette.attentionSoft))
        .chatBorder(ChatBorder(width: 1, radius: Radius.md), color: Color(hex: 0xECDFBA))
        .frame(maxWidth: 760)
        .padding(.horizontal, Space.sp4)
        .frame(maxWidth: .infinity)
        .padding(.top, Space.sp3)
        .accessibilityElement(children: .contain)
    }
}
