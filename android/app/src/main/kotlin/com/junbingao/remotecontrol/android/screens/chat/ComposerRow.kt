package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.attachments.Camera
import com.junbingao.remotecontrol.android.design.Bar
import com.junbingao.remotecontrol.android.design.EffortGauge
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.design.PromptShield
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.WorkingCircle
import com.junbingao.remotecontrol.android.design.scaledMetric
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.screens.chat.voice.InlineVoiceDraftSession
import com.junbingao.remotecontrol.android.screens.chat.voice.VoiceButton
import com.junbingao.remotecontrol.android.screens.chat.voice.VoiceListeningControls
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.Menu
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.contextMenu
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.ComposerControl
import com.junbingao.remotecontrol.core.state.ComposerPrimarySlot
import com.junbingao.remotecontrol.core.state.TerminalSetting
import com.junbingao.remotecontrol.core.state.allowsAttachments
import com.junbingao.remotecontrol.core.state.allowsModelCardChanges
import com.junbingao.remotecontrol.core.state.controlRow
import com.junbingao.remotecontrol.core.state.offersPermissionPicker
import com.junbingao.remotecontrol.core.state.terminalSetting

/**
 * Everything under the field, on one row — and while dictation runs, the level meter, the elapsed
 * time and the one button, Done, in place of all of it.
 *
 * Amendment A29: once the transcript is final the ordinary row comes back around the spinner,
 * which keeps the slot until the model has answered. What the slot holds is `ComposerPrimarySlot`'s
 * (`docs/DESIGN.md` § "The composer" → **Done becomes a spinner, and the spinner becomes Send**);
 * both rows read it from here, so the slot never disagrees with itself across the swap.
 */
@Composable
internal fun ControlsRow(
    chat: ChatStore,
    state: ComposerState,
    agent: AgentInfo?,
    voice: InlineVoiceDraftSession,
    showsQueue: () -> Unit,
    startDictation: () -> Unit,
    openPhotos: () -> Unit,
    openFiles: () -> Unit,
    openCamera: () -> Unit,
) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    val slot = ComposerPrimarySlot.of(voice = voice.voice.phase, polish = chat.polishPhase, returning = chat.isReturningEdit)
    if (voice.voice.phase.isBusy) {
        VoiceListeningControls(voice, slot) { voice.finish() }
        return
    }
    val primaryTouch = scaledMetric(Theme.Touch.primary, SystemFont.body)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = primaryTouch),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The two quiet icons read as one group, so they sit against each other rather than spread
        // across the row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AttachControl(chat, agent, openPhotos, openFiles, openCamera)
            VoiceButton(voice, enabled = !(chat.isReadOnly || chat.isReturningEdit), start = startDictation)
        }
        SessionControls(chat, agent, showsQueue, Modifier.weight(1f))
        Crossfade(slot == ComposerPrimarySlot.working, animationSpec = tween(if (reduceMotion) 0 else 200, easing = EaseInOut), label = "primary slot") { working ->
            if (working) {
                WorkingCircle(L10n.string(if (chat.isReturningEdit) "Sending…" else "Polishing…"))
            } else {
                SendButton(chat, agent, state)
            }
        }
    }
}

/**
 * Amendment A20: while a question is pending the one primary in the row answers it instead of
 * sending, and says so to assistive technology. The glyph stays an arrow: it is still the button
 * that takes what was typed. A long press offers the other ways a message can go.
 */
@Composable
private fun SendButton(chat: ChatStore, agent: AgentInfo?, state: ComposerState) {
    val side = scaledMetric(Theme.Touch.primary, SystemFont.body)
    val canSend = chat.canSend
    // The iPhone fades the circle and the arrow each on its own, as SwiftUI's opacity does, so a
    // button that cannot send shows its arrow as a ghost in the grey rather than as a white one:
    // 0.4 from the composer, and half again for a disabled plain button.
    val strength = if (canSend) 1f else 0.4f * DisabledLook.opacity
    Box(
        Modifier
            .size(side)
            .background(Theme.accent.copy(alpha = strength), CircleShape)
            .then(if (canSend) Modifier.contextMenu(sendMenu(chat, agent, state), onClick = { state.primary() }) else Modifier)
            .semantics {
                contentDescription = ComposerWords.primaryAction(chat)
                role = Role.Button
                if (!canSend) disabled()
            }
            .testTag("composer.send"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Sf.arrowUp, font = SystemFont.body.weight(FontWeight.SemiBold), tint = Theme.onAccent.copy(alpha = strength))
    }
}

/**
 * The send modes belong to a message. A command runs between turns and has neither a queue nor a
 * turn to interrupt, so the menu is not offered for one (A27). The menu opens upward, so the first
 * of them stands nearest the button, as iOS orders a menu that opens above its source.
 */
private fun sendMenu(chat: ChatStore, agent: AgentInfo?, state: ComposerState): List<MenuItem> {
    if (chat.draftCommand != null) return emptyList()
    val modes = buildList {
        if (agent?.supports(AgentCapability.queue) == true) {
            add(MenuItem.Action(L10n.string("Queue"), symbol = Sf.textLineFirstAndArrowtriangleForward) { state.send(SendMode.queue) })
        }
        if (agent?.supports(AgentCapability.interrupt) == true && (!chat.isAttached || agent.sharedInterrupt)) {
            add(MenuItem.Action(L10n.string("Interrupt & send"), symbol = Sf.bolt) { state.send(SendMode.interrupt) })
        }
    }
    return modes.reversed()
}

/**
 * A control that cannot act is not shown at all. A terminal session takes no bytes, and neither
 * does an attachment whose device did not report `shared_attachments` (amendment A11), so the `+`
 * goes rather than standing there dimmed with a caption under the field explaining it.
 *
 * The menu presents nothing itself: each item sets the composer's own picker going, because a
 * presenter built inside a menu leaves with the menu. The Camera item is offered only where a
 * camera exists (`docs/DESIGN.md` § "The composer" → **Attachments are named for what they are**).
 * The menu opens upward, so Files, the first of them, stands nearest the `+`.
 */
@Composable
private fun AttachControl(chat: ChatStore, agent: AgentInfo?, openPhotos: () -> Unit, openFiles: () -> Unit, openCamera: () -> Unit) {
    if (!chat.allowsAttachments || agent?.supports(AgentCapability.attachments) != true) return
    val context = LocalContext.current
    val items = buildList {
        add(MenuItem.Action(L10n.string("Files"), symbol = Sf.folder, action = openFiles))
        if (Camera.exists(context)) add(MenuItem.Action(L10n.string("Camera"), symbol = Sf.camera, action = openCamera))
        add(MenuItem.Action(L10n.string("Photos"), symbol = Sf.photo, action = openPhotos))
    }.reversed()
    Foreground(Theme.ink) {
        Menu(
            items,
            Modifier
                .disabledLook(!chat.isReturningEdit)
                .semantics { contentDescription = L10n.string("Add an attachment") }
                .testTag("composer.attach"),
            enabled = !chat.isReturningEdit,
        ) {
            Box(Modifier.size(Theme.Touch.minimum), contentAlignment = Alignment.Center) { Icon(Sf.plus) }
        }
    }
}

/**
 * The middle of the control row, in `ComposerControl`'s order: what waits behind the turn, how you
 * speak, what runs, what it may do (A43, A44). Each is an icon on a 44-point target. They scroll
 * sideways rather than wrap, so the row keeps its height however many of them there are, and the
 * last few points fade rather than cut flat against Send, which is how a row says there is more.
 *
 * Amendment A17: the session's own settings are offered only where they can be changed from here.
 * On a session a terminal holds they are shown instead, in the same positions and behind the same
 * icons, as menus with nothing to choose. Amendment A40: one setting at a time, because a shared
 * Claude session is typed into for the model and the effort but has no command for the permission
 * mode. Each slot is a control or a value, never a control that fails when tapped.
 */
@Composable
private fun SessionControls(chat: ChatStore, agent: AgentInfo?, showsQueue: () -> Unit, modifier: Modifier) {
    val model = LocalAppModel.current
    // The iPhone masks the last points to clear. Here the composer's own background — the bar
    // over the page — is laid over them instead, which looks the same on the one background the
    // row stands on and needs no layer of its own: a layer composited offscreen draws nothing of
    // a control added under it after it was first drawn.
    val behind = Bar.material.compositeOver(Theme.canvas)
    Box(
        modifier.drawWithContent {
            drawContent()
            val fade = ComposerMetrics.controlsFade.toPx()
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, behind), startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f),
                size = Size(fade, size.height),
            )
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            // Each control is itself across a change of the row, as `ForEach` keeps it, so Up next
            // arriving in front never makes the others over.
            for (control in chat.controlRow(backend = model.voiceBackendInEffect)) {
                key(control) {
                    when (control) {
                        ComposerControl.upNext -> UpNextControl(chat.session.queued, showsQueue)
                        ComposerControl.dictationLanguage -> DictationLanguageControl(model.settings)
                        ComposerControl.modelCard -> ModelSlot(chat, agent)
                        ComposerControl.permissions -> PermissionSlot(chat, agent)
                    }
                }
            }
        }
    }
}

/** What runs and how hard: the card where this app may change it, the value the terminal set where it may not (A17, A40) — the same gauge either way. */
@Composable
private fun ModelSlot(chat: ChatStore, agent: AgentInfo?) {
    if (chat.allowsModelCardChanges) {
        ModelCardChip(chat, agent)
        return
    }
    val setting = chat.terminalSetting(TerminalSetting.Field.modelCard) ?: return
    TerminalValueControl(setting) {
        EffortGauge(position = agent?.effortPosition(chat.session.effort), isFast = setting.speed != null)
    }
}

/**
 * The same two ways for the permission mode, which on a shared Claude session is the one the
 * terminal keeps. Amendment A25: an agent with no permission system (pi) lists no modes, and
 * nothing is drawn at all — nothing greyed out and nothing explained.
 */
@Composable
private fun PermissionSlot(chat: ChatStore, agent: AgentInfo?) {
    if (chat.offersPermissionPicker) {
        PermissionControl(chat, agent)
        return
    }
    val setting = chat.terminalSetting(TerminalSetting.Field.permissionMode) ?: return
    TerminalValueControl(setting) { PromptShield() }
}

internal object ComposerMetrics {
    /** How far the control row fades out against Send. */
    val controlsFade = 18.dp
}
