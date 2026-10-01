package com.junbingao.remotecontrol.win.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.chat.support.ChatText
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.TextStyle
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.platform.MarkdownEngine

/**
 * `web/src/features/chat/MarkdownText.tsx` and `Markdown.tsx`: the agent's prose, rendered onto
 * the canvas with no bubble — 14 points on a 1.65 line, breaking a word that would not otherwise
 * fit (`docs/DESIGN.md` § "The timeline").
 */
@Composable
fun MarkdownText(text: String) {
    when (val document = remember(text) { MarkdownEngine.shared.document(text) }) {
        is ChatMarkdown.Plain -> ChatText(document.text, css(FontSize.fs14, lineHeight = 1.65f), Modifier.fillMaxWidth())
        is ChatMarkdown.Blocks -> MDStackView(
            document.stack,
            Modifier.padding(top = document.stack.top.dp, bottom = document.stack.bottom.dp),
        )
    }
}

/** Blocks one after another, the collapsed margin between each pair. */
@Composable
fun MDStackView(stack: MDStack, modifier: Modifier = Modifier) {
    VStack(modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        for ((index, block) in stack.blocks.withIndex()) {
            MDBlockView(block, Modifier.padding(top = stack.gaps[index].dp))
        }
    }
}

/** One block of a message. */
@Composable
fun MDBlockView(block: MDBlock, modifier: Modifier = Modifier) {
    when (val kind = block.kind) {
        is MDBlock.Kind.Text -> MDParagraph(kind.text, modifier)
        is MDBlock.Kind.List -> MDListView(kind.list, modifier)
        is MDBlock.Kind.Quote -> MDQuoteView(kind.stack, modifier)
        is MDBlock.Kind.Group -> MDStackView(kind.stack, modifier)
        is MDBlock.Kind.Code -> MDCodeBlock(kind.code, modifier)
        is MDBlock.Kind.Table -> MDTableView(kind.table, modifier)
        MDBlock.Kind.Rule -> Box(modifier.fillMaxWidth().height(1.dp).background(Palette.line))
    }
}

/** A paragraph, a heading or a tight list item's text. */
@Composable
fun MDParagraph(text: MDText, modifier: Modifier = Modifier) {
    MDRichText(
        text.inlines,
        TextStyle(size = text.size, weight = text.weight.fontWeight, lineHeight = 1.65f, tracking = text.tracking),
        text.weight,
        LocalMdInk.current,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Inline content as one selectable text on the browser's line boxes, the code chips' tint and the
 * links' underlines drawn behind its glyphs from where its layout put them.
 */
@Composable
fun MDRichText(
    inlines: List<MDInline>,
    style: TextStyle,
    weight: MDWeight,
    ink: Color,
    alignment: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
) {
    val text = remember(inlines, style.size, weight) { MDInlineText.build(inlines, style.size, weight) }
    ChatText(
        text.text,
        style,
        modifier,
        color = ink,
        textAlign = alignment,
        inlineContent = text.inlineContent,
        decorations = if (text.isDecorated) { layout -> text.draw(this, layout, ink) } else null,
    )
}

/** The ink a message's text inherits: the page's, or a quote's quieter one. */
val LocalMdInk = compositionLocalOf { Palette.ink }
