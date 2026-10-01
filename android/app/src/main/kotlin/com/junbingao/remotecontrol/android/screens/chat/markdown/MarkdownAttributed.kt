package com.junbingao.remotecontrol.android.screens.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.junbingao.remotecontrol.android.markdown.MarkdownInlinePart

/**
 * `markdownAttributed`: a run of inline Markdown as the text the conversation draws — bold, italic,
 * code in the monospaced face, struck through, and links in [linkColor], the app's tint, as
 * SwiftUI draws a link in a `Text`. A tap on a link goes to [onLink], which decides what may be
 * opened (`MarkdownText`'s `openURL`).
 */
fun markdownAttributed(source: String, linkColor: Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    for (run in MarkdownInlineParser.runs(source)) {
        val style = SpanStyle(
            fontWeight = if (run.strong) FontWeight.Bold else null,
            fontStyle = if (run.em) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            textDecoration = if (run.strike) TextDecoration.LineThrough else null,
        )
        val link = run.link
        if (link == null) {
            withStyle(style) { append(run.text) }
        } else {
            val annotation = LinkAnnotation.Clickable(
                tag = link,
                styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
                linkInteractionListener = { onLink(link) },
            )
            withLink(annotation) { withStyle(style) { append(run.text) } }
        }
    }
}

/**
 * The same reading as the renderer's runs, for a paragraph whose formulas go to the web view:
 * `MarkdownVisualView.swift` keeps a link only when it is http, https or mailto.
 */
fun markdownRuns(source: String): List<MarkdownInlinePart.Run> =
    MarkdownInlineParser.runs(source).map { run ->
        MarkdownInlinePart.Run(
            text = run.text,
            strong = run.strong,
            em = run.em,
            code = run.code,
            strike = run.strike,
            link = run.link?.takeIf { MarkdownLinks.opensOutside(it) },
        )
    }

/** Which links leave the app: the web and mail, and nothing else. */
object MarkdownLinks {
    fun opensOutside(link: String): Boolean {
        val scheme = link.substringBefore(':', missingDelimiterValue = "").lowercase()
        return scheme == "https" || scheme == "http" || scheme == "mailto"
    }
}
