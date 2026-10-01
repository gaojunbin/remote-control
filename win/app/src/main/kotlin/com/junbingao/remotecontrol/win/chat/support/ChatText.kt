package com.junbingao.remotecontrol.win.chat.support

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.junbingao.remotecontrol.win.design.ExactHeight
import com.junbingao.remotecontrol.win.design.FaceRuns
import com.junbingao.remotecontrol.win.design.LocalContentColor
import com.junbingao.remotecontrol.win.design.PrimaryBaseline
import com.junbingao.remotecontrol.win.design.SystemFace
import com.junbingao.remotecontrol.win.design.TextRendering
import com.junbingao.remotecontrol.win.design.TextStyle
import com.junbingao.remotecontrol.win.design.composeTextStyle
import com.junbingao.remotecontrol.win.design.cssLineBox
import com.junbingao.remotecontrol.win.design.exactHeight
import com.junbingao.remotecontrol.win.design.recoloured
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource

/**
 * The chat's text: the foundation's `Text` on the browser's baselines, with what the Mac's
 * conversation adds to it — SwiftUI's `.textSelection(.enabled)`, inline images (a task list's
 * checkbox, the room before a code chip), and a layer drawn behind the glyphs from where the
 * layout put them (the chips' tint, the links' underline). The exact height it reports is the
 * text's, whether or not it can be selected, so the stack around it keeps the Mac's fractions.
 *
 * A text of several lines is drawn a line at a time, each on the browser's baselines at its exact
 * place (`ChatLines`): Skia gives every line a whole number of pixels, so a 12-point code line of
 * 1.6 would otherwise advance 39 pixels where the Mac's advances 38.4.
 *
 * A text whose spans name no colour of their own and that holds no inline image is drawn in the
 * neutral grey and recoloured, as every `Text` is; a coloured one is drawn in its own colours.
 */
@Composable
fun ChatText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Start,
    selectable: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    decorations: (DrawScope.(TextLayoutResult) -> Unit)? = null,
) {
    val language = InterfaceLanguageSource.current
    val tracking = style.tracking * style.size
    val faced = remember(text, style, language) {
        val primary = SystemFace.face(style.size, style.weight, style.mono)
        ChatKerning.restore(FaceRuns.apply(text, style.size, style.weight, style.mono, tracking, language), text, primary.tracking + tracking)
    }
    val line = ChatLineStyle(style, color, textAlign, inlineContent, decorations)
    val exact = remember { ExactHeight() }
    val wraps = softWrap && maxLines != 1
    val body: @Composable () -> Unit = when {
        wraps -> {
            { ChatWrappedLines(faced, line, exact) }
        }
        maxLines == Int.MAX_VALUE && faced.text.contains('\n') -> {
            { ChatLines(ChatLines.atLineFeeds(faced), line, exact) }
        }
        else -> {
            { ChatLine(faced, line, exact, overflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip) }
        }
    }
    if (selectable) SelectionContainer(modifier.exactHeight(exact)) { body() } else ChatPass(exact, modifier) { body() }
}

/** The same for a plain string. */
@Composable
fun ChatText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Start,
    selectable: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
) {
    ChatText(AnnotatedString(text), style, modifier, color, textAlign, selectable, maxLines, softWrap)
}

/** How every line of one text is drawn. */
internal class ChatLineStyle(
    val style: TextStyle,
    val color: Color,
    val textAlign: TextAlign,
    val inlineContent: Map<String, InlineTextContent>,
    val decorations: (DrawScope.(TextLayoutResult) -> Unit)?,
) {
    /** No span names a colour of its own and nothing is an image: the glyphs can be drawn in the neutral grey and recoloured. */
    fun isPlain(text: AnnotatedString): Boolean =
        inlineContent.isEmpty() && text.spanStyles.none { it.item.color != Color.Unspecified || it.item.brush != null }
}

/**
 * One line of a text, already in its faces, as one `BasicText` on the browser's line box; what
 * ends it — a line feed, a space — is kept and not drawn. Its exact height goes to `exact`.
 */
@Composable
internal fun ChatLine(
    text: AnnotatedString,
    line: ChatLineStyle,
    exact: ExactHeight,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip,
    snapToPoint: Boolean = true,
) {
    val style = line.style
    val language = InterfaceLanguageSource.current
    val ink = if (line.color != Color.Unspecified) line.color else LocalContentColor.current
    val neutral = TextRendering.neutralInk?.takeIf { line.isPlain(text) }
    val tracking = style.tracking * style.size
    val composeStyle = composeTextStyle(style.size, style.weight, style.mono, tracking, style.lineBox, neutral ?: ink, line.textAlign, language)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The primary face's line, as drawn, is the one put on the browser's baseline — a code chip or
    // a Chinese character in the line does not move it, as it does not move SwiftUI's.
    val aligned = PrimaryBaseline.of(composeStyle, style.weight, style.mono, style.lineBox, measurer, density)
    val laidOut = remember { arrayOfNulls<TextLayoutResult>(1) }
    val decorations = line.decorations
    BasicText(
        text = text,
        modifier = modifier
            .cssLineBox(style.lineBox, style.baseline, snapToPoint = snapToPoint, exact = exact, aligned = aligned)
            .then(if (decorations != null) Modifier.drawBehind { laidOut[0]?.let { decorations(it) } } else Modifier)
            .then(if (neutral != null) Modifier.recoloured(ink) else Modifier)
            .exactHeight(exact),
        style = composeStyle,
        onTextLayout = { laidOut[0] = it },
        overflow = overflow,
        softWrap = false,
        maxLines = 1,
        inlineContent = line.inlineContent,
    )
}
