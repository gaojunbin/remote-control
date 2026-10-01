package com.junbingao.remotecontrol.win.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon

/**
 * `.search-field`: the pill-shaped search the Sessions page and the chat sidebar share — the icon
 * in the tertiary ink, a muted tint that deepens while the field has focus, and at most 320 px wide.
 */
@Composable
fun SearchField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    iconSize: Dp = 15.dp,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val reduceMotion = LocalReduceMotion.current
    val fill by animateColorAsState(if (focused) Palette.surfaceActive else Palette.surfaceMuted, Motion.ease(Motion.durFast, reduceMotion))
    HStack(
        modifier
            .widthIn(max = 320.dp)
            .height(34.dp)
            .background(fill, CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { requester.requestFocus() }
            .padding(horizontal = Space.sp3),
        spacing = Space.sp2,
    ) {
        Icon(LucideIcon.search, size = iconSize, color = Palette.inkTertiary)
        FieldText(
            text, onTextChange, placeholder,
            modifier = Modifier.weight(1f),
            focusRequester = requester,
            onFocusChange = { focused = it },
        )
    }
}
