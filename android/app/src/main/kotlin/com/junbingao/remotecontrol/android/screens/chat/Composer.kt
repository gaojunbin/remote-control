package com.junbingao.remotecontrol.android.screens.chat

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.junbingao.remotecontrol.android.attachments.AttachmentNaming
import com.junbingao.remotecontrol.android.attachments.CameraAccess
import com.junbingao.remotecontrol.android.attachments.rememberCameraAccessRequest
import com.junbingao.remotecontrol.android.attachments.rememberCameraCapture
import com.junbingao.remotecontrol.android.attachments.rememberDocumentPicker
import com.junbingao.remotecontrol.android.attachments.rememberPhotoPicker
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.barBackground
import com.junbingao.remotecontrol.android.permissions.rememberPermissionRequest
import com.junbingao.remotecontrol.android.screens.chat.voice.InlineVoiceDraftSession
import com.junbingao.remotecontrol.android.screens.chat.voice.InlineVoiceInput
import com.junbingao.remotecontrol.android.screens.chat.voice.SpeechBackend
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.voice.GatewaySpeechRecognizer
import com.junbingao.remotecontrol.core.protocol.RequestLimits
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.VoiceBackend
import com.junbingao.remotecontrol.core.state.sendBlockReason
import com.junbingao.remotecontrol.core.state.steersRunningTurn
import com.junbingao.remotecontrol.core.state.VoiceDraftTarget
import com.junbingao.remotecontrol.core.state.VoiceInputPhase
import kotlinx.coroutines.launch

/**
 * The message bar.
 *
 * Two rows and never three. The field owns the first one and grows with what is in it; everything
 * else sits on the second, in one order: the `+` and the microphone, then the session's controls,
 * then Send against the trailing edge. The controls are icons (A44) and scroll sideways when they
 * do not fit, because a control row that wraps costs the transcript a line every time one is added.
 *
 * Enter inserts a newline, and sending is always an explicit, separate tap — dictation fills the
 * draft and stops there, and a word the keyboard is still composing is never sent. [bottomInset]
 * is how far the bar's material reaches under the system's own bar, or the keyboard.
 */
@Composable
internal fun Composer(chat: ChatStore, showsQueue: () -> Unit, bottomInset: Dp, modifier: Modifier = Modifier) {
    val model = LocalAppModel.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(chat) { ComposerState(chat, model) }
    val agent = model.agent(chat.session)
    val target = VoiceDraftTarget(account = model.connection.account, deviceID = chat.deviceID, sessionID = chat.sessionID)

    val microphone = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO)
    val cameraAccess = rememberCameraAccessRequest()
    val photos = rememberPhotoPicker(RequestLimits.maxAttachments) { uris -> scope.launch { ComposerAttachments.ingestPhotos(state, context, uris) } }
    val files = rememberDocumentPicker { uris -> scope.launch { ComposerAttachments.ingestFiles(state, context, uris) } }
    val camera = rememberCameraCapture(
        onCapture = { bytes -> scope.launch { ComposerAttachments.addPhoto(state, bytes, AttachmentNaming.cameraPhoto) } },
        onFailure = { state.attachmentError = L10n.string("That image could not be attached.") },
    )

    // Releasing a listening session would leave the microphone live with no screen to stop it, so
    // a dictation is reset before its backend is replaced, and on the way out.
    val backend = model.voiceBackendInEffect
    val language = model.settings.dictationLanguage
    val made = remember(chat, backend, language) {
        SpeechBackend.make(model.settings, model.connection, model.options, context, microphone, scope)
    }
    val voice = remember(made) {
        InlineVoiceDraftSession(made.platform, isPreview = made.isScripted, scope = scope).also { session ->
            // Amendment A29: the objects, not this composable, so the callback outlives the pass
            // that installed it.
            session.onDictationFinished = { span -> state.polish(span) }
        }
    }
    DisposableEffect(voice) {
        onDispose {
            voice.reset()
            (made.platform as? GatewaySpeechRecognizer)?.release()
        }
    }

    // The composer knows about the connection; the chat store does not.
    val online = model.device(chat.session)?.online ?: false
    LaunchedEffect(chat, model.connection.phase, online, agent) {
        chat.canReachGateway = model.connection.phase.canReachGateway || model.isDemo
        chat.deviceOnline = online
        chat.agent = agent
    }
    // Amendment A27: the list is fetched when the conversation opens and asked for again the moment
    // `/` is typed, if the last answer has gone stale or was empty. Only the first slash asks; the
    // letters after it filter what is already on screen.
    val isCommandDraft = chat.draft.startsWith("/")
    var wasCommandDraft by remember(chat) { mutableStateOf(isCommandDraft) }
    LaunchedEffect(isCommandDraft) {
        if (isCommandDraft && !wasCommandDraft) model.perform { chat.refreshCommands() }
        wasCommandDraft = isCommandDraft
    }
    var lastEdit by remember(chat) { mutableStateOf(chat.queuedEdit) }
    LaunchedEffect(chat.queuedEdit) {
        state.followEdit(before = lastEdit, now = chat.queuedEdit)
        lastEdit = chat.queuedEdit
    }

    val dictating = voice.voice.phase.isBusy
    Column(
        modifier
            .fillMaxWidth()
            .barBackground()
            .padding(horizontal = Theme.Space.page)
            .padding(top = Theme.Space.small, bottom = Theme.Space.small + bottomInset),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.small),
    ) {
        // Amendment A27: the terminal's `/` menu, over the keyboard. It is drawn only where the
        // device says the agent takes commands and only while what has been typed matches at least
        // one of them, so `/` is an ordinary character on an empty list.
        if (chat.commandRows.isNotEmpty()) CommandPanel(chat) { state.isWriting = true }
        NoticeLine(chat, state, voice, usesGateway = backend == VoiceBackend.gateway)
        EditingStrip(chat, state)
        if (state.attachments.isNotEmpty()) AttachmentStrip(state)
        PromptField(
            chat,
            state,
            placeholder = ComposerWords.placeholder(chat),
            // `docs/DESIGN.md` § "The composer" → **While dictation runs, the field follows the
            // words**: the field keeps its last line in view for as long as words are landing in it
            // — while listening and through the finishing spinner — and never while it is being
            // typed in, where the caret already keeps itself visible. Asking for the microphone
            // writes nothing yet, so it is not one of the two.
            followsTail = voice.voice.phase == VoiceInputPhase.listening || voice.voice.phase == VoiceInputPhase.finishing,
            dictating = dictating,
            takeOver = {
                voice.finish()
                state.isWriting = true
            },
        )
        PolishNote(chat)
        ControlsRow(
            chat = chat,
            state = state,
            agent = agent,
            voice = voice,
            showsQueue = showsQueue,
            startDictation = { state.startDictation(voice, target) },
            openPhotos = { photos.launch() },
            openFiles = { files.launch() },
            // A camera the app may not use gets the same one line any denied permission gets, and
            // the camera is never opened behind it.
            openCamera = {
                scope.launch {
                    if (cameraAccess.request() != CameraAccess.allowed) {
                        state.attachmentError = L10n.string("Allow camera access in Settings, or attach a photo instead.")
                        return@launch
                    }
                    camera.launch()
                }
            },
        )
    }
    InlineVoiceInput(voice, chat.draft, onDraft = { chat.draft = it }, target = target)
}

/** The words the bar shows that it does not keep. */
internal object ComposerWords {
    fun placeholder(chat: ChatStore): String {
        chat.sendBlockReason?.let { return it }
        // Amendment A20: the field is the free-text answer while a question is open, and nothing
        // typed here is queued behind it.
        if (chat.pendingQuestion != null) return L10n.string("Your answer")
        if (!chat.isRunning) return L10n.string("Message")
        // An agent that steers joins the running turn, whoever started it, so it never says the
        // message is waiting for anything.
        if (chat.steersRunningTurn) return L10n.string("Message · will steer the turn")
        return L10n.string(if (chat.isAttached) "Message · sent when the terminal is idle" else "Message · will be queued")
    }

    /**
     * What the one primary in the row does right now. The glyph never changes — it is still the
     * button that takes what was typed — but its name does, so a screen reader is never told Send
     * where a command would run (A20, A27), or where an edited message goes back into the line (A43).
     */
    fun primaryAction(chat: ChatStore): String {
        if (chat.pendingQuestion != null) return L10n.string("Answer")
        chat.editingSendLabel?.let { return it }
        return if (chat.draftCommand != null) L10n.string("Run") else L10n.string("Send")
    }
}
