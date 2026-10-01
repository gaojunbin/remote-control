package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.card
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.screens.sessions.BarFoot
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.BarTextButton

/** The same steps, spelled out for a host without curl. */
@Composable
fun ManualInstallView(command: String, code: String, dismiss: () -> Unit) {
    NavigationScreen(
        L10n.string("Manual install"),
        displayMode = TitleDisplayMode.inline,
        showsBack = false,
        trailing = { BarTextButton(L10n.string("Done"), dismiss, prominent = true) },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insets.padding())
                .padding(top = BarFoot.height)
                .padding(Theme.Space.page),
            verticalArrangement = Arrangement.spacedBy(Theme.Space.medium),
        ) {
            Text(L10n.string("Download the installer, then run it with your pairing code."), style = SystemFont.subheadline, color = Theme.inkSecondary)
            SelectionContainer {
                Text(breakingAfterSlashes(command.replace(" | sh -s --", " -o install.sh\nsh install.sh")), Modifier.card(), style = Theme.mono)
            }
            Text(
                L10n.string("The code %@ can be used once and expires ten minutes after it was issued.", code),
                style = SystemFont.footnote,
                color = Theme.inkSecondary,
            )
        }
    }
}
