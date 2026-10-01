package com.junbingao.remotecontrol.android.screens.chat.markdown

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.markdown.MarkdownLink
import java.net.URI

/**
 * Renders agent Markdown natively: a bounded block parser, a composable for every block, and a
 * sandboxed web view only for formulas and diagrams.
 *
 * Streaming text keeps one parser per visible view and reparses at most ten times a second, so a
 * long answer does not stall the timeline.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, onFileLink: ((String) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    // Seeded only when the view is first mounted; streaming updates go through the parser's
    // coalescing worker.
    val parser = remember { MarkdownParseModel(initialSource = text, scope = scope) }
    LaunchedEffect(text) { parser.submit(text) }
    DisposableEffect(parser) { onDispose { parser.cancel() } }
    val context = LocalContext.current
    val open = remember(context, onFileLink) { { link: String -> MarkdownLinkOpening.open(context, link, onFileLink) } }
    CompositionLocalProvider(LocalMarkdownLink provides open) {
        Foreground(Theme.ink, SystemFont.body) {
            SelectionContainer {
                Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Theme.Space.small + 2.dp)) {
                    for (block in parser.document.blocks) {
                        key(block.id) { MarkdownBlockView(block) }
                    }
                }
            }
        }
    }
}

/** What a tap on a link in the Markdown being drawn does. */
internal val LocalMarkdownLink = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The web and mail leave the app; a path to a file on the device goes to whoever asked to hear of
 * one, with an editor's `:line` suffix taken off; anything else is dropped.
 */
internal object MarkdownLinkOpening {
    fun open(context: Context, link: String, onFileLink: ((String) -> Unit)?) {
        if (MarkdownLinks.opensOutside(link)) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // Nothing on the phone opens this kind of link; the tap does nothing, as a dead link would.
            }
            return
        }
        val url = runCatching { URI(link) }.getOrNull() ?: return
        val path = MarkdownLink.filePath(url) ?: return
        onFileLink?.invoke(path)
    }
}

/** A unified patch with coloured additions and removals. */
@Composable
fun PatchView(patch: String, foldedLineLimit: Int = 20) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val lines = remember(patch) { patch.split("\n") }
    val visible = if (expanded) lines else lines.take(foldedLineLimit)
    val added = Theme.added
    val removed = Theme.removed
    val secondary = Theme.inkSecondary
    Column(Modifier.fillMaxWidth().background(Theme.surfaceSunken, ContinuousShape(Theme.Radius.control))) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            SelectionContainer {
                Column(Modifier.width(IntrinsicSize.Max)) {
                    for (line in visible) {
                        val (ink, fill) = when {
                            line.startsWith("+") && !line.startsWith("+++") -> added to added.copy(alpha = 0.08f)
                            line.startsWith("-") && !line.startsWith("---") -> removed to removed.copy(alpha = 0.08f)
                            else -> secondary to Color.Transparent
                        }
                        Text(
                            line.ifEmpty { " " },
                            Modifier
                                .fillMaxWidth()
                                .background(fill)
                                .padding(horizontal = Theme.Space.small, vertical = 1.dp),
                            style = Theme.mono,
                            color = ink,
                        )
                    }
                }
            }
        }
        if (lines.size > foldedLineLimit) {
            Button(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().heightIn(min = Theme.Touch.minimum),
            ) {
                Text(
                    if (expanded) L10n.string("Show less") else L10n.string("Show all %lld lines", lines.size),
                    style = SystemFont.footnote,
                    color = Theme.inkSecondary,
                )
            }
        }
    }
}

/** Fixed-width output with a fold beyond roughly twenty lines. */
@Composable
fun OutputBlock(text: String, foldedLineLimit: Int = 20, isTruncated: Boolean = false, onOpenFull: (() -> Unit)? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val lines = remember(text) { text.split("\n") }
    val folds = lines.size > foldedLineLimit
    val offersFull = isTruncated && onOpenFull != null
    Column(
        Modifier.fillMaxWidth().background(Theme.surfaceSunken, ContinuousShape(Theme.Radius.control)),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.tight),
    ) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(
                    if (expanded) text else lines.take(foldedLineLimit).joinToString("\n"),
                    Modifier.padding(Theme.Space.small),
                    style = Theme.mono,
                    color = Theme.ink,
                )
            }
        }
        Foreground(Theme.inkSecondary, SystemFont.footnote) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Theme.Space.small)
                    .padding(bottom = if (folds || isTruncated) Theme.Space.small else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(Theme.Space.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (folds) {
                    Button(onClick = { expanded = !expanded }) {
                        Text(if (expanded) L10n.string("Fold") else L10n.string("Show all %lld lines", lines.size))
                    }
                }
                if (offersFull) {
                    Button(onClick = { onOpenFull() }) { Text(L10n.string("Open full output")) }
                }
            }
        }
    }
}
