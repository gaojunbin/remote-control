package com.junbingao.remotecontrol.android.system

import androidx.compose.animation.core.Transition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ButtonStyle
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.interfaceLocale
import com.junbingao.remotecontrol.android.strings.L10n

/** A button's role in an alert or a dialog, as SwiftUI's `ButtonRole`. */
enum class ActionRole { normal, cancel, destructive }

/** One button of an alert or a confirmation dialog. Tapping it acts, then dismisses. */
data class AlertAction(
    val title: String,
    val role: ActionRole = ActionRole.normal,
    val enabled: Boolean = true,
    val tag: String? = null,
    val action: () -> Unit = {},
)

/** The text field an alert asks for a value in: a new name, a new password. */
data class AlertTextField(
    val placeholder: String,
    val value: String,
    val onValueChange: (String) -> Unit,
    val secure: Boolean = false,
    val tag: String? = null,
)

/**
 * `.alert(_:isPresented:actions:message:)` as iOS 26 draws it: a 320-point glass card in the
 * middle of a dimmed screen, the title and the message set flush left, and the buttons as
 * capsules — side by side when there are two and both fit, the cancel on the left, otherwise
 * stacked with the cancel last. Tapping outside does nothing, as on the iPhone; Back cancels.
 */
@Composable
fun Alert(
    isPresented: Boolean,
    title: String,
    onDismiss: () -> Unit,
    message: String? = null,
    actions: List<AlertAction> = emptyList(),
    textField: AlertTextField? = null,
) {
    Present(PresentationKind.alert, isPresented, onDismiss) {
        AlertCard(title, message, actions.ifEmpty { listOf(AlertAction(L10n.string("OK"), ActionRole.cancel)) }, textField, onDismiss)
    }
}

@Composable
private fun AlertCard(
    title: String,
    message: String?,
    actions: List<AlertAction>,
    textField: AlertTextField?,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .width(AlertMetrics.width)
            .glassPanel(ContinuousShape(AlertMetrics.corner), Glass.alert)
            .padding(top = AlertMetrics.topPadding, bottom = AlertMetrics.buttonInset),
    ) {
        Column(
            Modifier.padding(horizontal = AlertMetrics.textInset),
            verticalArrangement = Arrangement.spacedBy(AlertMetrics.messageSpacing),
        ) {
            Text(title, style = SystemFont.headline, color = Theme.ink)
            if (message != null) Text(message, style = SystemFont.subheadline, color = AlertMetrics.messageColor)
        }
        if (textField != null) AlertField(textField)
        Box(Modifier.height(AlertMetrics.buttonsTop))
        AlertButtons(actions, onDismiss, Modifier.padding(horizontal = AlertMetrics.buttonInset))
    }
}

@Composable
private fun AlertField(field: AlertTextField) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier
            .padding(start = AlertMetrics.buttonInset, end = AlertMetrics.buttonInset, top = 16.dp)
            .fillMaxWidth()
            .height(44.dp)
            .background(Theme.surface, CapsuleShape)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = field.value,
            onValueChange = field.onValueChange,
            singleLine = true,
            textStyle = SystemFont.body.copy(color = Theme.ink, localeList = interfaceLocale()),
            cursorBrush = SolidColor(Theme.accent),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            visualTransformation = if (field.secure) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .then(if (field.tag != null) Modifier.testTag(field.tag) else Modifier),
        )
        if (field.value.isEmpty()) Text(field.placeholder, style = SystemFont.body, color = SystemColor.tertiaryLabel, lineLimit = 1)
    }
}

/**
 * The buttons, side by side or stacked. Two sit side by side only when each title fits its half,
 * which is how UIKit decides; the cancel is on the left of two and last of a stack.
 */
@Composable
internal fun AlertButtons(actions: List<AlertAction>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val cancelLast = actions.sortedBy { if (it.role == ActionRole.cancel) 1 else 0 }
    val sideBySide = actions.size == 2 && fitsHalf(measurer, actions)
    if (sideBySide) {
        val ordered = actions.sortedBy { if (it.role == ActionRole.cancel) 0 else 1 }
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AlertMetrics.buttonGap)) {
            for (action in ordered) AlertButton(action, onDismiss, Modifier.weight(1f))
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AlertMetrics.buttonGap)) {
            for (action in cancelLast) AlertButton(action, onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun fitsHalf(measurer: TextMeasurer, actions: List<AlertAction>): Boolean {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val half = (AlertMetrics.width - AlertMetrics.buttonInset * 2 - AlertMetrics.buttonGap) / 2 - AlertMetrics.buttonTextInset * 2
    val room = with(density) { half.toPx() }
    return actions.all { measurer.measure(it.title, SystemFont.body).size.width <= room }
}

@Composable
private fun AlertButton(action: AlertAction, onDismiss: () -> Unit, modifier: Modifier) {
    Button(
        onClick = {
            action.action()
            onDismiss()
        },
        modifier = modifier.then(if (action.tag != null) Modifier.testTag(action.tag) else Modifier),
        enabled = action.enabled,
        style = AlertButtonStyle,
    ) {
        Text(
            action.title,
            style = SystemFont.body,
            color = when {
                !action.enabled -> SystemColor.tertiaryLabel
                action.role == ActionRole.destructive -> AlertMetrics.destructive
                else -> Theme.ink
            },
            alignment = TextAlign.Center,
            lineLimit = 1,
        )
    }
}

private val AlertButtonStyle = ButtonStyle { configuration, label ->
    Box(
        Modifier
            .fillMaxWidth()
            .height(AlertMetrics.buttonHeight)
            .background(
                if (configuration.isPressed) Glass.selection.copy(alpha = 0.2f) else Glass.selection,
                CapsuleShape,
            )
            .padding(horizontal = AlertMetrics.buttonTextInset),
        contentAlignment = Alignment.Center,
    ) { label() }
}

@Composable
internal fun AlertLayer(transition: Transition<Boolean>, body: @Composable () -> Unit) {
    val progress = transition.progress(IosDurations.alertIn, IosDurations.alertOut)
    val safe = safeArea()
    Box(Modifier.fillMaxSize()) {
        Scrim(progress, null)
        // Centred in the safe area, as the iPhone centres it, and above the keyboard when it is up.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = safe.top, bottom = safe.bottomWithKeyboard)
                .graphicsLayer {
                    alpha = progress
                    val scale = 1.15f - 0.15f * progress
                    scaleX = scale
                    scaleY = scale
                },
            contentAlignment = Alignment.Center,
        ) { body() }
    }
}

/** The alert's measurements, from the iPhone 17 reference screenshots. */
object AlertMetrics {
    val width = 320.dp
    val corner = 34.dp
    val topPadding = 22.dp
    val textInset = 31.dp
    val messageSpacing = 7.dp
    val buttonsTop = 21.dp
    val buttonInset = 16.dp
    val buttonHeight = 48.dp
    val buttonGap = 8.dp
    val buttonTextInset = 12.dp

    /** The message's grey, which is the system's secondary label read through the glass. */
    val messageColor: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xFFA1A1A6) else Color(0xFF868685)

    /** The destructive word as the iPhone's alert sets it. */
    val destructive: Color @Composable @ReadOnlyComposable
        get() = if (LocalAppearance.current.isDark) Color(0xFFFF453A) else Color(0xFFE9292C)
}
