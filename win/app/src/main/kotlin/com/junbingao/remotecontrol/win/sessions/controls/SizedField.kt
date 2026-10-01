package com.junbingao.remotecontrol.win.sessions.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junbingao.remotecontrol.win.design.LocalIsEnabled
import com.junbingao.remotecontrol.win.design.LocalShowsCaret
import com.junbingao.remotecontrol.win.design.NaturalLine
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.SystemFace
import com.junbingao.remotecontrol.win.design.TextRendering
import com.junbingao.remotecontrol.win.design.focusOutline
import com.junbingao.remotecontrol.win.design.placeholderInk
import com.junbingao.remotecontrol.win.design.rgb
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/**
 * `.field` at a height the foundation's `WebField` does not offer: the New session drawer's
 * working directory (46 px, 15 px mono, room at the trailing edge for the line that says whether it
 * exists) and the directory picker's new folder name (32 px). The chrome is the web's: a 1 px
 * `--line` edge on a 12 px radius, and when focused the ink edge, the 3 px ring and the
 * `:focus-visible` outline on a 4 px radius.
 */
@Composable
internal fun SizedField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String = "",
    mono: Boolean = false,
    fontSize: Float,
    height: Dp,
    trailingPadding: Dp = Space.sp3,
    autofocus: Boolean = false,
    /** Bumped to take the focus back, as a browser field keeps it through being disabled for a moment. */
    refocus: Int = 0,
    onFocus: (Boolean) -> Unit = {},
    onSubmit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    var editing by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = if (editing.text == text) editing else TextFieldValue(text, TextRange(text.length))
    val face = SystemFace.face(fontSize, FontWeight.Normal, mono)
    val style = ComposeTextStyle(
        color = Palette.ink,
        fontSize = fontSize.sp,
        fontFamily = face.family,
        letterSpacing = face.tracking.sp,
        lineHeight = NaturalLine.of(fontSize, FontWeight.Normal, mono).height.sp,
        platformStyle = TextRendering.platformStyle,
        localeList = LocaleList(InterfaceLanguageSource.current.rawValue),
    )
    val shape = RoundedCornerShape(if (focused) 4.dp else Radius.md)
    Box(
        modifier
            .focusOutline(focused)
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
            .height(height)
            // The web's padding is inside a 1 px border, and the browser's text sits half a pixel
            // higher than the field centres it.
            .padding(start = Space.sp3 + 1.dp, end = trailingPadding + 1.dp, bottom = 1.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                editing = next
                if (next.text != text) onTextChange(next.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { state ->
                    if (state.isFocused != focused) onFocus(state.isFocused)
                    focused = state.isFocused
                }
                .semantics { contentDescription = placeholder },
            enabled = LocalIsEnabled.current,
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(if (LocalShowsCaret.current) Palette.ink else Color.Transparent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) BasicText(placeholder, style = style.copy(color = placeholderInk), maxLines = 1, softWrap = false)
                    field()
                }
            },
        )
    }
    LaunchedEffect(Unit) {
        if (!autofocus) return@LaunchedEffect
        requester.requestFocus()
        // Again once the dialog around it has settled its own focus, which it does two frames after
        // it opens: a field that appears with its dialog would otherwise lose the focus it took.
        repeat(3) { withFrameNanos {} }
        requester.requestFocus()
    }
    LaunchedEffect(refocus) { if (refocus > 0) requester.requestFocus() }
}
