package com.junbingao.remotecontrol.win.sessions.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.RecentDirectory
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.FirstTextBaseline
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.sessions.controls.SizedField
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S

/**
 * The working directory: its label with Browse… at the trailing edge, the path in a tall mono field
 * that says whether it exists, and up to four recent directories under it.
 */
@Composable
internal fun DirectoryField(form: NewSessionForm, browse: () -> Unit) {
    val probe = form.probe.status(form.deviceID, form.cwd)
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        HStack(Modifier.fillMaxWidth(), spacing = Space.sp3, alignment = Alignment.FirstTextBaseline) {
            FieldLabel(S.newSession.workingDirectory, Modifier.weight(1f))
            Disabled(form.deviceID == null) { BrowseLink(browse) }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            SizedField(form.cwd, form::setPath, mono = true, fontSize = FontSize.fs15, height = 46.dp, trailingPadding = 74.dp)
            val word = DirectoryField.word(probe.status)
            if (word != null) {
                Text(
                    word, css(FontSize.fs12), Modifier.padding(end = Space.sp4),
                    color = if (probe.status == DirectoryProbe.Status.missing) Palette.danger else Palette.inkSecondary,
                )
            }
        }
        if (form.recent.isNotEmpty()) {
            VStack(Modifier.fillMaxWidth().padding(top = Space.sp2), spacing = 0.dp) {
                for (entry in form.recent.take(4)) RecentRow(entry) { form.setPath(entry.path) }
            }
        }
    }
}

internal object DirectoryField {
    fun word(status: DirectoryProbe.Status): String? = when (status) {
        DirectoryProbe.Status.exists -> S.newSession.dirExists
        DirectoryProbe.Status.missing -> S.newSession.dirMissing
        DirectoryProbe.Status.checking -> S.newSession.dirChecking
        DirectoryProbe.Status.idle -> null
    }
}

/** `.label-row .link-btn`: Browse…, in the secondary ink, underlined in the ink under the pointer. */
@Composable
private fun BrowseLink(action: () -> Unit) {
    Button(action, style = BrowseLinkStyle) {
        val text = S.newSession.browse
        Text(if (LocalLinkLit.current) AnnotatedString(text, SpanStyle(textDecoration = TextDecoration.Underline)) else AnnotatedString(text), css(FontSize.fs13))
    }
}

/** Whether the link around a label is under the pointer, which underlines it. */
private val LocalLinkLit = compositionLocalOf { false }

private val BrowseLinkStyle = ButtonStyle { configuration, modifier ->
    val lit = configuration.isEnabled && configuration.isHovered
    WithForeground(if (lit) Palette.ink else Palette.inkSecondary) {
        CompositionLocalProvider(LocalLinkLit provides lit) { Box(modifier) { configuration.label() } }
    }
}

/** One recent directory: the path in mono and when it was last used, a tint under the pointer. */
@Composable
private fun RecentRow(entry: RecentDirectory, action: () -> Unit) {
    Button(action, Modifier.fillMaxWidth(), style = RecentRowStyle) {
        HStack(Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = Space.sp2), spacing = Space.sp3) {
            Text(Format.tildePath(entry.path), css(FontSize.fs13, mono = true), Modifier.weight(1f), lineLimit = 1)
            Text(Format.relativeAgo(entry.lastUsed), css(FontSize.fs12), color = Palette.inkSecondary)
        }
    }
}

private val RecentRowStyle = ButtonStyle { configuration, modifier ->
    Box(modifier.background(if (configuration.isHovered) Palette.surfaceHover else Color.Transparent, RoundedCornerShape(Radius.sm))) {
        configuration.label()
    }
}
