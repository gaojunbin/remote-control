package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.Divider
import com.junbingao.remotecontrol.android.design.EffortGauge
import com.junbingao.remotecontrol.android.design.StopSlider
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import com.junbingao.remotecontrol.android.system.IndicatorSize
import com.junbingao.remotecontrol.core.protocol.AgentCapability
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentLabel
import com.junbingao.remotecontrol.core.protocol.AgentOption
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.TerminalSetting
import com.junbingao.remotecontrol.core.state.nextSpeed

/**
 * The model name with the effort word after it, as the card's first row draws it. The sizer
 * behind that row stacks this same composable, so the box it measures is the box the words land in.
 */
@Composable
private fun ModelNameLabel(model: String, effort: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
        Text(model, style = Theme.Text.label, color = Theme.ink, lineLimit = 1)
        if (effort != null) Text(effort, style = Theme.Text.meta, color = Theme.inkSecondary, lineLimit = 1)
    }
}

/**
 * Amendment A21: the composer's one control for what runs and how hard.
 *
 * Amendment A44: on the phone it is the gauge — the needle at the session's effort, the bolt while
 * a faster tier is on — and its accessible value says the rest in words
 * (`TerminalSetting.modelCardSpoken`). A tap opens a card over the keyboard holding the speed
 * toggle, the model list and the effort slider. The card stays up until it is dismissed, so several
 * changes can be made in one visit.
 */
@Composable
internal fun ModelCardChip(chat: ChatStore, agent: AgentInfo?) {
    var isOpen by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val pending = chat.isSettingPending
    // Amendment A40: while a change is being typed into a terminal the chip is the waiting
    // control. It takes no second change, and it dims, so a card dismissed mid-wait still says one
    // is in flight.
    Button(
        onClick = { isOpen = true },
        modifier = Modifier
            .alpha(if (pending) 0.5f else 1f)
            .disabledLook(!pending)
            .onGloballyPositioned { anchor = it.boundsInRoot() }
            .semantics {
                contentDescription = L10n.string("Model")
                stateDescription = TerminalSetting.modelCardSpoken(chat.session, agent)
            }
            .testTag("composer.modelCard"),
        enabled = !pending,
    ) {
        ControlGlyph { EffortGauge(position = agent?.effortPosition(chat.session.effort), isFast = chat.session.speed != null) }
    }
    Popover(isOpen, onDismiss = { isOpen = false }, anchor = anchor, edge = PopoverEdge.above) {
        ModelCard(chat, agent)
    }
}

/**
 * `docs/DESIGN.md` § "The model card": the card's name row is as wide as the widest
 * model-and-effort combination the agent offers, so nothing beside it shifts while a level is
 * chosen or a model is picked. The chip in the row is an icon on the phone (A44), which never
 * changes its width.
 *
 * The width is measured, not guessed: every `models × efforts` pair is stacked behind the visible
 * label and drawn in nothing, which keeps the layout and drops the drawing, so the box takes the
 * widest of them.
 */
@Composable
private fun ModelCardSizer(pairs: List<ModelCardSizing.Pair>, content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        for (pair in pairs) {
            ModelNameLabel(pair.model, pair.effort, Modifier.alpha(0f).clearAndSetSemantics { })
        }
        content()
    }
}

/**
 * The combinations a sizer measures. An agent with no efforts or no models measures whatever it
 * has, which is what the row would draw for it.
 */
object ModelCardSizing {
    data class Pair(val model: String, val effort: String?)

    /**
     * Every model label with every effort label after it. The fallback stands in where the agent
     * lists no model, because that is what the row draws there.
     */
    fun pairs(agent: AgentInfo?, model: String): List<Pair> {
        val models = agent?.models?.map { it.label } ?: emptyList()
        val efforts = if (agent?.supports(AgentCapability.effort) == true) agent.efforts.map { it.label } else emptyList()
        val names = models.ifEmpty { listOf(model) }
        if (efforts.isEmpty()) return names.map { Pair(model = it, effort = null) }
        return names.flatMap { name -> efforts.map { Pair(model = name, effort = it) } }
    }
}

/**
 * The card itself: speed and model on the first row, the effort slider on the second, and the
 * model list under them once the name is tapped.
 */
@Composable
private fun ModelCard(chat: ChatStore, agent: AgentInfo?) {
    val model = LocalAppModel.current
    var stop by remember { mutableIntStateOf(0) }
    var showsModels by remember { mutableStateOf(false) }
    val pending = chat.isSettingPending
    // One stop per level the agent offers, and nothing to slide when it offers one level or none.
    val efforts: List<AgentOption> = if (agent?.supports(AgentCapability.effort) == true) agent.efforts else emptyList()
    val models = agent?.models ?: emptyList()
    val currentModelID = chat.session.model ?: agent?.defaultModel
    val modelLabel = agent?.modelLabel(currentModelID) ?: currentModelID ?: AgentLabel.name(chat.session.agent)
    // The level the thumb is on while it is moving, and the session's own level when there is no
    // slider to move. Amendment A25: an agent that lists no levels has no such setting, so the row
    // reads the model alone.
    val effortLabel = if (efforts.isEmpty()) TerminalSetting.effortText(chat.session, agent) else efforts.getOrNull(stop)?.label
    val chosen = chat.session.effort ?: agent?.defaultEffort
    val currentEffortIndex = efforts.indexOfFirst { it.id == chosen }.coerceAtLeast(0)
    LaunchedEffect(chat.session.effort) { stop = currentEffortIndex }
    // Amendment A40: the thumb is where the finger left it while the device types the level in.
    // When the answer lands the row reads the session again, so a refused level slides back rather
    // than standing as a level the terminal never took.
    LaunchedEffect(pending) { if (!pending) stop = currentEffortIndex }
    CardColumn(
        Modifier.padding(horizontal = Theme.Space.medium, vertical = Theme.Space.small),
        minWidth = ModelCardMetrics.minWidth - Theme.Space.medium * 2,
        spacing = Theme.Space.small,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.small), verticalAlignment = Alignment.CenterVertically) {
            if (agent?.speeds?.isNotEmpty() == true) SpeedToggle(chat, agent)
            ModelButton(
                pairs = ModelCardSizing.pairs(agent, model = modelLabel),
                modelLabel = modelLabel,
                effortLabel = effortLabel,
                open = showsModels,
                enabled = models.isNotEmpty() && !pending,
                pending = pending,
                modifier = Modifier.weight(1f),
            ) { showsModels = !showsModels }
        }
        if (efforts.size > 1) {
            // The word above follows the thumb; the request waits for the release, so dragging
            // across four levels is one `session.set` and not four. The thumb must not move while
            // the last level is still being typed in, or it would show a level the terminal is not
            // on (A40).
            StopSlider(
                stops = efforts.size,
                index = stop,
                value = effortLabel ?: "",
                onIndex = { stop = it },
                onCommit = { landed -> efforts.getOrNull(landed)?.let { option -> model.perform { chat.set(effort = option.id) } } },
                modifier = Modifier
                    .holdsStill(pending)
                    .semantics { contentDescription = L10n.string("Effort") }
                    .testTag("composer.effort"),
            )
        }
        if (showsModels) {
            Column(Modifier.holdsStill(pending)) {
                Divider()
                for (option in models) {
                    key(option.id) {
                        Button(
                            onClick = {
                                showsModels = false
                                model.perform { chat.set(model = option.id) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Theme.Touch.minimum)
                                .disabledLook(!pending)
                                .testTag("composer.model.${option.id}"),
                            enabled = !pending,
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(option.label, Modifier.weight(1f), style = Theme.Text.label, color = Theme.ink, lineLimit = 1)
                                if (option.id == currentModelID) {
                                    Icon(Sf.checkmark, font = SystemFont.caption.weight(FontWeight.SemiBold), tint = Theme.ink)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A tap cycles standard → each tier the agent lists → standard. On a session this app drives the
 * lightning fills on the tap, because the store draws the change before the request leaves; on a
 * shared one it fills when the device says the tier took (A40).
 */
@Composable
private fun SpeedToggle(chat: ChatStore, agent: AgentInfo) {
    val model = LocalAppModel.current
    val isFast = chat.session.speed != null
    Button(
        onClick = {
            val next = chat.nextSpeed ?: return@Button
            model.perform { chat.set(speed = next) }
        },
        modifier = Modifier
            .disabledLook(!chat.isSettingPending)
            .semantics {
                contentDescription = L10n.string("Speed")
                stateDescription = agent.speedLabel(chat.session.speed) ?: L10n.string("Standard")
            }
            .testTag("composer.speed"),
        enabled = !chat.isSettingPending,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(if (isFast) Theme.accent else Theme.quietFill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (isFast) Sf.boltFill else Sf.bolt, font = SystemFont.footnote.weight(FontWeight.SemiBold), tint = if (isFast) Theme.onAccent else Theme.ink)
        }
    }
}

@Composable
private fun ModelButton(
    pairs: List<ModelCardSizing.Pair>,
    modelLabel: String,
    effortLabel: String?,
    open: Boolean,
    enabled: Boolean,
    pending: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = Theme.Touch.minimum)
            .disabledLook(enabled)
            .semantics {
                contentDescription = L10n.string("Model")
                stateDescription = modelLabel
            }
            .testTag("composer.model"),
        enabled = enabled,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
            ModelCardSizer(pairs) { ModelNameLabel(modelLabel, effortLabel) }
            // `Spacer(minLength:)`: the chevron goes to the far end of whatever width the card took.
            Spacer(Modifier.weight(1f).widthIn(min = Theme.Space.tight))
            // Amendment A40: the change is being typed into a terminal and the answer comes from
            // its transcript. The spinner takes the chevron's place rather than standing beside
            // it, so nothing on the row moves while the device types.
            if (pending) {
                ActivityIndicator(size = IndicatorSize.mini)
            } else {
                Icon(if (open) Sf.chevronUp else Sf.chevronDown, font = SystemFont.caption.weight(FontWeight.SemiBold), tint = Theme.inkSecondary)
            }
        }
    }
}

/**
 * The card's column: as wide as its first row asks — and never narrower than [minWidth] — with
 * every row laid out at that width, as SwiftUI's stack offers the name row and the slider the
 * width the card took. A popover is measured with no width to fill, so the width has to come from
 * the content: the first row's own width, asked for before it is laid out.
 */
@Composable
private fun CardColumn(modifier: Modifier, minWidth: Dp, spacing: Dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, _ ->
        val gap = spacing.roundToPx()
        val natural = measurables.firstOrNull()?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val width = maxOf(natural, minWidth.roundToPx())
        val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
        val height = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            for (placeable in placeables) {
                placeable.place(0, y)
                y += placeable.height + gap
            }
        }
    }
}

/** `.disabled(_:)` for a control drawn by the design system that takes no `enabled`: no touch reaches it. */
private fun Modifier.holdsStill(still: Boolean): Modifier = if (!still) {
    this
} else {
    semantics { disabled() }.pointerInput(Unit) {
        awaitEachGesture {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

internal object ModelCardMetrics {
    /** The card's own floor; the name row sizes it now, and it is never narrower than this. */
    val minWidth = 280.dp
}
