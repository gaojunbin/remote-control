package com.junbingao.remotecontrol.android.design

import android.annotation.SuppressLint
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp

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
 *
 * Text written from outside — a queued message taken back to be edited, a command row, a
 * dictation — puts the caret after the last character, as a text view's does when its text is
 * set, so typing carries on where the words end ([FieldText]).
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
    val field = remember { FieldText(text) }
    val shown = field.shown(text)

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
            value = shown,
            onValueChange = { next -> if (field.take(next, shown)) onTextChange(next.text) },
            enabled = enabled,
            textStyle = style,
            cursorBrush = SolidColor(Theme.accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = line, max = line * ComposerLayout.maximumLines)
                .scrollIndicator(scroll, flashesWhenFull = true)
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
 * The field's own copy of what it shows: the text, with the caret and any composition the keyboard
 * holds. Text that arrives different from the copy was written from outside and is shown with the
 * caret after its last character, as UITextView puts it when its text is set. The field's own edits
 * come back as the same text, so the copy is never reset under them and the selection and an IME
 * composition survive the round trip through the owner of the text.
 */
internal class FieldText(text: String) {
    private var held by mutableStateOf(TextFieldValue(text, TextRange(text.length)))

    /** What the field draws for [text]. */
    fun shown(text: String): TextFieldValue =
        if (held.text == text) held else TextFieldValue(text, TextRange(text.length))

    /** Take an edit the field made to [shown]; true when it changed the text, not only the selection or the composition. */
    fun take(next: TextFieldValue, shown: TextFieldValue): Boolean {
        held = next
        return next.text != shown.text
    }
}
