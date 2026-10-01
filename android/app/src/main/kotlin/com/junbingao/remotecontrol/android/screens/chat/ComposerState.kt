package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.junbingao.remotecontrol.android.screens.chat.voice.InlineVoiceDraftSession
import com.junbingao.remotecontrol.android.shell.AppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.OutboundAttachment
import com.junbingao.remotecontrol.core.protocol.RequestLimits
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.DictationLanguage
import com.junbingao.remotecontrol.core.state.DictationSpan
import com.junbingao.remotecontrol.core.state.QueuedEdit
import com.junbingao.remotecontrol.core.state.VoiceDraftTarget

/**
 * What the message bar holds of its own — the files waiting to go, the one line about them, whether
 * the field has the keyboard — and what its buttons do with it: the iPhone's `Composer` `@State`
 * and its actions, apart from what the bar draws.
 *
 * The words are the store's ([ChatStore.draft]); the files are the composer's, so a refused send
 * puts them back here and an edit of a queued message sets them aside here.
 */
class ComposerState(private val chat: ChatStore, private val model: AppModel) {
    var attachments: List<OutboundAttachment> by mutableStateOf(emptyList())
    var attachmentError: String? by mutableStateOf(null)
    var isWriting: Boolean by mutableStateOf(false)

    /** How many photos this composer has taken from the library, so each one is named for its place in the order they were attached in. */
    var photosAttached = 0

    /** Amendment A43: the files the composer held when a queued message was taken out to be edited. They come back when the words the store set aside do. */
    var attachmentsAside: List<OutboundAttachment> = emptyList()
        private set

    /**
     * `docs/DESIGN.md` § "The composer" → **A draft belongs to its session**: "A refused send
     * returns everything to the composer — the words and the attachments — so a second tap sends
     * what the first one meant to."
     *
     * The store returns the words and says so; the files are the composer's, so they come back
     * here. A send that was accepted, or whose outcome is unknown, keeps them — the pending record
     * holds the bytes, and Retry re-sends the same message rather than the words without their files.
     */
    fun send(mode: SendMode) {
        val outgoing = attachments
        val store = chat
        attachments = emptyList()
        attachmentError = null
        model.perform {
            when (store.send(mode = mode, attachments = outgoing)) {
                // Nothing is on its way, so nothing was taken from the composer — unless newer
                // files were attached while the request was out, which are the person's and not ours.
                ChatStore.SendOutcome.empty, ChatStore.SendOutcome.refused ->
                    if (outgoing.isNotEmpty() && attachments.isEmpty()) attachments = outgoing
                // Nothing is waiting any more, so the next photo is photo-1.jpg — unless files set
                // aside by an edit are about to come back.
                ChatStore.SendOutcome.accepted, ChatStore.SendOutcome.uncertain ->
                    if (attachments.isEmpty() && attachmentsAside.isEmpty()) photosAttached = 0
            }
            saveDraft()
        }
    }

    /** Amendment A43: Cancel puts the original words back into the line; the store brings back what the field held once they are there. */
    fun cancelEdit() {
        val store = chat
        model.perform {
            store.cancelEdit()
            saveDraft()
        }
    }

    /**
     * Amendment A43: the files follow the words. When an edit begins, the pills the composer held
     * go aside with the draft and the field takes the keyboard, caret after the message's last word;
     * when it ends, they come back, in front of anything attached meanwhile — a file attached while
     * editing went with the edited message, and one Cancel left is kept.
     */
    fun followEdit(before: QueuedEdit?, now: QueuedEdit?) {
        when {
            before == null && now != null -> {
                attachmentsAside = attachments
                attachments = emptyList()
                attachmentError = null
                isWriting = true
            }
            before != null && now == null -> {
                attachments = (attachmentsAside + attachments).take(RequestLimits.maxAttachments)
                attachmentsAside = emptyList()
            }
        }
    }

    /**
     * Amendment A27: the first word names a command, so the same button runs it instead of sending
     * the words. Attachments are left where they are: a command carries none, and a pill the user
     * added is not ours to discard.
     */
    fun run() {
        val store = chat
        model.perform {
            store.runCommand()
            saveDraft()
        }
    }

    /** Amendment A20: the draft is the free-text answer to the question the card is still waiting on, and goes with whatever was chosen on the card. */
    fun answer() {
        val store = chat
        model.perform {
            store.answerDraft()
            saveDraft()
        }
    }

    /** What the one primary in the row does right now. */
    fun primary() {
        when {
            chat.pendingQuestion != null -> answer()
            chat.draftCommand != null -> run()
            else -> send(SendMode.auto)
        }
    }

    // Dictation

    fun startDictation(voice: InlineVoiceDraftSession?, target: VoiceDraftTarget) {
        voice ?: return
        isWriting = false
        // Amendment A29: a second dictation is a new span, so the note about the last one goes
        // and its answer, if still out, is dropped.
        chat.cancelPolish()
        voice.start(draft = chat.draft, target = target)
    }

    /**
     * Amendment A29: the words are in the field already. This asks the gateway's model to say the
     * same thing cleanly, and only where the gateway has one, the person has turned it on, and a
     * model is chosen. Amendment A44: the hint is `auto` for words the gateway transcribed and the
     * language the phone listened for otherwise.
     */
    fun polish(span: DictationSpan) {
        val api = model.connection.api ?: return
        val settings = model.settings
        if (!model.connection.polish.enabled || !settings.polishEnabled || settings.polishModel.isEmpty()) return
        chat.polishService = { request -> api.polish(request).text }
        chat.polish(
            span = span,
            model = settings.polishModel,
            strength = settings.polishStrength,
            language = DictationLanguage.polishHint(backend = model.voiceBackendInEffect, listening = settings.voiceLanguage),
        )
    }

    // Attachments

    /** The limits are checked here so a rejected message never costs a draft. */
    fun add(data: ByteArray, name: String, mime: String) {
        if (attachments.size >= RequestLimits.maxAttachments) {
            attachmentError = L10n.string("You can attach at most %lld files to one message.", RequestLimits.maxAttachments)
            return
        }
        if (data.size > RequestLimits.maxAttachmentBytes) {
            attachmentError = L10n.string("%@ is larger than 6 MB.", name)
            return
        }
        attachmentError = null
        attachments = attachments + OutboundAttachment(name = name, mime = mime, data = data)
    }

    fun remove(attachment: OutboundAttachment) {
        attachments = attachments.filter { it.id != attachment.id }
    }
}
