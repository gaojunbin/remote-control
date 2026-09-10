import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import RCCore

/// The message bar: attachments, dictation, a text field where Return inserts a
/// newline, and a send button that is always an explicit, separate tap.
struct Composer: View {
    let chat: ChatStore
    @Binding var showsQueue: Bool
    @Binding var showsSettings: Bool

    @Environment(AppModel.self) private var model
    @State private var voice: InlineVoiceDraftSession?
    @State private var usesGateway = false
    @State private var attachments: [OutboundAttachment] = []
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showsFileImporter = false
    @State private var showsCamera = false
    @State private var attachmentError: String?
    @State private var sendAfterDictation = false
    /// Amendment A10: which terminal-owned control the user just reached for.
    /// The one-line status swaps to its explanation for a few seconds.
    @State private var blockedControl: TerminalControl?
    @FocusState private var isWriting: Bool

    /// A control an attached session leaves to the terminal.
    private enum TerminalControl: Hashable { case attachments, settings }

    private var target: VoiceDraftTarget {
        VoiceDraftTarget(account: model.connection.account,
                         deviceID: chat.deviceID, sessionID: chat.sessionID)
    }

    private var agent: AgentInfo? { model.agent(for: chat.session) }

    var body: some View {
        @Bindable var chat = chat
        VStack(spacing: Theme.Space.small) {
            if let attachmentError {
                Text(attachmentError).font(.caption).foregroundStyle(Theme.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if chat.isAttached {
                terminalNote
            }
            if !attachments.isEmpty { attachmentStrip }

            if let voice, voice.voice.phase.isBusy {
                VoiceCapturePanel(session: voice, usesGateway: usesGateway, draft: $chat.draft,
                                  cancel: { cancelDictation() },
                                  stopAndSend: { sendWhenDictationFinishes(voice) })
            } else {
                inputRow
            }
            optionsRow
        }
        .padding(.horizontal, Theme.Space.page)
        .padding(.top, Theme.Space.small)
        .padding(.bottom, Theme.Space.small)
        .barBackground()
        .modifier(VoiceBinding(voice: voice, draft: $chat.draft, target: target))
        .onAppear {
            prepareVoice()
            syncSendability()
        }
        .onChange(of: model.connection.phase) { _, _ in syncSendability() }
        .onChange(of: model.device(for: chat.session)?.online) { _, _ in syncSendability() }
        .onChange(of: voice?.voice.phase) { _, phase in
            guard sendAfterDictation, let phase, !phase.isBusy else { return }
            sendAfterDictation = false
            // A failed run keeps whatever was recognised in the draft, but the
            // user decides whether to send it.
            guard phase == .review, chat.canSend else { return }
            send(mode: .auto)
        }
        .onChange(of: model.settings.voiceBackend) { _, _ in prepareVoice() }
        .onChange(of: model.settings.voiceLanguage) { _, _ in prepareVoice() }
        .onChange(of: photoItems) { _, items in Task { await ingest(items) } }
        .fileImporter(isPresented: $showsFileImporter, allowedContentTypes: [.item],
                      allowsMultipleSelection: true) { result in
            Task { await ingest(files: result) }
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $showsCamera) {
            CameraCapture { data in addPhoto(data, name: "photo.jpg") }
                .ignoresSafeArea()
        }
        #endif
    }

    private var inputRow: some View {
        @Bindable var chat = chat
        return HStack(alignment: .bottom, spacing: Theme.Space.tight) {
            attachControl

            TextField(placeholder, text: $chat.draft, axis: .vertical)
                .lineLimit(1...6)
                .focused($isWriting)
                .font(.body)
                .padding(.horizontal, Theme.Space.small)
                .padding(.vertical, Theme.Space.small)
                .background(Theme.surface,
                            in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous)
                    .strokeBorder(Theme.border, lineWidth: 0.5))
                .disabled(chat.isReadOnly)
                .accessibilityIdentifier("composer.prompt")

            if let voice {
                VoiceButton(session: voice) { startDictation() }
                    .disabled(chat.isReadOnly)
            }

            Button {
                send(mode: .auto)
            } label: {
                Image(systemName: "arrow.up")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.onAccent)
                    .frame(width: Theme.Touch.primary, height: Theme.Touch.primary)
                    .background(Theme.accent, in: Circle())
            }
            .buttonStyle(.plain)
            .disabled(!chat.canSend)
            .opacity(chat.canSend ? 1 : 0.4)
            .contextMenu { sendMenu }
            .accessibilityLabel("Send")
            .accessibilityIdentifier("composer.send")
        }
    }

    /// On an attached session the picker is inert, because the relay has no way
    /// to hand bytes to a live CLI. Reaching for it says so rather than
    /// swallowing the tap.
    @ViewBuilder
    private var attachControl: some View {
        if chat.isAttached {
            Button { blockedControl = .attachments } label: { attachLabel }
                .buttonStyle(.plain)
                .foregroundStyle(Theme.ink)
                .opacity(0.45)
                .accessibilityLabel("Add an attachment")
                .accessibilityHint(attachmentsOwnedByTerminal)
                .accessibilityIdentifier("composer.attach")
        } else {
            Menu {
                Button { showsFileImporter = true } label: { Label("Files", systemImage: "folder") }
                #if os(iOS)
                Button { showsCamera = true } label: { Label("Camera", systemImage: "camera") }
                #endif
                PhotosPicker(selection: $photoItems, maxSelectionCount: RequestLimits.maxAttachments,
                             matching: .images) {
                    Label("Photos", systemImage: "photo")
                }
            } label: {
                attachLabel
            }
            .foregroundStyle(Theme.ink)
            .disabled(!chat.allowsAttachments || agent?.supports(.attachments) != true)
            .accessibilityLabel("Add an attachment")
            .accessibilityIdentifier("composer.attach")
        }
    }

    private var attachLabel: some View {
        Image(systemName: "plus")
            .frame(width: Theme.Touch.minimum, height: Theme.Touch.minimum)
    }

    @ViewBuilder
    private var sendMenu: some View {
        if agent?.supports(.queue) == true {
            Button { send(mode: .queue) } label: { Label("Queue", systemImage: "text.line.first.and.arrowtriangle.forward") }
        }
        if agent?.supports(.interrupt) == true, !chat.isAttached || agent?.sharedInterrupt == true {
            Button { send(mode: .interrupt) } label: { Label("Interrupt & send", systemImage: "bolt") }
        }
    }

    private var optionsRow: some View {
        HStack(spacing: Theme.Space.tight) {
            Button {
                openSettings()
            } label: {
                Text(agent?.modelLabel(chat.session.model) ?? chat.session.agent)
            }
            .buttonStyle(ChipButtonStyle())
            .opacity(chat.allowsSettingsChanges ? 1 : 0.45)
            .accessibilityHint(chat.allowsSettingsChanges ? Text("") : settingsOwnedByTerminal)
            .accessibilityIdentifier("composer.model")

            Button {
                openSettings()
            } label: {
                Text(agent?.permissionModeLabel(chat.session.permissionMode) ?? "Permissions")
            }
            .buttonStyle(ChipButtonStyle())
            .opacity(chat.allowsSettingsChanges ? 1 : 0.45)
            .accessibilityHint(chat.allowsSettingsChanges ? Text("") : settingsOwnedByTerminal)

            Menu {
                Picker("Dictation language", selection: languageBinding) {
                    Text("Automatic").tag("auto")
                    ForEach(languageCodes, id: \.self) { code in
                        Text(languageName(code)).tag(code)
                    }
                }
            } label: {
                Text(model.settings.voiceLanguage == "auto"
                     ? "Auto" : languageName(model.settings.voiceLanguage))
            }
            .menuStyle(.button)
            .buttonStyle(ChipButtonStyle())
            .accessibilityLabel("Dictation language")
            .accessibilityIdentifier("composer.language")

            if chat.session.queued > 0 {
                Button { showsQueue = true } label: { Text("Up next · \(chat.session.queued)") }
                    .buttonStyle(ChipButtonStyle())
                    .accessibilityIdentifier("composer.queue")
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Amendment A10: one quiet line on an attached session, saying who owns
    /// the two controls the device cannot drive. Reaching for either swaps the
    /// line for that control's own sentence rather than adding a second one.
    private var terminalNote: some View {
        blockedLabel
            .font(.caption)
            .foregroundStyle(Theme.inkSecondary)
            .lineLimit(1)
            // The line shrinks a little on a narrow phone rather than
            // truncating: a half-sentence explains nothing.
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("composer.terminalNote")
            .task(id: blockedControl) {
                guard blockedControl != nil else { return }
                try? await Task.sleep(for: .seconds(4))
                guard !Task.isCancelled else { return }
                blockedControl = nil
            }
    }

    private var blockedLabel: Text {
        switch blockedControl {
        case .attachments: attachmentsOwnedByTerminal
        case .settings: settingsOwnedByTerminal
        case nil: Text("Attached to the terminal · settings and attachments are changed there")
        }
    }

    private var attachmentsOwnedByTerminal: Text {
        Text("Attachments cannot be delivered to a terminal session")
    }

    private var settingsOwnedByTerminal: Text {
        Text("Change it in the terminal")
    }

    private var attachmentStrip: some View {
        ScrollView(.horizontal) {
            HStack(spacing: Theme.Space.tight) {
                ForEach(attachments) { attachment in
                    HStack(spacing: 4) {
                        Image(systemName: "paperclip").font(.caption2)
                        Text(attachment.name).font(.caption).lineLimit(1)
                        Button {
                            attachments.removeAll { $0.id == attachment.id }
                        } label: {
                            Image(systemName: "xmark.circle.fill").font(.caption)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Remove \(attachment.name)")
                    }
                    .foregroundStyle(Theme.inkSecondary)
                    .padding(.horizontal, Theme.Space.small)
                    .frame(height: 30)
                    .background(Theme.surfaceSunken, in: Capsule())
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var languageCodes: [String] {
        let offered = model.connection.stt.languages.filter { $0 != "auto" }
        return offered.isEmpty ? ["en", "zh", "ja", "de", "fr", "es"] : offered
    }

    private func languageName(_ code: String) -> String {
        Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    private var languageBinding: Binding<String> {
        Binding(get: { model.settings.voiceLanguage },
                set: { model.settings.voiceLanguage = $0 })
    }

    private var placeholder: String {
        if let reason = chat.sendBlockReason { return reason }
        guard chat.isRunning else { return "Message" }
        return chat.isAttached ? "Message · sent when the terminal is idle" : "Message · will be queued"
    }

    private func openSettings() {
        if chat.allowsSettingsChanges { showsSettings = true } else { blockedControl = .settings }
    }

    private func send(mode: SendMode) {
        let outgoing = attachments
        attachments = []
        attachmentError = nil
        Task {
            await chat.send(mode: mode, attachments: outgoing)
            await model.saveDraft()
        }
    }

    // MARK: - Dictation

    private func prepareVoice() {
        // Releasing a listening session would leave the microphone and the
        // recording audio session live with no UI to stop them.
        voice?.reset()
        let backend = SpeechBackend.make(settings: model.settings, connection: model.connection)
        usesGateway = model.settings.voiceBackend == .gateway && model.connection.stt.enabled
        voice = InlineVoiceDraftSession(platform: backend.platform, isPreview: backend.isScripted)
    }

    /// Stop dictation and send once the backend has produced its final text.
    private func sendWhenDictationFinishes(_ voice: InlineVoiceDraftSession) {
        sendAfterDictation = true
        voice.finish()
    }

    /// The composer knows about the connection; the chat store does not.
    private func syncSendability() {
        chat.connectionReady = model.connection.phase == .connected || model.isDemo
        chat.deviceOnline = model.device(for: chat.session)?.online ?? false
        chat.agent = agent
    }

    private func startDictation() {
        guard let voice else { return }
        isWriting = false
        voice.start(draft: chat.draft, target: target)
    }

    private func cancelDictation() {
        guard let voice else { return }
        if let restored = voice.cancel(currentDraft: chat.draft, currentTarget: target) {
            chat.draft = restored
        }
    }

    // MARK: - Attachments

    private func ingest(_ items: [PhotosPickerItem]) async {
        photoItems = []
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            addPhoto(data, name: item.itemIdentifier ?? "photo.jpg")
        }
    }

    /// Every photo goes through the same downscale: a full-resolution camera
    /// JPEG would be rejected at the 6 MB limit with no way to shrink it.
    private func addPhoto(_ data: Data, name: String) {
        do {
            add(data: try PhotoPreparation.jpeg(from: data), name: name, mime: "image/jpeg")
        } catch {
            attachmentError = (error as? LocalizedError)?.errorDescription ?? "That image could not be attached."
        }
    }

    private func ingest(files result: Result<[URL], any Error>) async {
        guard case .success(let urls) = result else { return }
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            // Check the size before reading: a large video would spike memory
            // and could be jetsammed before the guard below ever ran.
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            guard size <= RequestLimits.maxAttachmentBytes else {
                attachmentError = "\(url.lastPathComponent) is larger than 6 MB."
                continue
            }
            guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { continue }
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            add(data: data, name: url.lastPathComponent, mime: mime)
        }
    }

    /// The limits are checked here so a rejected message never costs a draft.
    private func add(data: Data, name: String, mime: String) {
        guard attachments.count < RequestLimits.maxAttachments else {
            attachmentError = "You can attach at most \(RequestLimits.maxAttachments) files to one message."
            return
        }
        guard data.count <= RequestLimits.maxAttachmentBytes else {
            attachmentError = "\(name) is larger than 6 MB."
            return
        }
        attachmentError = nil
        attachments.append(OutboundAttachment(name: name, mime: mime, data: data))
    }
}

/// Applies the dictation modifier only once a backend exists.
private struct VoiceBinding: ViewModifier {
    let voice: InlineVoiceDraftSession?
    @Binding var draft: String
    let target: VoiceDraftTarget

    func body(content: Content) -> some View {
        if let voice {
            content.inlineVoiceInput(session: voice, draft: $draft, target: target)
        } else {
            content
        }
    }
}

/// Model, permission mode, effort and the STT language, all from what the
/// device said it supports.
struct SessionSettingsSheet: View {
    let chat: ChatStore
    let agent: AgentInfo?
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var settings = model.settings
        NavigationStack {
            Form {
                if let agent, !agent.models.isEmpty {
                    Section {
                        Picker("Model", selection: modelBinding) {
                            ForEach(agent.models) { option in Text(option.label).tag(option.id) }
                        }
                        .disabled(isLockedToTerminal)
                        .accessibilityIdentifier("session.model")
                    } header: { FieldLabel("Model") } footer: { terminalFooter }
                }
                if let agent, !agent.permissionModes.isEmpty {
                    Section {
                        Picker("Permissions", selection: permissionBinding) {
                            ForEach(agent.permissionModes) { option in Text(option.label).tag(option.id) }
                        }
                        .disabled(isLockedToTerminal)
                        .accessibilityIdentifier("session.permissions")
                    } header: { FieldLabel("Permissions") } footer: { terminalFooter }
                }
                if let agent, agent.supports(.effort), !agent.efforts.isEmpty {
                    Section {
                        Picker("Effort", selection: effortBinding) {
                            ForEach(agent.efforts) { option in Text(option.label).tag(option.id) }
                        }
                        .disabled(isLockedToTerminal)
                    } header: { FieldLabel("Effort") } footer: { terminalFooter }
                }
                Section {
                    Picker("Dictation language", selection: $settings.voiceLanguage) {
                        Text("Automatic").tag("auto")
                        ForEach(languages, id: \.self) { code in
                            Text(languageName(code)).tag(code)
                        }
                    }
                    .accessibilityIdentifier("session.sttLanguage")
                } header: {
                    FieldLabel("Voice")
                } footer: {
                    Text(model.settings.voiceBackend.explanation).font(.caption)
                }
            }
            .scrollContentBackground(.hidden)
            .pageBackground()
            .navigationTitle("Session settings")
            .inlineNavigationTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .sheetSize()
    }

    private var languages: [String] {
        model.connection.stt.languages.filter { $0 != "auto" }
    }

    /// Amendment A10: `session.set` is unsupported for model, permission mode
    /// and effort while a live CLI owns the session.
    private var isLockedToTerminal: Bool { !chat.allowsSettingsChanges }

    @ViewBuilder
    private var terminalFooter: some View {
        if isLockedToTerminal {
            Text("Change it in the terminal")
                .font(.caption)
                .accessibilityIdentifier("session.terminalNote")
        }
    }

    private func languageName(_ code: String) -> String {
        Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    private var modelBinding: Binding<String> {
        Binding(get: { chat.session.model ?? agent?.defaultModel ?? "" },
                set: { value in Task { await chat.set(model: value) } })
    }

    private var permissionBinding: Binding<String> {
        Binding(get: { chat.session.permissionMode ?? agent?.defaultPermissionMode ?? "" },
                set: { value in Task { await chat.set(permissionMode: value) } })
    }

    private var effortBinding: Binding<String> {
        Binding(get: { chat.session.effort ?? agent?.defaultEffort ?? "" },
                set: { value in Task { await chat.set(effort: value) } })
    }
}

#Preview("Session settings") {
    DemoPreview {
        SessionSettingsSheet(
            chat: ChatStore(session: demoSession(), channel: DemoGateway()),
            agent: DemoFixtures.claude)
    }
}
