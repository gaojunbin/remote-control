package com.junbingao.remotecontrol.win.sessions.controls

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.FieldText
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.LocalReduceMotion
import com.junbingao.remotecontrol.win.design.Motion
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon

/**
 * `.search-field` where the lists size it themselves: the Sessions page's is 34 px tall and at most
 * 320 wide until 640, where it takes the whole line; the chat sidebar's is 32 tall with no cap
 * (`.sidebar-search`). The same pill as the foundation's `SearchField` otherwise — the icon in the
 * tertiary ink, a muted tint that deepens while the field has focus.
 */
@Composable
internal fun ListSearchField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    iconSize: Dp = 15.dp,
    height: Dp = 34.dp,
    maxWidth: Dp? = 320.dp,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val fill by animateColorAsState(if (focused) Palette.surfaceActive else Palette.surfaceMuted, Motion.ease(Motion.durFast, LocalReduceMotion.current))
    HStack(
        modifier
            .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier)
            .fillMaxWidth()
            .height(height)
            .background(fill, CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { requester.requestFocus() }
            .padding(horizontal = Space.sp3)
            .semantics { contentDescription = placeholder },
        spacing = Space.sp2,
    ) {
        Icon(LucideIcon.search, size = iconSize, color = Palette.inkTertiary)
        // The browser keeps an input's own 2 px of padding, which the stylesheet never resets.
        FieldText(
            text, onTextChange, placeholder,
            modifier = Modifier.weight(1f).padding(start = 2.dp),
            focusRequester = requester,
            onFocusChange = { focused = it },
        )
    }
}
