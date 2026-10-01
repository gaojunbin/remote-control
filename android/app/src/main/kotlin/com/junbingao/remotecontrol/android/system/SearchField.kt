package com.junbingao.remotecontrol.android.system

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.interfaceLocale
import com.junbingao.remotecontrol.android.design.textInputRegion
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * `.searchable(text:prompt:)`'s field as iOS 26 draws it in a navigation bar: a glass capsule 44
 * points tall with the magnifying glass, the words, and — once there are any — the filled clear
 * button; while it is being used, a close button of its own beside it ends the search, which is
 * [onCancel]. [prompt] is the placeholder, "Search" when the screen names none.
 */
@Composable
fun SearchField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String = L10n.string("Search"),
    isActive: Boolean = false,
    onActiveChange: (Boolean) -> Unit = {},
    onCancel: (() -> Unit)? = null,
    tag: String = "search.field",
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // The caret starts at the end of what is there, as UIKit puts it when a search resumes; the
    // words themselves stay the caller's.
    var selection by remember { mutableStateOf(TextRange(text.length)) }
    val field = TextFieldValue(text, TextRange(selection.start.coerceAtMost(text.length), selection.end.coerceAtMost(text.length)))
    LaunchedEffect(isActive) { if (isActive) focus.requestFocus() }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BarMetrics.spacing + 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(BarMetrics.buttonHeight)
                .glass(CapsuleShape)
                .textInputRegion()
                .padding(start = SearchMetrics.leading, end = SearchMetrics.trailing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Sf.magnifyingglass, font = SystemFont.body.weight(FontWeight.Medium), tint = Theme.ink)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = field,
                    onValueChange = {
                        selection = it.selection
                        if (it.text != text) onTextChange(it.text)
                    },
                    singleLine = true,
                    textStyle = SystemFont.body.copy(color = Theme.ink, localeList = interfaceLocale()),
                    cursorBrush = SolidColor(Theme.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { if (it.isFocused != isActive && it.isFocused) onActiveChange(true) }
                        .semantics { contentDescription = prompt }
                        .testTag(tag),
                )
                if (text.isEmpty()) Text(prompt, style = SystemFont.body, color = SystemColor.secondaryLabel, lineLimit = 1)
            }
            if (text.isNotEmpty()) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onTextChange("") }
                        .semantics { contentDescription = L10n.string("Clear text") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Sf.xmarkCircleFill, font = SystemFont.body, tint = Theme.ink)
                }
            }
        }
        if (isActive && onCancel != null) {
            BarButton(
                onClick = {
                    focusManager.clearFocus()
                    onCancel()
                },
                Modifier.width(BarMetrics.buttonHeight),
                contentDescription = L10n.string("Close"),
                tag = "search.cancel",
            ) {
                Icon(Sf.xmark, side = SearchMetrics.closeSide, weight = FontWeight.Normal, tint = Theme.ink)
            }
        }
    }
}

/** The search field's measurements, from the iPhone 17's archive search (`16-archive-search`). */
object SearchMetrics {
    /** From the capsule's edges to the magnifying glass's box and to the clear button's. */
    val leading = 12.33.dp
    val trailing = 9.5.dp

    /** The close button's cross, 17 points of ink: larger than a bar button's text-sized symbol. */
    val closeSide = 29.dp
}
