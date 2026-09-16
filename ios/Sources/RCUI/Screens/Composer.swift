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

    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var voice: InlineVoiceDraftSession?
    @State private var usesGateway = false
    @State private var attachments: [OutboundAttachment] = []
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showsFileImporter = false
    @State private var showsPhotos = false
    @State private var showsCamera = false
    @State private var attachmentError: String?
    @State private var isWriting = false
    /// How many photos this composer has taken from the library, so each one
    /// is named for its place in the order they were attached in.
    @State private var photosAttached = 0
    /// The attachment pill and the Send circle grow with the type, as the
    /// command panel's rows already do: a fixed frame around a label clips
    /// well before the largest accessibility size.
    @ScaledMetric(relativeTo: .caption) private var pillHeight: CGFloat = 30
    @ScaledMetric(relativeTo: .body) private var primaryTouch: CGFloat = Theme.Touch.primary

    private var target: VoiceDraftTarget {
        VoiceDraftTarget(account: model.connection.account,
                         deviceID: chat.deviceID, sessionID: chat.sessionID)
    }

    private var agent: AgentInfo? { model.agent(for: chat.session) }

    var body: some View {
        @Bindable var chat = chat
        return VStack(spacing: Theme.Space.small) {
            commandPanel
            noticeLine
            if !attachments.isEmpty { attachmentStrip }
            promptField
            polishNote
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
        // Amendment A27: the list is fetched when the conversation opens and
        // asked for again the moment `/` is typed, if the last answer has gone
        // stale or was empty. Only the first slash asks; the letters after it
        // filter what is already on screen.
        .onChange(of: chat.draft.hasPrefix("/")) { was, isCommandDraft in
            guard isCommandDraft, !was else { return }
            Task { await chat.refreshCommands() }
        }
        .onChange(of: photoItems) { _, items in Task { await ingest(items) } }
        .fileImporter(isPresented: $showsFileImporter, allowedContentTypes: [.item],
                      allowsMultipleSelection: true) { result in
            Task { await ingest(files: result) }
        }
        // The picker is presented from the composer, not from the menu row that
        // asks for it: a `PhotosPicker` built inside a `Menu` leaves the view
        // hierarchy the moment the menu closes, so its sheet never arrives.
        .photosPicker(isPresented: $showsPhotos, selection: $photoItems,
                      maxSelectionCount: RequestLimits.maxAttachments, matching: .images)
        #if os(iOS)
        .fullScreenCover(isPresented: $showsCamera) {
            CameraCapture { data in addPhoto(data, name: AttachmentNaming.cameraPhoto) }
                .ignoresSafeArea()
        }
        #endif
    }

    /// Amendment A27: the terminal's `/` menu, over the keyboard. It is drawn
    /// only where the device says the agent takes commands and only while what
    /// has been typed matches at least one of them, so `/` is an ordinary
    /// character on a Claude session and on an empty list alike.
    @ViewBuilder
    private var commandPanel: some View {
        if !chat.commandRows.isEmpty {
            CommandPanel(chat: chat) { isWriting = true }
        }
    }

    /// One line above the field, and only when something is happening to it:
    /// an attachment problem, what dictation is doing, or where the argument of
    /// a command already named goes. An attached session says so in the header
    /// and prints nothing here.
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
        // Something that is happening outranks something that is merely true,
        // so the hint waits for dictation to finish before it takes the line.
        } else if let command = chat.commandHint {
            CommandHintLine(chat: chat, command: command)
        }
    }

    /// The field takes the row to itself and grows with the draft up to
    /// `ComposerLayout.maximumLines`, then scrolls inside itself.
    private var promptField: some View {
        @Bindable var chat = chat
        return GrowingTextField(placeholder, text: $chat.draft, isFocused: $isWriting,
                                identifier: "composer.prompt",
                                followsTail: dictationIsWriting)
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

    /// Amendment A29: the small line under the field once a dictation has been
    /// polished, and the one line a failed polish gets. Both stand until the
    /// next edit or send; the failure also goes by itself after a few seconds,
    /// because nothing is wrong with the draft it is talking about.
    @ViewBuilder
    private var polishNote: some View {
        switch chat.polishPhase {
        case .polished:
            HStack(spacing: Theme.Space.tight) {
                // The identifiers go on the two elements themselves: one on the
                // row would overwrite both.
                Text("Polished").accessibilityIdentifier("composer.polished")
                Text(verbatim: "·").accessibilityHidden(true)
                Button("Undo") { chat.undoPolish() }
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.ink)
                    .accessibilityIdentifier("composer.polishUndo")
                Spacer(minLength: 0)
            }
            .font(.caption)
            .foregroundStyle(Theme.inkSecondary)
        case .failed:
            Text("Polishing failed, your words are unchanged")
                .font(.caption)
                .foregroundStyle(Theme.inkSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("composer.polishFailed")
                .task {
                    try? await Task.sleep(for: .seconds(6))
                    guard !Task.isCancelled else { return }
                    chat.clearPolishNote()
                }
        case .idle, .polishing:
            EmptyView()
        }
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
    /// level meter, the elapsed time and the one button, Done, in place of all
    /// of it.
    ///
    /// Amendment A29: once the transcript is final the ordinary row comes back
    /// around the spinner, which keeps the slot until the model has answered.
    @ViewBuilder
    private var controlsRow: some View {
        if let voice, voice.voice.phase.isBusy {
            VoiceListeningControls(session: voice, slot: primarySlot, done: { finishDictation() })
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
                if primarySlot == .working {
                    WorkingCircle(label: L10n.string("Polishing…"))
                } else {
                    sendButton
                }
            }
            .frame(minHeight: primaryTouch)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: primarySlot)
        }
    }

    /// What the one primary slot holds: Done, the spinner, or Send. The whole
    /// rule is `ComposerPrimarySlot`'s (`docs/DESIGN.md` § "The composer" →
    /// **Done becomes a spinner, and the spinner becomes Send**); both rows read
    /// it from here, so the slot never disagrees with itself across the swap.
    private var primarySlot: ComposerPrimarySlot {
        ComposerPrimarySlot.of(voice: voice?.voice.phase ?? .idle, polish: chat.polishPhase)
    }

    /// Amendment A20: while a question is pending the one primary in the row
    /// answers it instead of sending, and says so to assistive technology. The
    /// glyph stays an arrow: it is still the button that takes what was typed.
    private var sendButton: some View {
        Button {
            if chat.pendingQuestion != nil { answer() }
            else if chat.draftCommand != nil { run() }
            else { send(mode: .auto) }
        } label: {
            Image(systemName: "arrow.up")
                .font(.body.weight(.semibold))
                .foregroundStyle(Theme.onAccent)
                .frame(width: primaryTouch, height: primaryTouch)
                .background(Theme.accent, in: Circle())
        }
        .buttonStyle(.plain)
        .disabled(!chat.canSend)
        .opacity(chat.canSend ? 1 : 0.4)
        .contextMenu { sendMenu }
        .accessibilityLabel(primaryAction)
        .accessibilityIdentifier("composer.send")
    }

    /// What the one primary in the row does right now. The glyph never changes —
    /// it is still the button that takes what was typed — but its name does, so
    /// a screen reader is never told Send where a command would run (A20, A27).
    private var primaryAction: String {
        if chat.pendingQuestion != nil { return L10n.string("Answer") }
        return chat.draftCommand != nil ? L10n.string("Run") : L10n.string("Send")
    }

    private var isDictating: Bool { voice?.voice.phase.isBusy == true }

    /// `docs/DESIGN.md` § "The composer" → **While dictation runs, the field
    /// follows the words**: the field keeps its last line in view for as long
    /// as words are landing in it — while listening and through the finishing
    /// spinner — and never while it is being typed in, where the caret already
    /// keeps itself visible. Asking for the microphone writes nothing yet, so
    /// it is not one of the two.
    private var dictationIsWriting: Bool {
        guard let phase = voice?.voice.phase else { return false }
        return phase == .listening || phase == .finishing
    }

    /// A control that cannot act is not shown at all. A terminal session takes
    /// no bytes, and neither does an attachment whose device did not report
    /// `shared_attachments` (amendment A11), so the `+` goes rather than
    /// standing there dimmed with a caption under the field explaining it.
    @ViewBuilder
    private var attachControl: some View {
        if chat.allowsAttachments, agent?.supports(.attachments) == true {
            Menu {
                Button { showsFileImporter = true } label: { Label("Files", systemImage: "folder") }
                // `docs/DESIGN.md` § "The composer" → **Attachments are named
                // for what they are**: "The Camera item is offered only where
                // a camera exists, and a refused camera gets the same one line
                // as any denied permission." Presenting the picker where there
                // is no camera raises rather than refusing.
                if Camera.exists {
                    Button { openCamera() } label: { Label("Camera", systemImage: "camera") }
                }
                Button { showsPhotos = true } label: { Label("Photos", systemImage: "photo") }
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

    /// The send modes belong to a message. A command runs between turns and has
    /// neither a queue nor a turn to interrupt, so the menu is not offered for
    /// one (A27).
    @ViewBuilder
    private var sendMenu: some View {
        if chat.draftCommand != nil {
            EmptyView()
        } else {
            sendModes
        }
    }

    @ViewBuilder
    private var sendModes: some View {
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
                    ModelCardChip(chat: chat, agent: agent)
                    permissionChip
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
                         ? L10n.string("Auto") : languageName(model.settings.voiceLanguage))
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

    /// What the session may do, as a plain list of the agent's own modes with
    /// the current one marked and nothing else on it. What runs and how hard
    /// is the model card's; this is the chip after it.
    ///
    /// Amendment A25: an agent with no permission system (pi) lists no modes,
    /// and the chip is not drawn at all. Nothing is greyed out and nothing is
    /// explained — an absent control means the agent has no such setting.
    @ViewBuilder
    private var permissionChip: some View {
        let modes = agent?.permissionModes ?? []
        if !modes.isEmpty {
            Menu {
                Picker("Permissions", selection: permissionBinding) {
                    ForEach(modes) { option in Text(option.label).tag(option.id) }
                }
            } label: {
                Text(agent?.permissionModeLabel(chat.session.permissionMode)
                     ?? L10n.string("Permissions"))
            }
            .menuStyle(.button)
            .buttonStyle(ChipButtonStyle())
            .accessibilityLabel("Permissions")
            .accessibilityValue(agent?.permissionModeLabel(chat.session.permissionMode)
                                ?? chat.session.permissionMode ?? "")
            .accessibilityIdentifier("composer.permissions")
        }
    }

    private var permissionBinding: Binding<String> {
        Binding(get: { chat.session.permissionMode ?? agent?.defaultPermissionMode ?? "" },
                set: { value in Task { await chat.set(permissionMode: value) } })
    }

    /// Amendment A17: what the terminal chose, where its control would have
    /// been. It reads as "Model, Sonnet 4.5 High, set in the terminal" rather
    /// than as a control, so nobody reaches for something that cannot move.
    /// Amendment A21: the tier it is running at rides on the same chip.
    private func terminalChip(_ setting: TerminalSetting) -> some View {
        StaticChip { ModelCardLabel(text: setting.text, isFast: setting.speed != nil) }
            .accessibilityElement()
            .accessibilityLabel(setting.field.label)
            .accessibilityValue(setting.spokenValue)
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
                    .frame(height: pillHeight)
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
        // Amendment A20: the field is the free-text answer while a question is
        // open, and nothing typed here is queued behind it.
        if chat.pendingQuestion != nil { return L10n.string("Your answer") }
        guard chat.isRunning else { return L10n.string("Message") }
        // An agent that steers joins the running turn, whoever started it, so
        // it never says the message is waiting for anything.
        if chat.steersRunningTurn { return L10n.string("Message · will steer the turn") }
        return L10n.string(chat.isAttached ? "Message · sent when the terminal is idle"
                                           : "Message · will be queued")
    }

    /// `docs/DESIGN.md` § "The composer" → **A draft belongs to its session**:
    /// "A refused send returns everything to the composer — the words and the
    /// attachments — so a second tap sends what the first one meant to."
    ///
    /// The store returns the words and says so; the files are the view's, so
    /// they come back here. A send that was accepted, or whose outcome is
    /// unknown, keeps them — the pending record holds the bytes, and Retry
    /// re-sends the same message rather than the words without their files.
    private func send(mode: SendMode) {
        let outgoing = attachments
        attachments = []
        attachmentError = nil
        Task {
            switch await chat.send(mode: mode, attachments: outgoing) {
            case .empty, .refused:
                // Nothing is on its way, so nothing was taken from the
                // composer — unless newer files were attached while the
                // request was out, which are the person's and not ours.
                if !outgoing.isEmpty, attachments.isEmpty { attachments = outgoing }
            case .accepted, .uncertain:
                // Nothing is waiting any more, so the next photo is photo-1.jpg.
                if attachments.isEmpty { photosAttached = 0 }
            }
            await model.saveDraft()
        }
    }

    /// Amendment A27: the first word names a command, so the same button runs it
    /// instead of sending the words. Attachments are left where they are: a
    /// command carries none, and a pill the user added is not ours to discard.
    private func run() {
        Task {
            await chat.runCommand()
            await model.saveDraft()
        }
    }

    /// Amendment A20: the draft is the free-text answer to the question the card
    /// is still waiting on, and goes with whatever was chosen on the card.
    private func answer() {
        Task {
            await chat.answerDraft()
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
        let session = InlineVoiceDraftSession(platform: backend.platform, isPreview: backend.isScripted)
        // Amendment A29: the objects, not this view, so the callback outlives
        // the body that installed it.
        let appModel = model
        let store = chat
        session.onDictationFinished = { span in Self.polish(span, model: appModel, chat: store) }
        voice = session
    }

    /// Amendment A29: the words are in the field already. This asks the
    /// gateway's model to say the same thing cleanly, and only where the
    /// gateway has one, the person has turned it on, and a model is chosen.
    private static func polish(_ span: DictationSpan, model: AppModel, chat: ChatStore) {
        guard model.connection.polish.enabled, model.settings.polishEnabled,
              !model.settings.polishModel.isEmpty, let api = model.connection.api else { return }
        chat.polishService = { request in try await api.polish(request).text }
        chat.polish(span: span, model: model.settings.polishModel,
                    strength: model.settings.polishStrength,
                    language: model.settings.voiceLanguage)
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
        // Amendment A29: a second dictation is a new span, so the note about
        // the last one goes and its answer, if still out, is dropped.
        chat.cancelPolish()
        voice.start(draft: chat.draft, target: target)
    }

    /// Stop listening and keep every word in the field. Sending is still the
    /// ordinary Send button afterwards.
    private func finishDictation() {
        voice?.finish()
    }

    // MARK: - Attachments

    /// The library's own `itemIdentifier` is a `PHAsset` local id — a UUID with
    /// slashes and no extension — so a file named from it reaches the agent as
    /// a path with nothing to say it is an image. `AttachmentNaming` owns the
    /// rule instead.
    private func ingest(_ items: [PhotosPickerItem]) async {
        photoItems = []
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            photosAttached += 1
            addPhoto(data, name: AttachmentNaming.libraryPhoto(photosAttached))
        }
    }

    /// A camera the app may not use gets the same one line any denied
    /// permission gets, and the picker is never presented behind it.
    private func openCamera() {
        Task {
            guard await Camera.requestAccess() == .allowed else {
                attachmentError = L10n.string("Allow camera access in Settings, or attach a photo instead.")
                return
            }
            showsCamera = true
        }
    }

    /// Every photo goes through the same downscale: a full-resolution camera
    /// JPEG would be rejected at the 6 MB limit with no way to shrink it.
    private func addPhoto(_ data: Data, name: String) {
        do {
            add(data: try PhotoPreparation.jpeg(from: data), name: name, mime: "image/jpeg")
        } catch {
            attachmentError = (error as? LocalizedError)?.errorDescription
                ?? L10n.string("That image could not be attached.")
        }
    }

    private func ingest(files result: Result<[URL], any Error>) async {
        guard case .success(let urls) = result else { return }
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            // Check the size before reading: a large video would spike memory
            // and could be jetsammed before the guard below ever ran.
            guard PickedFile.size(of: url) <= RequestLimits.maxAttachmentBytes else {
                attachmentError = L10n.string("%@ is larger than 6 MB.", url.lastPathComponent)
                continue
            }
            // Eagerly, inside the scope this loop is about to release: a
            // mapping outliving its scoped access faults with `SIGBUS`.
            guard let data = try? PickedFile.read(url) else { continue }
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            add(data: data, name: url.lastPathComponent, mime: mime)
        }
    }

    /// The limits are checked here so a rejected message never costs a draft.
    private func add(data: Data, name: String, mime: String) {
        guard attachments.count < RequestLimits.maxAttachments else {
            attachmentError = L10n.string("You can attach at most %lld files to one message.",
                                          RequestLimits.maxAttachments)
            return
        }
        guard data.count <= RequestLimits.maxAttachmentBytes else {
            attachmentError = L10n.string("%@ is larger than 6 MB.", name)
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
