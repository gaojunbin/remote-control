package com.junbingao.remotecontrol.android.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * A circle that breathes between full and half opacity while it is asked to, and stands still at
 * full opacity when it is not. Both dots draw themselves with it; which one moves is each dot's
 * own rule.
 */
@Composable
private fun BreathingCircle(color: Color, size: Dp, pulsing: Boolean) {
    val alpha = if (pulsing) {
        // A trough deep enough to read as motion and shallow enough that a still frame never
        // shows a washed-out colour.
        rememberInfiniteTransition(label = "breathing").animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathing",
        ).value
    } else {
        1f
    }
    Box(Modifier.size(size).alpha(alpha).background(color, CircleShape))
}

/**
 * A session status dot. The colour is a shortcut; the label next to it always says the same
 * thing in words. [color] and [pulses] are `Theme.dotColor(tone)` and `StatusDot.pulses(tone:)`
 * of the session's `DotTone`, the core's rule, which only the waiting tone moves under; Reduce
 * Motion (the system's animator scale at zero) holds even that one still.
 */
@Composable
fun StatusDot(color: Color, pulses: Boolean, size: Dp = 8.dp) {
    BreathingCircle(color, size, pulsing = pulses && !LocalAppearance.current.reduceMotion)
}

/**
 * A device's own dot, which is not a session dot and does not follow the tone table: green while
 * the machine answers, grey when it is gone, and breathing while it updates itself (A22).
 */
@Composable
fun OnlineDot(online: Boolean, updating: Boolean = false, size: Dp = 8.dp) {
    BreathingCircle(
        color = if (online || updating) Theme.running else Theme.resting,
        size = size,
        pulsing = updating && !LocalAppearance.current.reduceMotion,
    )
}

/** The black pill used for the one primary action on a screen. */
class PrimaryButtonStyle(private val fullWidth: Boolean = true) : ButtonStyle {
    @Composable
    override fun Body(configuration: ButtonConfiguration, label: @Composable () -> Unit) {
        Box(
            Modifier
                .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
                .alpha(if (configuration.isPressed) 0.82f else 1f)
                .background(Theme.accent, CapsuleShape)
                .heightIn(min = Theme.Touch.primary)
                .padding(horizontal = Theme.Space.large),
            contentAlignment = Alignment.Center,
        ) {
            Foreground(Theme.onAccent, SystemFont.body.weight(FontWeight.Medium)) { label() }
        }
    }
}

/** The pill itself: the tint, the type ramp and the height every chip shares. */
@Composable
fun ChipPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .background(Theme.quietFill, CapsuleShape)
            .heightIn(min = 32.dp)
            .padding(horizontal = Theme.Space.small + 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Foreground(Theme.ink, Theme.Text.meta) { content() }
    }
}

/**
 * A quiet pill for secondary actions and chips: tinted, never outlined, so a screen carries one
 * filled button and nothing else with an edge. The pill stays 32 tall; the target is 44.
 */
val ChipButtonStyle = ButtonStyle { configuration, label ->
    Box(Modifier.heightIn(min = Theme.Touch.minimum), contentAlignment = Alignment.Center) {
        ChipPill(Modifier.alpha(if (configuration.isPressed) 0.6f else 1f)) { label() }
    }
}

/**
 * A dot and a word, in that order, always both. The dot alone is never the signal, and the word
 * alone loses the glanceable colour. [textColor] is `Theme.attention` for the waiting tone and
 * the secondary ink for every other, as the iPhone's does.
 */
@Composable
fun StatusLabel(dot: Color, pulses: Boolean, text: String, textColor: Color = Theme.inkSecondary) {
    Row(
        Modifier.clearAndSetSemantics { contentDescription = text },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(dot, pulses)
        Text(text, style = Theme.Text.meta, color = textColor, lineLimit = 1)
    }
}

/**
 * A session row's dot and the word beside it: the state in colour, the origin in words. The word
 * keeps the secondary ink whatever the dot says, so the two never say the same thing twice.
 */
@Composable
fun SessionOriginLabel(dot: Color, pulses: Boolean, origin: String) {
    Row(
        Modifier.clearAndSetSemantics { contentDescription = origin },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(dot, pulses)
        Text(origin, style = Theme.Text.meta, color = Theme.inkSecondary, lineLimit = 1)
    }
}

/**
 * The agent a session runs, as a tinted pill: its logo, then its name (`AgentLabel.name`, the
 * core's). Tinted and never outlined, and no agent carries a colour of its own.
 */
@Composable
fun AgentChip(agent: String, name: String) {
    Row(
        Modifier
            .background(Theme.quietFill, CapsuleShape)
            .padding(horizontal = Theme.Space.tight, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Theme.Space.hair + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentLogo(agent)
        Text(name, style = Theme.Text.caption, color = Theme.inkSecondary, lineLimit = 1)
    }
}

/**
 * The caption above a group of fields, with an optional trailing count or control on the same
 * line. It takes the catalogue key and looks the words up itself, as the iPhone's takes a
 * `LocalizedStringKey`, and spells them as written: nothing is re-cased.
 */
@Composable
fun FieldLabel(key: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(L10n.string(key), style = Theme.Text.meta, color = Theme.inkSecondary)
        Spacer(Modifier.weight(1f).widthIn(min = Theme.Space.small))
        trailing?.invoke()
    }
}

/** Monospace text for a path, a command or a tool title, cut from the head so its end shows. */
@Composable
fun CodeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Theme.inkSecondary,
    font: TextStyle = Theme.mono,
) {
    Text(text, modifier, style = font, color = color, lineLimit = 1, truncation = Truncation.head)
}
