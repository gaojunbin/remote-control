package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.LocalShowsCaret
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.TextRendering
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.recoloured
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import kotlin.math.ceil

/**
 * The composer's `<textarea>`: Compose's text field in a thin scroll view, because only a text
 * field's value tells whether an input method holds a composition (`ComposerTextView`). It grows
 * with its words from one line to 220 px and scrolls inside after that, keeps the undo of what was
 * typed, and writes every edit straight to the draft.
 */
@Composable
fun ComposerField(
    composer: ComposerModel,
    /** The words, passed in so a change re-measures the field. */
    text: String,
    /** A terminal holds the session, or the device is offline: nothing to type into. */
    disabled: Boolean,
    /** A43: an edited message is on its way back, so the field holds still. */
    readOnly: Boolean,
    modifier: Modifier = Modifier,
    /** The field's words, selection and composition, which a test holds to drive an input method. */
    view: ComposerTextView = remember { ComposerTextView(text) },
    onFocusChange: (Boolean) -> Unit,
) {
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val language = InterfaceLanguageSource.current
    val measurer = rememberTextMeasurer()
    val neutral = TextRendering.neutralInk
    val style = remember(language) { ComposerFieldText.composeStyle(neutral ?: Palette.ink, language) }
    val shift = remember(style, density) { ComposerFieldText.baselineShift(style, measurer, density) }
    val inset = ComposerFieldText.padding * density.density
    val transformation = remember(language) { FaceRunsTransformation(language) }
    var lines by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    val editable = !disabled && !readOnly
    view.onKey = { key, shiftDown, marked -> composer.handle(key, shift = shiftDown, hasMarkedText = marked) }
    view.onTyped = { composer.userTyped(it) }
    view.onFiles = { composer.attach(it) }
    view.acceptsFiles = { composer.acceptsFiles }
    view.moveFocus = { forward -> focusManager.moveFocus(if (forward) FocusDirection.Next else FocusDirection.Previous) }
    // A write that is not a keystroke replaces the words and leaves the caret after them.
    val value = view.shown(text)
    SideEffect { view.adopt(value) }
    ThinScrollView(modifier = modifier.fillMaxWidth().heightIn(max = ComposerFieldText.maxHeight.dp), state = scroll) {
        BasicTextField(
            value = value,
            onValueChange = view::edit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = with(density) { (inset + shift).toDp() }, bottom = with(density) { (inset - shift).toDp() })
                .then(if (neutral != null) Modifier.recoloured(Palette.ink) else Modifier)
                .focusRequester(focus)
                .onFocusChanged { state ->
                    focused = state.isFocused
                    onFocusChange(state.isFocused)
                }
                .onPreviewKeyEvent { view.keyDown(it, editable) }
                .pointerInput(composer) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        composer.pointerDownInField()
                    }
                }
                .semantics { contentDescription = S.composer.placeholder },
            enabled = !disabled,
            readOnly = readOnly,
            textStyle = style,
            cursorBrush = SolidColor(if (LocalShowsCaret.current) Palette.ink else Color.Transparent),
            visualTransformation = transformation,
            onTextLayout = { lines = it },
        )
    }
    // The focus and the tail, each asked for by bumping a counter. Both wait for the layout that
    // the new words bring.
    LaunchedEffect(composer.focusRequest) {
        if (composer.focusRequest == 0) return@LaunchedEffect
        // `focus()` with the caret after the words, which a browser scrolls into view.
        focus.requestFocus()
        view.caretToEnd()
        withFrameNanos {}
        scroll.scrollTo(scroll.maxValue)
    }
    LaunchedEffect(composer.tailRequest) {
        if (composer.tailRequest == 0) return@LaunchedEffect
        // `scrollTop = scrollHeight`, never animated: the end of the words stays in view while
        // dictation writes them.
        withFrameNanos {}
        scroll.scrollTo(scroll.maxValue)
    }
    // A caret keeps itself visible while the person types, as a browser's does.
    LaunchedEffect(value.selection, lines, focused) {
        val layout = lines ?: return@LaunchedEffect
        if (!focused || scroll.viewportSize <= 0) return@LaunchedEffect
        val at = value.selection.end.coerceIn(0, layout.layoutInput.text.length)
        if (layout.getLineForOffset(at) == layout.lineCount - 1) {
            scroll.scrollTo(scroll.maxValue)
            return@LaunchedEffect
        }
        val caret = layout.getCursorRect(at)
        val top = inset + shift + caret.top
        val bottom = inset + shift + caret.bottom
        when {
            top < scroll.value -> scroll.scrollTo(top.toInt())
            bottom > scroll.value + scroll.viewportSize -> scroll.scrollTo(ceil(bottom - scroll.viewportSize).toInt())
        }
    }
}
