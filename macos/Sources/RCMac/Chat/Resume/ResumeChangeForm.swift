import RCCore
import SwiftUI

/// `ChangeForm` in `ResumeNotice.tsx`: the smallest time picker the platform
/// has — the web's `datetime-local` field — prefilled with the time the device
/// holds, and checked on Set against a minute and eight days away, because a
/// typed value reaches neither bound. The refusal is this app's own sentence.
struct ResumeChangeForm: View {
    let resume: SessionResume
    let onSet: (Int64) async -> Bool
    let onDone: () -> Void
    @State private var text: String
    @State private var error: String?
    @State private var busy = false

    init(resume: SessionResume, onSet: @escaping (Int64) async -> Bool, onDone: @escaping () -> Void) {
        self.resume = resume
        self.onSet = onSet
        self.onDone = onDone
        _text = State(initialValue: ResumeFieldFormat.text(resume.date))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.sp2) {
            Text(S.chat.resumeAt)
                .css(FontSize.fs12)
                .foregroundStyle(Palette.inkSecondary)
            ResumeDateField(text: $text)
                .onChange(of: text) { error = nil }
            if let error {
                Text(error)
                    .css(FontSize.fs12)
                    .foregroundStyle(Palette.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button(action: submit) { Text(S.chat.resumeSet).css(FontSize.fs13, weight: .medium) }
                .buttonStyle(ChatStretchedPrimaryStyle())
                .disabled(busy)
        }
        .padding(Space.sp3)
        .frame(minWidth: 232, alignment: .leading)
    }

    private func submit() {
        guard let date = ResumeFieldFormat.date(text) else {
            error = S.chat.resumeTooSoon
            return
        }
        let at = Int64((date.timeIntervalSince1970 * 1000).rounded())
        if let bound = ResumeWords.boundError(at) {
            error = bound
            return
        }
        error = nil
        busy = true
        Task {
            let taken = await onSet(at)
            busy = false
            if taken { onDone() } else { error = S.errors.resumeSetFailed }
        }
    }
}

/// What the field shows and reads: a time to the minute, as the browser's
/// `datetime-local` field draws it — the short date and time of the person's
/// language, every number at the field's width: "2026/09/28 23:50",
/// "09/28/2026, 11:50 PM". The browser takes that from the system, not from
/// the page, so the interface language does not change it.
@MainActor
enum ResumeFieldFormat {
    static func text(_ date: Date) -> String { formatter.string(from: date) }

    /// The time the field names, or nil when it names none.
    static func date(_ text: String) -> Date? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? nil : formatter.date(from: trimmed)
    }

    private static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = browserLocale
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        formatter.dateFormat = fieldPattern(formatter.dateFormat ?? "y-MM-dd HH:mm")
        formatter.isLenient = false
        return formatter
    }()

    /// The person's first language without its region, which is how the
    /// browser picks the language it draws its own controls in: "zh-Hans-SG"
    /// draws as Simplified Chinese does, not as Singapore's dates are written.
    private static var browserLocale: Locale {
        let language = Locale(identifier: Locale.preferredLanguages.first ?? "en").language
        let parts = [language.languageCode?.identifier, language.script?.identifier].compactMap { $0 }
        return Locale(identifier: parts.isEmpty ? "en" : parts.joined(separator: "-"))
    }

    /// A pattern with every number at the width the browser's field draws it:
    /// four digits of year, two of everything else. Quoted text is kept.
    nonisolated static func fieldPattern(_ pattern: String) -> String {
        var out = ""
        var quoted = false
        var index = pattern.startIndex
        while index < pattern.endIndex {
            let character = pattern[index]
            var end = pattern.index(after: index)
            if character == "'" {
                quoted.toggle()
                out.append(character)
                index = end
                continue
            }
            while !quoted, end < pattern.endIndex, pattern[end] == character { end = pattern.index(after: end) }
            switch (quoted, character) {
            case (false, "y"): out += "yyyy"
            case (false, "M"), (false, "d"), (false, "h"), (false, "H"), (false, "m"):
                out += String(repeating: character, count: 2)
            default: out += pattern[index..<end]
            }
            index = end
        }
        return out
    }
}

/// `.resume-form-field`: 32 points tall with its edge, a strong edge on an
/// 8-point radius, 13-point text 8 points in, the edge turning ink while the
/// field has the keyboard.
private struct ResumeDateField: View {
    @Binding var text: String
    @FocusState private var focused: Bool

    var body: some View {
        TextField("", text: $text)
            .textFieldStyle(.plain)
            .font(.web(size: FontSize.fs13))
            .foregroundStyle(Palette.ink)
            .autocorrectionDisabled()
            .focused($focused)
            .accessibilityLabel(S.chat.resumeAt)
            // Inside the edge, the padding and the point the browser keeps on
            // either side of each of the field's numbers; its text sits a
            // point higher than AppKit centres it.
            .padding(.horizontal, Space.sp2 + 2)
            .padding(.bottom, 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: 32)
            .background(RoundedRectangle(cornerRadius: Radius.sm, style: .circular).fill(Palette.surface))
            .chatBorder(ChatBorder(width: 1, radius: Radius.sm), color: focused ? Palette.ink : Palette.lineStrong)
    }
}
