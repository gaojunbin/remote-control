package com.junbingao.remotecontrol.android.design

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The field a message is written in: one line while the draft is short, growing with it to
 * `ComposerLayout.maximumLines`, then scrolling inside itself with an indicator down its trailing
 * edge — the only cue `docs/DESIGN.md` allows that there is more above or below.
 *
 * Its lines are the iPhone text view's: 22 apart at the body size, measured from the reference
 * screenshots, so eight lines are as tall here as there. The indicator shows while the draft is
 * scrolled and fades after, and flashes once when the field starts to scroll, as UIKit's does.
 *
 * [isFocused] reads both ways, as the iPhone's `Bool` binding does: setting it puts the keyboard
 * up, and the field reports through [onFocusChange] when editing ends; a field nothing drives from
 * outside leaves both out. [followsTail] keeps the last line in view while something outside is
 * writing into the field — dictation — and is off for typing, where the cursor keeps itself
 * visible. Scrolling down is the only move it makes.
 */
@Composable
fun GrowingTextField(
    placeholder: String,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isFocused: Boolean? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    identifier: String? = null,
    followsTail: Boolean = false,
    enabled: Boolean = true,
) {
    val style = SystemFont.body.copy(
        color = Theme.ink,
        localeList = interfaceLocale(),
        // A text view's lines are all one height, the first one included.
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    val density = LocalDensity.current
    val line = with(density) { style.lineHeight.toDp() }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(isFocused, enabled) {
        when {
            isFocused == true && enabled && !focused -> focus.requestFocus()
            isFocused == false && focused -> focusManager.clearFocus()
        }
    }
    if (followsTail) {
        LaunchedEffect(Unit) {
            snapshotFlow { scroll.maxValue }.collect { end -> if (end > scroll.value) scroll.scrollTo(end) }
        }
    }

    Box(modifier.fillMaxWidth().textInputRegion()) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = enabled,
            textStyle = style,
            cursorBrush = SolidColor(Theme.accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = line, max = line * ComposerLayout.maximumLines)
                .verticalScroll(scroll)
                .focusRequester(focus)
                .onFocusChanged { state ->
                    if (state.isFocused != focused) {
                        focused = state.isFocused
                        onFocusChange?.invoke(state.isFocused)
                    }
                }
                // The placeholder is the field's name, read before whatever has been typed.
                .semantics { contentDescription = placeholder }
                .then(if (identifier != null) Modifier.testTag(identifier) else Modifier),
        )
        if (text.isEmpty()) {
            Text(
                placeholder,
                style = SystemFont.body.copy(lineHeightStyle = style.lineHeightStyle),
                color = Theme.inkSecondary,
                lineLimit = 1,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        ScrollIndicator(scroll, Modifier.matchParentSize())
        if (FieldScrollProbe.isOn && identifier != null) {
            // The probe exists to print the offset, so it reads it as it changes; only a debug
            // build behind `--field-scroll-probe` ever draws it.
            @SuppressLint("FrequentlyChangingValue")
            val offset = with(density) { scroll.value.toDp().value }
            val end = with(density) { scroll.maxValue.toDp().value }
            // A point tall and drawn in nothing: the element is there to be read.
            Text(
                FieldScrollProbe.report(offset, end),
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(1.dp)
                    .graphicsLayer(alpha = 0f)
                    .testTag("$identifier.scroll"),
            )
        }
    }
}

/**
 * UIKit's scroll indicator: a thin dark capsule down the trailing edge, there while the content
 * moves and for a moment after, and flashed once when there first is somewhere to scroll.
 */
@Composable
private fun ScrollIndicator(scroll: ScrollState, modifier: Modifier) {
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { scroll.maxValue > 0 }.collect { scrolls ->
            if (scrolls) {
                flash = true
                delay(900)
                flash = false
            }
        }
    }
    val visible = scroll.maxValue > 0 && (scroll.isScrollInProgress || flash)
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 100 else 350), label = "indicator")
    val ink = SystemColor.label.copy(alpha = 0.35f)
    Canvas(modifier) {
        if (alpha == 0f || scroll.maxValue <= 0) return@Canvas
        val viewport = size.height
        val content = viewport + scroll.maxValue
        val width = 3.dp.toPx()
        val inset = 3.dp.toPx()
        val length = (viewport * viewport / content).coerceAtLeast(36.dp.toPx().coerceAtMost(viewport))
        val top = (viewport - length) * scroll.value / scroll.maxValue
        drawRoundRect(
            ink.copy(alpha = ink.alpha * alpha),
            topLeft = Offset(size.width - inset - width, top),
            size = Size(width, length),
            cornerRadius = CornerRadius(width / 2),
        )
    }
}
