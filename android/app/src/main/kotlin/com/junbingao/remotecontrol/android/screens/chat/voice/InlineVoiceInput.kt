package com.junbingao.remotecontrol.android.screens.chat.voice

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.WorkingCircle
import com.junbingao.remotecontrol.android.design.monospacedDigit
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.launch.LaunchOptions
import com.junbingao.remotecontrol.android.permissions.PermissionRequest
import com.junbingao.remotecontrol.android.screens.chat.WorkingSlot
import com.junbingao.remotecontrol.android.screens.chat.disabledLook
import com.junbingao.remotecontrol.android.security.SceneRule
import com.junbingao.remotecontrol.android.security.currentSceneState
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.voice.GatewaySpeechRecognizer
import com.junbingao.remotecontrol.android.voice.MicrophoneCapture
import com.junbingao.remotecontrol.android.voice.SpeechInputFailure
import com.junbingao.remotecontrol.android.voice.SpeechInputPlatform
import com.junbingao.remotecontrol.android.voice.SystemSpeechRecognizer
import com.junbingao.remotecontrol.core.state.ComposerPrimarySlot
import com.junbingao.remotecontrol.core.state.ConnectionStore
import com.junbingao.remotecontrol.core.state.SettingsStore
import com.junbingao.remotecontrol.core.state.VoiceBackend
import com.junbingao.remotecontrol.core.state.VoiceDraftTarget
import com.junbingao.remotecontrol.core.state.VoiceInputPhase
import com.junbingao.remotecontrol.core.state.inEffect
import com.junbingao.remotecontrol.core.transport.GatewayHTTPClient
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * Chooses the dictation backend from settings and what the gateway offers.
 *
 * The scripted platform is only reachable behind an explicit launch argument in a debug build
 * ([LaunchOptions.voicePreview]), so a shipping build can never substitute fake speech for the
 * microphone.
 */
object SpeechBackend {
    data class Made(val platform: SpeechInputPlatform, val isScripted: Boolean)

    /**
     * Amendment A44: the backend is `VoiceBackend.inEffect`, the same answer the composer and
     * Settings read. The gateway is told no language; the phone's recogniser is told the one it
     * listens for. [microphone] is the screen's own request for it, and [scope] is where the
     * scripted platform paces its words.
     */
    fun make(
        settings: SettingsStore,
        connection: ConnectionStore,
        options: LaunchOptions,
        context: Context,
        microphone: PermissionRequest,
        scope: CoroutineScope,
    ): Made {
        if (options.voicePreview) return Made(scriptedPlatform(options, scope), isScripted = true)
        if (VoiceBackend.inEffect(settings = settings, connection = connection) == VoiceBackend.gateway) {
            val client = connection.api as? GatewayHTTPClient
            if (client != null) return Made(GatewaySpeechRecognizer(client, microphone, MicrophoneCapture(context)), isScripted = false)
        }
        return Made(SystemSpeechRecognizer(context, settings.speechLocaleIdentifier, microphone), isScripted = false)
    }

    /**
     * The scripted platform as the launch arguments asked for it. `--voice-transcript=long` speaks a
     * dictation that outruns the field, delivered in four partials the way a long one really lands;
     * anything else is the short sentence every other test hears. `--voice-level=` holds it at one
     * input level, so the glow can be pictured at rest, at conversational speech and at the top of
     * its range.
     */
    fun scriptedPlatform(options: LaunchOptions, scope: CoroutineScope): ScriptedSpeechInput {
        val level = options.voiceLevel ?: ScriptedSpeechInput.defaultLevel
        if (options.voiceTranscript != "long") return ScriptedSpeechInput(scope, level = level)
        return ScriptedSpeechInput(scope, transcript = ScriptedSpeechInput.longTranscript, level = level, partials = 4)
    }
}

/** The microphone button in the composer's control row. */
@Composable
internal fun VoiceButton(session: InlineVoiceDraftSession, enabled: Boolean, start: () -> Unit) {
    Button(
        onClick = start,
        modifier = Modifier
            .size(Theme.Touch.minimum)
            .disabledLook(enabled && !session.voice.phase.isBusy)
            .semantics { contentDescription = L10n.string("Dictate a message") }
            .testTag("composer.voice"),
        enabled = enabled && !session.voice.phase.isBusy,
    ) {
        Icon(Sf.mic, tint = Theme.ink)
    }
}

/**
 * What the control row holds while dictation runs: how loud it is, how long it has been listening,
 * and the one way out.
 *
 * There is no "stop and send". Done keeps the transcript in the message field and Send stays the
 * separate, explicit tap it is for anything typed. There is no Cancel either: a dictation nobody
 * wants is Done and then edited or cleared like any other draft, and Done stands where Send stands,
 * at Send's size, because while listening it is the one primary action in the row.
 *
 * The tap on Done is answered at once: the capsule gives way to the spinner in Send's circle, and
 * the meter and the clock stop with it. `ComposerPrimarySlot` is what decides which of the two the
 * slot holds.
 */
@Composable
internal fun VoiceListeningControls(session: InlineVoiceDraftSession, slot: ComposerPrimarySlot, done: () -> Unit) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    var elapsed by remember { mutableStateOf("0:00") }
    val phase by rememberUpdatedState(session.voice.phase)
    // The clock counts how long the microphone was open, so it stops at the moment Done was tapped:
    // nothing is being heard after that.
    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        while (true) {
            delay(1_000)
            if (phase != VoiceInputPhase.listening) continue
            val seconds = ((System.currentTimeMillis() - started) / 1000).toInt()
            elapsed = "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Theme.Touch.primary),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp, 20.dp), contentAlignment = Alignment.Center) {
            VoiceLevelMeter(session.voice.inputLevel, listening = session.voice.phase == VoiceInputPhase.listening)
        }
        Text(
            elapsed,
            Modifier
                .semantics { contentDescription = L10n.string("Listening for %@", elapsed) }
                .testTag("voice.elapsed"),
            style = Theme.mono.monospacedDigit(),
            color = Theme.inkSecondary,
        )
        Spacer(Modifier.weight(1f).widthIn(min = Theme.Space.small))
        // Done while the microphone is live, and the spinner it becomes the moment it is tapped.
        // The slot is `done` while the microphone is still being asked for too, where Done stands
        // and is not live yet; `send` never reaches this row, which is drawn only while dictation
        // is busy.
        Crossfade(slot == ComposerPrimarySlot.working, animationSpec = tween(if (reduceMotion) 0 else 200, easing = EaseInOut), label = "voice slot") { working ->
            if (working) {
                WorkingCircle(L10n.string("Finishing the transcript"), WorkingSlot.identified)
            } else {
                Button(
                    onClick = done,
                    modifier = Modifier.testTag("voice.done"),
                    enabled = session.voice.phase == VoiceInputPhase.listening,
                    style = PrimaryButtonStyle(fullWidth = false),
                ) { Text(L10n.string("Done")) }
            }
        }
    }
}

/** The quiet line above the message field while dictation runs, and the place a failure says what went wrong. */
@Composable
internal fun VoiceStatusLine(session: InlineVoiceDraftSession, usesGateway: Boolean) {
    val failure = session.voice.failure
    val text = when {
        failure != null -> VoiceWords.message(failure)
        session.voice.phase == VoiceInputPhase.requestingPermission -> L10n.string("Getting the microphone ready")
        session.voice.phase == VoiceInputPhase.finishing -> L10n.string("Finishing the transcript")
        usesGateway -> L10n.string("Transcribing on your gateway · edit before sending")
        else -> L10n.string("Transcribing live · edit before sending")
    }
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .testTag("voice.status"),
        style = SystemFont.caption,
        color = if (failure == null) Theme.inkSecondary else Theme.danger,
        lineLimit = 2,
    )
}

internal object VoiceWords {
    /** A failure keeps whatever was recognised, so every message ends with what to do next rather than with the loss. */
    fun message(failure: SpeechInputFailure): String = when (failure) {
        SpeechInputFailure.SpeechPermission -> L10n.string("Allow speech recognition in Settings, or keep typing.")
        SpeechInputFailure.MicrophonePermission -> L10n.string("Allow microphone access in Settings, or keep typing.")
        SpeechInputFailure.Unsupported ->
            L10n.string("No on-device model for this language. Switch to gateway transcription in Settings.")
        SpeechInputFailure.Unavailable -> L10n.string("Transcription is unavailable right now. Keep typing instead.")
        SpeechInputFailure.Recording -> L10n.string("The microphone is unavailable. Try again, or keep typing.")
        SpeechInputFailure.Recognition, SpeechInputFailure.Interrupted ->
            L10n.string("Dictation stopped. What was recognised is in your draft.")
    }
}

/** Five bars driven by the measured input level. */
@Composable
internal fun VoiceLevelMeter(level: Double, listening: Boolean) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (index in 0 until 5) {
            val envelope = 0.45 + 0.55 * sin(index / 4.0 * PI)
            val target = if (listening) 3 + 15 * level.coerceIn(0.0, 1.0) * envelope else 3.0
            val height by animateDpAsState(target.dp, tween(if (reduceMotion) 0 else 120, easing = EaseOut), label = "meter")
            Box(Modifier.size(4.dp, height).background(Theme.ink, CapsuleShape))
        }
    }
}

/**
 * Keeps the draft in step with the live transcript, puts the listening glow around the display,
 * and resets dictation whenever the composer it belongs to changes.
 */
@Composable
internal fun InlineVoiceInput(session: InlineVoiceDraftSession, draft: String, onDraft: (String) -> Unit, target: VoiceDraftTarget) {
    VoiceGlowPresenter(active = session.voice.phase == VoiceInputPhase.listening, level = session.voice.inputLevel)
    val currentDraft by rememberUpdatedState(draft)
    val currentTarget by rememberUpdatedState(target)
    val write by rememberUpdatedState(onDraft)
    LaunchedEffect(session) {
        snapshotFlow { session.voice.transcript to session.voice.phase }.collect {
            session.updateDraft(currentDraft = currentDraft, currentTarget = currentTarget)?.let { write(it) }
            // Amendment A29: the words are in the field now; whether they are then polished is the
            // composer's own decision, taken from this one call.
            session.reportFinishedDictation()
        }
    }
    var lastTarget by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (target == lastTarget) return@LaunchedEffect
        lastTarget = target
        session.reset()
    }
    // Listening runs until Done is tapped, and only leaving the app ends it early: a system dialog,
    // the notification shade and the other half of a split screen only take the app out of the
    // front, and dictation listens through them (`docs/DESIGN.md` § "The composer", Voice).
    val scene = currentSceneState()
    LaunchedEffect(session, scene) {
        val background = SceneRule.isBackground(scene)
        session.voice.setSceneActive(!background, cancelAuthorization = background)
    }
    DisposableEffect(session) { onDispose { session.reset() } }
}
