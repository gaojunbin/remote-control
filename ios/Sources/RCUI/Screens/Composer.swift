import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import RCCore

/// The message bar.
///
/// Two rows and never three. The field owns the first one and grows with what
/// is in it; everything else sits on the second, in one order: the `+` and the
/// microphone, then the session's chips, then Send against the trailing edge.
/// The chips scroll sideways when they do not fit, because a control row that
/// wraps costs the transcript a line every time a chip is added.
///
/// Return inserts a newline, and sending is always an explicit, separate tap —
/// dictation fills the draft and stops there.
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
    @State private var isWriting = false

    private var target: VoiceDraftTarget {
        VoiceDraftTarget(account: model.connection.account,
                         deviceID: chat.deviceID, sessionID: chat.sessionID)
    }

    private var agent: AgentInfo? { model.agent(for: chat.session) }

    var body: some View {
        @Bindable var chat = chat
        return VStack(spacing: Theme.Space.small) {
            noticeLine
            if !attachments.isEmpty { attachmentStrip }
            promptField
            controlsRow
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

    /// One line above the field, and only when something is happening to it:
    /// an attachment problem, or what dictation is doing. An attached session
    /// says so in the header and prints nothing here.
    @ViewBuilder
    private var noticeLine: some View {
        if let attachmentError {
            Text(attachmentError).font(.caption).foregroundStyle(Theme.danger)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if let voice, voice.voice.phase.isBusy || voice.voice.failure != nil {
            VoiceStatusLine(session: voice, usesGateway: usesGateway)
                .task(id: voice.voice.failure == nil) {
                    guard voice.voice.failure != nil else { return }
                    try? await Task.sleep(for: .seconds(6))
                    guard !Task.isCancelled else { return }
                    voice.voice.dismissFailure()
                }
        }
    }

    /// The field takes the row to itself and grows with the draft up to
    /// `ComposerLayout.maximumLines`, then scrolls inside itself.
    private var promptField: some View {
        @Bindable var chat = chat
        return GrowingTextField(placeholder, text: $chat.draft, isFocused: $isWriting,
                                identifier: "composer.prompt")
            // Without this the bar takes its height from what is left over and
            // squeezes the field back to one scrolling line; the transcript is
            // the view that should give way, not the thing being written.
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, Theme.Space.small)
            .padding(.vertical, Theme.Space.small)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface,
                        in: RoundedRectangle(cornerRadius: Theme.Radius.control, style: .continuous))
            .disabled(chat.isReadOnly)
            .overlay { dictationTakeover }
    }

    /// While dictation runs the field shows the transcript arriving. Reaching
    /// for it is a request to take over rather than a dead tap, so it ends the
    /// dictation, keeps every word and puts the cursor in the field.
    @ViewBuilder
    private var dictationTakeover: some View {
        if isDictating {
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture {
                    finishDictation()
                    isWriting = true
                }
                .accessibilityHidden(true)
        }
    }

    /// Everything under the field, on one row — and while dictation runs, the
    /// level meter, the elapsed time and exactly two controls, Cancel and Done,
    /// in place of all of it.
    @ViewBuilder
    private var controlsRow: some View {
        if let voice, voice.voice.phase.isBusy {
            VoiceListeningControls(session: voice,
                                   cancel: { cancelDictation() },
                                   done: { finishDictation() })
        } else {
            HStack(spacing: Theme.Space.tight) {
                // The two quiet icons read as one group, so they sit against
                // each other rather than spread across the row.
                HStack(spacing: 0) {
                    attachControl
                    if let voice {
                        VoiceButton(session: voice) { startDictation() }
                            .disabled(chat.isReadOnly)
                    }
                }
                chips
                sendButton
            }
            .frame(minHeight: Theme.Touch.primary)
        }
    }

    private var sendButton: some View {
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

    private var isDictating: Bool { voice?.voice.phase.isBusy == true }

    /// A control that cannot act is not shown at all. A terminal session takes
    /// no bytes, and neither does an attachment whose device did not report
    /// `shared_attachments` (amendment A11), so the `+` goes rather than
    /// standing there dimmed with a caption under the field explaining it.
    @ViewBuilder
    private var attachControl: some View {
        if chat.allowsAttachments, agent?.supports(.attachments) == true {
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

    /// The middle of the control row: what this session is set to, and what is
    /// waiting behind the turn. They scroll sideways rather than wrap, so the
    /// row keeps its height however many of them there are.
    ///
    /// Amendment A17: the session's own settings are offered only where they
    /// can be changed from here. On a session a terminal holds they are shown
    /// instead, in the same three positions, as chips that open nothing.
    private var chips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: Theme.Space.tight) {
                if chat.allowsSettingsChanges {
                    settingsChip(agent?.modelLabel(chat.session.model) ?? chat.session.agent,
                                 identifier: "composer.model")
                    settingsChip(agent?.permissionModeLabel(chat.session.permissionMode) ?? "Permissions",
                                 identifier: "composer.permissions")
                    if agent?.supports(.effort) == true, let efforts = agent?.efforts, !efforts.isEmpty {
                        settingsChip(agent?.effortLabel(chat.session.effort) ?? "Effort",
                                     identifier: "composer.effort")
                    }
                } else {
                    ForEach(chat.terminalSettings) { setting in terminalChip(setting) }
                }

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
            }
            .padding(.horizontal, 2)
        }
        .scrollIndicators(.hidden)
        // Nothing to scroll while the chips fit, so the row does not rubber-band
        // under a thumb aiming for Send.
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        // A chip cut off flat against Send reads as broken text. The last few
        // points fade instead, which is how a row says there is more of it.
        .mask {
            HStack(spacing: 0) {
                Rectangle()
                LinearGradient(colors: [.black, .black.opacity(0)],
                               startPoint: .leading, endPoint: .trailing)
                    .frame(width: 18)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func settingsChip(_ label: String, identifier: String) -> some View {
        Button { showsSettings = true } label: { Text(label) }
            .buttonStyle(ChipButtonStyle())
            .accessibilityIdentifier(identifier)
    }

    /// Amendment A17: what the terminal chose, where its picker would have
    /// been. It reads as "Model, Sonnet 4.5, set in the terminal" rather than
    /// as a control, so nobody reaches for something that cannot move.
    private func terminalChip(_ setting: TerminalSetting) -> some View {
        StaticChip(setting.text)
            .accessibilityElement()
            .accessibilityLabel(setting.field.label)
            .accessibilityValue(setting.text)
            .accessibilityHint("Set in the terminal")
            .accessibilityIdentifier("composer.readonly.\(setting.field.rawValue)")
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
        // An agent that steers joins the running turn, whoever started it, so
        // it never says the message is waiting for anything.
        if chat.steersRunningTurn { return "Message · will steer the turn" }
        return chat.isAttached ? "Message · sent when the terminal is idle" : "Message · will be queued"
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

    /// The composer knows about the connection; the chat store does not.
    private func syncSendability() {
        chat.canReachGateway = model.connection.phase.canReachGateway || model.isDemo
        chat.deviceOnline = model.device(for: chat.session)?.online ?? false
        chat.agent = agent
    }

    private func startDictation() {
        guard let voice else { return }
        isWriting = false
        voice.start(draft: chat.draft, target: target)
    }

    /// Stop listening and keep every word in the field. Sending is still the
    /// ordinary Send button afterwards.
    private func finishDictation() {
        voice?.finish()
    }

    /// Discard what this dictation added and put back the draft it started from.
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
///
/// Amendment A11: the sheet is reached only from the composer's chips, and
/// those exist only where this app may retune the session, so nothing here is
/// ever locked to the terminal.
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
                        .accessibilityIdentifier("session.model")
                    } header: { FieldLabel("Model") }
                }
                if let agent, !agent.permissionModes.isEmpty {
                    Section {
                        Picker("Permissions", selection: permissionBinding) {
                            ForEach(agent.permissionModes) { option in Text(option.label).tag(option.id) }
                        }
                        .accessibilityIdentifier("session.permissions")
                    } header: { FieldLabel("Permissions") }
                }
                if let agent, agent.supports(.effort), !agent.efforts.isEmpty {
                    Section {
                        Picker("Effort", selection: effortBinding) {
                            ForEach(agent.efforts) { option in Text(option.label).tag(option.id) }
                        }
                    } header: { FieldLabel("Effort") }
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
