package com.junbingao.remotecontrol.android.screens.chat.markdown

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.monospaced
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.icons.SfSymbol
import com.junbingao.remotecontrol.android.markdown.MarkdownVisualKind
import com.junbingao.remotecontrol.android.markdown.MarkdownVisualView
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.android.system.Sheet
import kotlinx.coroutines.delay

/**
 * A fenced block: its language over the code in the monospaced face, scrolling sideways, with
 * copy and share; a diagram or a formula is drawn by the renderer once the fence has closed, with a
 * switch back to its source and a way to open it full screen.
 */
@Composable
internal fun MarkdownCodeCard(language: String, source: String, closed: Boolean) {
    var sourceVisible by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val kind = MarkdownCodeCards.kind(language)
    val rendered = kind != null && closed && !sourceVisible
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(2_000)
        copied = false
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(SystemColor.label.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                when (kind) {
                    MarkdownVisualKind.diagram -> L10n.string("Diagram")
                    MarkdownVisualKind.math -> L10n.string("Formula")
                    else -> language.ifEmpty { L10n.string("Code") }
                },
                style = SystemFont.caption.weight(FontWeight.Medium),
                color = SystemColor.secondaryLabel,
            )
            if (!closed) Text(L10n.string("Still writing"), style = SystemFont.caption2, color = SystemColor.secondaryLabel)
            Spacer(Modifier.weight(1f))
            Foreground(Theme.accent, SystemFont.body) {
                if (kind != null && closed) {
                    CodeButton(
                        if (sourceVisible) Sf.chartXyaxisLine else Sf.chevronLeftForwardslashChevronRight,
                        L10n.string(if (sourceVisible) "Show the rendered version" else "Show the source"),
                    ) { sourceVisible = !sourceVisible }
                    CodeButton(Sf.arrowUpLeftAndArrowDownRight, L10n.string("Open full screen")) { expanded = true }
                }
                CodeButton(if (copied) Sf.checkmark else Sf.docOnDoc, L10n.string(if (copied) "Copied" else "Copy the whole block")) {
                    MarkdownCodeCards.copy(context, source)
                    copied = true
                }
                CodeButton(Sf.squareAndArrowUp, L10n.string("Share this block")) { MarkdownCodeCards.share(context, source) }
            }
        }
        if (rendered && kind != null) MarkdownVisualView(kind, source) else CodeSource(source)
    }
    Sheet(isPresented = expanded, onDismiss = { expanded = false }) {
        NavigationScreen(
            L10n.string(if (kind == MarkdownVisualKind.diagram) "Diagram" else "Formula"),
            displayMode = TitleDisplayMode.inline,
            showsBack = false,
            trailing = { BarTextButton(L10n.string("Done"), onClick = { expanded = false }, prominent = true) },
        ) { insets ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = insets.top, bottom = insets.bottom)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (kind != null) MarkdownVisualView(kind, source, maximumHeight = 1600.dp)
                CodeSource(source)
            }
        }
    }
}

/** The code itself, in the callout size, sideways and never wrapped. */
@Composable
private fun CodeSource(source: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics {
                contentDescription = L10n.string("Code")
                stateDescription = source
            },
    ) {
        SelectionContainer {
            Text(
                source.ifEmpty { " " },
                Modifier.padding(vertical = 5.dp),
                style = SystemFont.callout.monospaced().spaced(4f),
            )
        }
    }
}

@Composable
private fun CodeButton(symbol: SfSymbol, description: String, onClick: () -> Unit) {
    Button(onClick, Modifier.size(40.dp, 44.dp).semantics { contentDescription = description }) { Icon(symbol) }
}

internal object MarkdownCodeCards {
    fun kind(language: String): MarkdownVisualKind? = when (language) {
        "mermaid" -> MarkdownVisualKind.diagram
        "math", "latex", "tex" -> MarkdownVisualKind.math
        else -> null
    }

    fun copy(context: Context, source: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(null, source))
    }

    /** The system's share sheet, as `ShareLink` opens the iPhone's. */
    fun share(context: Context, source: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, source)
        try {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Nothing on the phone takes shared text; the tap does nothing.
        }
    }
}
