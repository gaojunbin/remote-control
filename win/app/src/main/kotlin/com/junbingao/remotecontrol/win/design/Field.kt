package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junbingao.remotecontrol.win.design.overlay.dialogField
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/** Chrome's placeholder grey, which the web's fields keep: none of its stylesheets set `::placeholder`. */
internal val placeholderInk = Color.hex(0x757575)

/**
 * The text of a `.field`: what was typed, or the placeholder in the web's grey while nothing has
 * been. It draws no edge of its own, so a form can give it one (`fieldChrome`); `focusRequester`
 * and `onFocusChange` are how the form moves and follows its focus. Focus lands with the caret
 * after the text, as the web's `focus()` leaves it.
 */
@Composable
fun FieldText(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String = "",
    secure: Boolean = false,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    val size = if (mono) FontSize.fs13 else FontSize.fs14
    val face = SystemFace.face(size, FontWeight.Normal, mono)
    val line = NaturalLine.of(size, FontWeight.Normal, mono)
    var editing by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = if (editing.text == text) editing else TextFieldValue(text, TextRange(text.length))
    val style = ComposeTextStyle(
        color = Palette.ink,
        fontSize = size.sp,
        fontFamily = face.family,
        letterSpacing = face.tracking.sp,
        lineHeight = line.height.sp,
        platformStyle = TextRendering.platformStyle,
        localeList = LocaleList(InterfaceLanguageSource.current.rawValue),
    )
    BasicTextField(
        value = value,
        onValueChange = { next ->
            editing = next
            if (next.text != text) onTextChange(next.text)
        },
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                if (state.isFocused) editing = value.copy(selection = TextRange(value.text.length))
                onFocusChange(state.isFocused)
            },
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(if (LocalShowsCaret.current) Palette.ink else Color.Transparent),
        visualTransformation = if (secure) PasswordVisualTransformation('•') else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    BasicPlaceholder(placeholder, style.copy(color = placeholderInk))
                }
                field()
            }
        },
    )
}

@Composable
private fun BasicPlaceholder(text: String, style: ComposeTextStyle) {
    androidx.compose.foundation.text.BasicText(text, style = style, maxLines = 1, softWrap = false)
}

/**
 * `.field`: 40 px tall, a 1 px `--line` edge on a 12 px radius over the surface, the text 12 px
 * inside the edge. Focused, it is what the browser draws for a focused input: the edge turns ink
 * and a 3 px ring of 6 % ink surrounds it (`.field:focus`), and because a text field always
 * matches `:focus-visible` the radius drops to 4 and a 2 px ink outline is drawn 2 px outside.
 */
fun Modifier.fieldChrome(focused: Boolean): Modifier {
    val shape = RoundedCornerShape(if (focused) 4.dp else Radius.md)
    return focusOutline(focused)
        .drawBehind {
            if (focused) {
                val ring = 3.dp.toPx()
                val radius = (4 + 3).dp.toPx()
                drawRoundRect(
                    Color.rgb(17, 17, 17, opacity = 0.06f),
                    topLeft = Offset(-ring, -ring),
                    size = Size(size.width + 2 * ring, size.height + 2 * ring),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
        }
        .background(Palette.surface, shape)
        .border(1.dp, if (focused) Palette.ink else Palette.line, shape)
        .fillMaxWidth()
        .height(40.dp)
        // The web's 12 px of padding are inside a 1 px border, and the browser's text sits half a
        // pixel higher than the field centres it.
        .padding(start = Space.sp3 + 1.dp, end = Space.sp3 + 1.dp, bottom = 1.dp)
}

/**
 * A `.field` with its own focus: `FieldText` in the field's chrome. Inside a modal or the drawer
 * it is one of the fields the dialog may put the focus in as it opens (`DialogFocus`).
 */
@Composable
fun WebField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String = "",
    secure: Boolean = false,
    mono: Boolean = false,
    autofocus: Boolean = false,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    Box(modifier.fieldChrome(focused).dialogField(requester), contentAlignment = Alignment.CenterStart) {
        FieldText(
            text, onTextChange, placeholder, secure, mono,
            modifier = Modifier.fillMaxWidth(),
            focusRequester = requester,
            onFocusChange = { focused = it },
            onSubmit = onSubmit,
        )
    }
    LaunchedEffect(autofocus) { if (autofocus) requester.requestFocus() }
}
