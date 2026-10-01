package com.junbingao.remotecontrol.android.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.junbingao.remotecontrol.android.design.BorderlessButtonStyle
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.scrollIndicator
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.NavigationMetrics
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.BarTextButton

/**
 * Read the report first, then decide whether to share it.
 *
 * A debug build also offers the primitives' gallery here ([openGallery]), the developer's way in
 * to it from the app; its words are a tool's and not product strings.
 */
@Composable
fun DiagnosticsView(report: String, dismiss: () -> Unit, openGallery: (() -> Unit)? = null) {
    val context = LocalContext.current
    NavigationScreen(
        L10n.string("Diagnostics"),
        displayMode = TitleDisplayMode.inline,
        showsBack = false,
        trailing = { BarTextButton(L10n.string("Done"), dismiss, prominent = true, tag = "diagnostics.done") },
    ) { insets ->
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .scrollIndicator(scroll, top = insets.top, bottom = insets.bottom)
                .verticalScroll(scroll)
                .padding(insets.padding())
                .padding(top = NavigationMetrics.barFoot)
                .padding(Theme.Space.page),
            verticalArrangement = Arrangement.spacedBy(Theme.Space.medium),
        ) {
            Text(
                L10n.string("This is everything the report contains. Read it before you share it."),
                style = SystemFont.subheadline,
                color = Theme.inkSecondary,
            )
            SelectionContainer {
                Text(report, Modifier.fillMaxWidth().testTag("diagnostics.report"), style = Theme.mono)
            }
            Button(onClick = { share(context, report) }, Modifier.testTag("diagnostics.share"), style = BorderlessButtonStyle) {
                Box(Modifier.heightIn(min = Theme.Touch.minimum), contentAlignment = Alignment.CenterStart) {
                    Label(L10n.string("Share this report"), Sf.squareAndArrowUp)
                }
            }
            openGallery?.let { open ->
                Button(onClick = open, Modifier.testTag("diagnostics.gallery"), style = BorderlessButtonStyle) {
                    Box(Modifier.heightIn(min = Theme.Touch.minimum), contentAlignment = Alignment.CenterStart) {
                        Text("Primitive gallery")
                    }
                }
            }
        }
    }
}

/** `ShareLink(item:)`: the system's share sheet, with the report as plain text and nothing else. */
private fun share(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
    try {
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the phone takes text; the report is still on screen to be copied.
    }
}
