package com.junbingao.remotecontrol.win.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * `.group-title`: the plain caption above a surface. 12 px, 600, the tertiary ink, indented by the
 * row padding. Sentence case: nothing in the app is re-cased.
 */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        css(FontSize.fs12, weight = FontWeight.SemiBold, lineHeight = 1.4f),
        modifier.fillMaxWidth().padding(start = Space.sp4, bottom = Space.sp2),
        color = Palette.inkTertiary,
    )
}

/**
 * `.label`: a form field's label, 13 px, 500, the secondary ink, 8 above its field. Sentence case,
 * like every other caption in the app.
 */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        css(FontSize.fs13, weight = FontWeight.Medium, lineHeight = 1.4f),
        modifier.fillMaxWidth().padding(bottom = Space.sp2),
        color = Palette.inkSecondary,
    )
}

/** `.hint`: secondary text at 13 px. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, css(FontSize.fs13), modifier, color = Palette.inkSecondary)
}

/** `.form-error`: why a form was refused, under the fields it belongs to. */
@Composable
fun FormError(text: String, modifier: Modifier = Modifier) {
    Text(text, css(FontSize.fs13, lineHeight = 1.5f), modifier.fillMaxWidth(), color = Palette.danger)
}

/**
 * `.empty`: the centred state a list shows when it has nothing, with an optional first line in
 * the primary ink (`<strong>`).
 */
@Composable
fun EmptyState(text: String, title: String? = null, modifier: Modifier = Modifier) {
    VStack(
        modifier.fillMaxWidth().padding(vertical = Space.sp10, horizontal = Space.sp4),
        spacing = 0.dp,
    ) {
        if (title != null) {
            Text(
                title,
                css(FontSize.fs14, weight = FontWeight.Medium),
                Modifier.padding(bottom = Space.sp1),
                color = Palette.ink,
                textAlign = TextAlign.Center,
            )
        }
        Text(text, css(FontSize.fs14), color = Palette.inkSecondary, textAlign = TextAlign.Center)
    }
}
