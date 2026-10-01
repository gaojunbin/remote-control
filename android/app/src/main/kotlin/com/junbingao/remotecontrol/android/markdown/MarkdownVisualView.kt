package com.junbingao.remotecontrol.android.markdown

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.junbingao.remotecontrol.android.design.Foreground
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.LocalAppearance
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n

/**
 * A diagram or a formula, drawn by the iPhone's renderer in a web view: the port of
 * `MarkdownVisualView.swift`. Only a formula or a diagram enters it; navigation, the conversation
 * and every word of text stay native. It has no cookies, no files, no network and no bridge
 * beyond the one status message (`MarkdownSurface`).
 *
 * The body text size follows the phone's font size as `@ScaledMetric(relativeTo: .body)` follows
 * Dynamic Type, times [textScale], the chat's own text scale.
 */
@Composable
fun MarkdownVisualView(
    kind: MarkdownVisualKind,
    source: String,
    modifier: Modifier = Modifier,
    parts: List<MarkdownInlinePart> = emptyList(),
    maximumHeight: Dp = 720.dp,
    textScale: Float = 1f,
    onLink: ((Uri) -> Unit)? = null,
) {
    if (MarkdownVisualDocument.isTooLong(kind, source)) {
        Foreground(SystemColor.secondaryLabel) {
            Label(L10n.string("Too long to render. Read the source instead."), Sf.docText, modifier, font = SystemFont.caption)
        }
        return
    }
    val context = LocalContext.current
    val dark = LocalAppearance.current.isDark
    val fontPixels = SystemFont.body.fontSize.value * LocalDensity.current.fontScale * textScale
    var height by remember { mutableStateOf(72.dp) }
    var error by remember { mutableStateOf<String?>(null) }
    val open = onLink ?: { url: Uri -> openOutside(context, url) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(
            factory = { MarkdownSurface(it) },
            modifier = Modifier.fillMaxWidth().height(height),
            update = { surface ->
                surface.onHeight = { measured -> height = measured.coerceIn(36.0, maximumHeight.value.toDouble()).dp }
                surface.onError = { message -> error = message }
                surface.onLink = open
                surface.show(kind, source, parts, dark, fontPixels.toDouble())
            },
            onRelease = { it.release() },
        )
        error?.let { message ->
            SelectionContainer {
                Text(message, style = SystemFont.caption, color = SystemColor.secondaryLabel)
            }
        }
    }
}

private fun openOutside(context: android.content.Context, url: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the phone opens this kind of link; the tap does nothing, as a dead link would.
    }
}
