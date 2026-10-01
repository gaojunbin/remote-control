package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.state.DictationPolish
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.win.voice.DictationDraft
import com.junbingao.remotecontrol.win.voice.DictationPolishState
import com.junbingao.remotecontrol.win.voice.DictationPolishing
import com.junbingao.remotecontrol.win.voice.PolishSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The Mac's `ComposerModel+Voice.swift`: dictation into the field, and polish of what it left (A29).

/** How long the one line about a failed polish stays before it goes. */
val polishNoteDuration: Duration = 5.seconds

fun ComposerModel.startVoice() {
    dropPolish()
    dictation = ComposerModel.Dictation(base = text, applied = text)
    voice.start()
}

/**
 * Reaching for the field takes it back: dictation stops and the words it did recognise stay. The
 * field's own programmatic focus — taking a command row, say — is not a reach and ends nothing.
 */
fun ComposerModel.takeFieldBack() {
    if (!voiceBusy) return
    dictation = null
    voice.cancel()
}

/**
 * Dictation writes into this field and nothing else, and only while the field still holds what it
 * last wrote: a keystroke in between is the person's, and theirs wins. Every transcript that changes
 * the field leaves the end of the words in view, through the finishing spinner too.
 */
fun ComposerModel.receiveTranscript(transcript: String, isFinal: Boolean) {
    val run = dictation ?: return
    if (text != run.applied) return
    val next = DictationDraft.merge(run.base, transcript)
    if (next != run.applied) requestTail()
    dictation = if (isFinal) null else run.copy(applied = next)
    setDraft(next)
    // A29: polishing starts on the last transcript of a dictation, once the words are in the field
    // and the field is the person's again.
    if (isFinal) startPolish(PolishSpan(base = run.base, dictated = transcript))
}

/**
 * A29: the words a dictation just left go to the gateway's model with the conversation the page
 * already shows, and come back said cleanly. Only the dictated span is ever replaced, and only while
 * the field holds it.
 */
fun ComposerModel.startPolish(span: PolishSpan) {
    val choice = host.polishChoice ?: return
    if (!DictationPolish.canPolish(span.dictated)) return
    polishRun += 1
    val run = polishRun
    polish = DictationPolishState.Polishing
    val request = DictationPolishing.request(span, model = choice.model, strength = choice.strength,
                                             context = DictationPolish.context(chat.timeline))
    polishTask = host.tasks.launch {
        val answer = try {
            host.polish(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (run == polishRun) polishFailed()
            return@launch
        }
        if (run != polishRun) return@launch
        val next = DictationPolishing.applyPolished(current = text, span = span, polished = answer)
        if (next == null) {
            polish = DictationPolishState.Idle
            return@launch
        }
        setDraft(next)
        polish = DictationPolishState.Polished(span = span, text = answer.trimmed)
    }
}

/**
 * A send, an edit or a new dictation ends a polish run: an answer whose run is no longer current is
 * dropped, because the words on screen are the person's, not a late model's.
 */
fun ComposerModel.dropPolish() {
    polishRun += 1
    polishTask?.cancel()
    polishTask = null
    polishNoteTimer?.cancel()
    if (polish != DictationPolishState.Idle) polish = DictationPolishState.Idle
}

/** Put the dictated words back, and take the note away with them. */
fun ComposerModel.undoPolish() {
    val polished = polish as? DictationPolishState.Polished ?: return
    DictationPolishing.undoPolished(current = text, span = polished.span, polished = polished.text)?.let(::setDraft)
    dropPolish()
}

/**
 * The one line about a failed polish says what happened and then goes away; the words it is about
 * are in the field, where they always were.
 */
private fun ComposerModel.polishFailed() {
    polish = DictationPolishState.Failed
    polishNoteTimer?.cancel()
    polishNoteTimer = host.tasks.launch {
        delay(polishNoteDuration)
        if (polish == DictationPolishState.Failed) polish = DictationPolishState.Idle
    }
}
