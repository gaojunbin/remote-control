package com.junbingao.remotecontrol.android.gallery

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.markdown.MarkdownVisualKind
import com.junbingao.remotecontrol.android.markdown.MarkdownVisualView
import com.junbingao.remotecontrol.android.scanner.StaticCodeScanner
import com.junbingao.remotecontrol.android.terminal.TerminalFeed
import com.junbingao.remotecontrol.android.terminal.TerminalHost

/** The services that draw: the terminal, a Mermaid diagram and a formula, and the scanner's stand-in. */
@Composable
internal fun ServicesGallery() {
    val feed = remember { TerminalFeed() }
    var scanned by remember { mutableStateOf("") }
    LaunchedEffect(feed) {
        feed.write("me@mac-studio-office ~/dev/remote-control % ls\r\nandroid  docs  gateway  ios  macos  web\r\nme@mac-studio-office ~/dev/remote-control % ".toByteArray())
    }
    GalleryScaffold(GalleryPages.services.title) {
        Specimen("Terminal") {
            TerminalHost(
                feed,
                fontSize = 12.0,
                onSize = { _, _ -> },
                onInput = {},
                onFontSize = {},
                scaledFontSize = { base, scale -> (base * scale).coerceIn(8.0, 24.0) },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
        }
        Specimen("Mermaid diagram and a formula") {
            MarkdownVisualView(MarkdownVisualKind.diagram, "graph LR\n  Phone --> Gateway --> Device")
            MarkdownVisualView(MarkdownVisualKind.math, "e^{i\\pi} + 1 = 0")
        }
        Specimen("Pairing scanner stand-in") {
            StaticCodeScanner("https://demo.remote-control.invalid/pair#RC7K42QX9M").Viewfinder({ scanned = it }, Modifier.fillMaxWidth().height(80.dp))
            if (scanned.isNotEmpty()) Text(scanned, style = Theme.Text.metaMono, color = Theme.inkSecondary)
        }
    }
}
