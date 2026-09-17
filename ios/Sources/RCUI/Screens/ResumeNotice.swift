import SwiftUI
import RCCore

/// Amendment A35 — a session the usage limit paused, said where the session is.
///
/// One line above the transcript with two actions and no more: Change opens the
/// smallest time picker the platform has, and Cancel takes the resume away at
/// once (`docs/DESIGN.md` § "Paused by the usage limit"). The status dot is not
/// touched: the session is idle and the dot says so, and this carries the pause.
struct ResumeNotice: View {
    let chat: ChatStore
    let resume: SessionResume
    @State private var showsPicker = false
    /// One request at a time, so a second tap cannot send a second cancel.
    @State private var acting = OneAtATime()

    var body: some View {
        NoticeBanner(text: ResumeText.notice(resume),
                     tint: Theme.attention,
                     actionTitle: L10n.string("Change"),
                     action: { showsPicker = true },
                     actionEnabled: !acting.isBusy,
                     secondaryActionTitle: L10n.string("Cancel"),
                     secondaryAction: { Task { await acting.run { await chat.cancelResume() } } },
                     identifier: "chat.resumeNotice")
            .sheet(isPresented: $showsPicker) {
                ResumeTimeSheet(resume: resume) { date in
                    await acting.run { await chat.setResume(at: date) }
                }
            }
    }
}

/// The time picker Change opens: one compact date-and-time control, prefilled
/// with the time the resume is set for, between a minute from now and eight
/// days out — the bounds the device enforces, so nothing it would refuse can be
/// chosen here.
struct ResumeTimeSheet: View {
    let resume: SessionResume
    let set: (Date) async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var date: Date
    @State private var isSetting = false

    /// The bounds are read once, when the sheet opens: a range that slid under
    /// the picker while someone was turning it would move the wheel for them.
    private let opened = Date()

    init(resume: SessionResume, set: @escaping (Date) async -> Void) {
        self.resume = resume
        self.set = set
        _date = State(initialValue: max(resume.date, ResumeBounds.earliest(from: Date())))
    }

    private var range: ClosedRange<Date> {
        ResumeBounds.earliest(from: opened)...ResumeBounds.latest(from: opened)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Resume at", selection: $date, in: range,
                               displayedComponents: [.date, .hourAndMinute])
                        .datePickerStyle(.compact)
                        .font(Theme.Text.label)
                        .settingsRowLayout()
                        .accessibilityIdentifier("resume.picker")
                } footer: {
                    SettingsFooter(L10n.string(
                        "Any time from a minute from now to eight days away."))
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle("Resume")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Set") {
                        isSetting = true
                        Task {
                            await set(date)
                            isSetting = false
                            dismiss()
                        }
                    }
                    .disabled(isSetting)
                    .accessibilityIdentifier("resume.set")
                }
            }
        }
        .sheetSize()
    }
}
