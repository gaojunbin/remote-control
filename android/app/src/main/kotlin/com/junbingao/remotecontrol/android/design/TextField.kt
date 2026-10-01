package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp

/**
 * SwiftUI's single-line `TextField` and `SecureField` as a form row draws them: no box of their
 * own (the card or the row they sit on is the box), the placeholder in the tertiary ink, the
 * caret in the accent, a row at least [minHeight] tall. [style] is the font, as `.font(…)` on the
 * iPhone's field; [submit] is the return key's action and [onSubmit] what it does (`.submitLabel`
 * and `.onSubmit`). A secure field shows dots and never offers its words to the keyboard's
 * suggestions.
 */
@Composable
fun TextField(
    placeholder: String,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = SystemFont.body,
    secure: Boolean = false,
    keyboard: KeyboardOptions = PlainTextEntry,
    submit: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
    enabled: Boolean = true,
    minHeight: Dp = Theme.Touch.minimum,
    tag: String? = null,
) {
    val ink = if (enabled) Theme.ink else SystemColor.tertiaryLabel
    Box(modifier.fillMaxWidth().heightIn(min = minHeight).textInputRegion(), contentAlignment = Alignment.CenterStart) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            enabled = enabled,
            textStyle = style.copy(color = ink, localeList = interfaceLocale()),
            cursorBrush = SolidColor(Theme.accent),
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboard.copy(imeAction = submit, autoCorrectEnabled = if (secure) false else keyboard.autoCorrectEnabled),
            keyboardActions = KeyboardActions(onAny = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = placeholder }
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        )
        // UIKit's `placeholderText` is the tertiary label.
        if (text.isEmpty()) Text(placeholder, style = style, color = SystemColor.tertiaryLabel, lineLimit = 1)
    }
}
