package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import com.junbingao.remotecontrol.android.design.Truncation
import com.junbingao.remotecontrol.android.design.holdsCjk
import com.junbingao.remotecontrol.android.design.interfaceLocale
import kotlin.math.ceil

/**
 * A `Text` as wide as what it draws, the way SwiftUI sizes one, where Compose takes the whole
 * width it was offered as soon as the words wrap or are cut short. The person's bubble hugs what
 * they wrote (`docs/DESIGN.md` § "The timeline"), and a path cut from its head in the chat header
 * ends where its last letter does, so the dot after it follows at the stack's spacing.
 *
 * The words are measured as the design system's `Text` sets them — the style's tracking for Latin,
 * none for Chinese, the interface language's locale — with the same [lineLimit] and [truncation],
 * and the text is then laid out at exactly the width of its widest line, which breaks or cuts it
 * where it did before.
 */
@Composable
fun Modifier.hugsLines(text: String, style: TextStyle, lineLimit: Int? = null, truncation: Truncation = Truncation.tail): Modifier {
    val measurer = rememberTextMeasurer()
    val locale = interfaceLocale()
    val resolved = style.copy(localeList = locale, letterSpacing = if (holdsCjk(text)) TextUnit.Unspecified else style.letterSpacing)
    val overflow = when {
        lineLimit == null -> TextOverflow.Clip
        lineLimit == 1 && truncation == Truncation.head -> TextOverflow.StartEllipsis
        lineLimit == 1 && truncation == Truncation.middle -> TextOverflow.MiddleEllipsis
        else -> TextOverflow.Ellipsis
    }
    return layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || text.isEmpty()) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val result = measurer.measure(
            AnnotatedString(text),
            resolved,
            overflow = overflow,
            softWrap = lineLimit != 1,
            maxLines = lineLimit ?: Int.MAX_VALUE,
            constraints = Constraints(maxWidth = constraints.maxWidth),
        )
        val widest = (0 until result.lineCount).maxOfOrNull { result.getLineRight(it) - result.getLineLeft(it) } ?: 0f
        // A pixel to spare, so rounding cannot push the last word of the widest line onto the next.
        val width = (ceil(widest).toInt() + 1).coerceIn(constraints.minWidth, constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * How a plain button the iPhone disables looks: its label at half strength, as SwiftUI draws a
 * disabled button in the plain style — measured on the disabled microphone of a terminal-held
 * session (`88-grok-terminal-composer`).
 */
fun Modifier.disabledLook(enabled: Boolean): Modifier = if (enabled) this else alpha(DisabledLook.opacity)

object DisabledLook {
    const val opacity = 0.5f
}
