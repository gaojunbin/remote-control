package com.junbingao.remotecontrol.android.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.junbingao.remotecontrol.android.strings.L10n

/** How a line that does not fit gives way: SwiftUI's `truncationMode`. */
enum class Truncation { head, middle, tail }

/**
 * SwiftUI's `Text`: the iPhone's text style, the ink it is given — or the nearest one set above
 * it ([LocalForeground], [LocalFont]), or the system's primary label colour — and a line limit
 * that truncates where the iPhone does.
 *
 * The words are drawn in the interface language's locale, so Chinese takes the simplified forms
 * of the system's CJK face whatever the phone itself is set to, as the iPhone's `\.locale` does.
 * Nothing here translates: a word is looked up with `L10n.string` where it is written, and a
 * string handed in is drawn as it is, like `Text(verbatim:)`.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalFont.current,
    color: Color = Color.Unspecified,
    lineLimit: Int? = null,
    truncation: Truncation = Truncation.tail,
    alignment: TextAlign = TextAlign.Start,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val box = remember { TextBoxMeasure() }
    BasicText(
        text = text,
        modifier = modifier.swiftUITextBox(style, box),
        style = resolve(style, color, alignment, text),
        overflow = overflow(lineLimit, truncation),
        softWrap = lineLimit != 1,
        maxLines = lineLimit ?: Int.MAX_VALUE,
        onTextLayout = {
            box.result = it
            onTextLayout?.invoke(it)
        },
    )
}

/** The same, for text with runs of its own weight, colour or face. */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalFont.current,
    color: Color = Color.Unspecified,
    lineLimit: Int? = null,
    truncation: Truncation = Truncation.tail,
    alignment: TextAlign = TextAlign.Start,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val box = remember { TextBoxMeasure() }
    BasicText(
        text = text,
        modifier = modifier.swiftUITextBox(style, box),
        style = resolve(style, color, alignment, text),
        overflow = overflow(lineLimit, truncation),
        softWrap = lineLimit != 1,
        maxLines = lineLimit ?: Int.MAX_VALUE,
        onTextLayout = {
            box.result = it
            onTextLayout?.invoke(it)
        },
    )
}

@Composable
private fun resolve(style: TextStyle, color: Color, alignment: TextAlign, text: CharSequence): TextStyle {
    val inherited = LocalForeground.current
    val ink = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        inherited != Color.Unspecified -> inherited
        else -> SystemColor.label
    }
    val tracking = if (holdsCjk(text)) TextUnit.Unspecified else style.letterSpacing
    return style.copy(color = ink, textAlign = alignment, localeList = interfaceLocale(), letterSpacing = tracking)
}

/**
 * Whether a run holds Chinese (or any CJK ideograph or kana), which is set without the Latin
 * tracking: the CJK face is as wide as the iPhone's already.
 */
internal fun holdsCjk(text: CharSequence): Boolean = text.any { character ->
    val block = Character.UnicodeBlock.of(character)
    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA
}

/** The locale the interface language's words are shaped in. */
@Composable
fun interfaceLocale(): LocaleList =
    if (L10n.language == L10n.chinese) LocaleList("zh-Hans-CN") else LocaleList("en-US")

private fun overflow(lineLimit: Int?, truncation: Truncation): TextOverflow = when {
    lineLimit == null -> TextOverflow.Clip
    lineLimit == 1 && truncation == Truncation.head -> TextOverflow.StartEllipsis
    lineLimit == 1 && truncation == Truncation.middle -> TextOverflow.MiddleEllipsis
    else -> TextOverflow.Ellipsis
}

/** `.monospacedDigit()`: figures that keep their width while they count. */
fun TextStyle.monospacedDigit(): TextStyle = copy(fontFeatureSettings = "tnum")
