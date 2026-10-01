package com.junbingao.remotecontrol.android.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ChipButtonStyle
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.Alert
import com.junbingao.remotecontrol.android.system.AlertAction
import com.junbingao.remotecontrol.android.system.AlertTextField
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.android.system.ConfirmationDialog
import com.junbingao.remotecontrol.android.system.FullScreenCover
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.android.system.Menu
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.Sheet
import com.junbingao.remotecontrol.android.system.SheetDetent

/** Which presentation the gallery opens, so a screenshot test can open one without a tap. */
enum class GalleryOverlay { sheet, mediumSheet, alert, stackedAlert, fieldAlert, dialog, menu, cover }

/** Every way the iPhone presents something over a screen, each behind a button. */
@Composable
internal fun PresentationsGallery(initial: GalleryOverlay? = null) {
    var open by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("macbook-air") }
    var language by remember { mutableStateOf("zh") }
    val close = { open = null }
    NavigationScreen(GalleryPages.presentations.title, displayMode = TitleDisplayMode.inline) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), contentPadding = insets.padding()) {
            section(key = "overlays") {
                for (overlay in GalleryOverlay.entries.filter { it != GalleryOverlay.menu }) {
                    row(key = overlay.name, onClick = { open = overlay }, tag = "gallery.open.${overlay.name}") {
                        Text(overlay.name, style = Theme.Text.label, color = Theme.ink)
                    }
                }
            }
            item(key = "menu") {
                Menu(
                    listOf(
                        MenuItem.Action("Chinese", checked = language == "zh") { language = "zh" },
                        MenuItem.Action("English", checked = language == "en") { language = "en" },
                        MenuItem.Action("Japanese", checked = language == "ja") { language = "ja" },
                    ),
                    Modifier.padding(start = 16.dp, top = 12.dp),
                ) {
                    Button(onClick = {}, style = ChipButtonStyle) { Text(L10n.string("Dictation language")) }
                }
            }
        }
    }
    Sheet(open == GalleryOverlay.sheet, close) { GallerySheetBody(close) }
    Sheet(open == GalleryOverlay.mediumSheet, close, detents = listOf(SheetDetent.medium, SheetDetent.large)) {
        GallerySheetBody(close)
    }
    Alert(
        open == GalleryOverlay.alert,
        L10n.string("Revoke device"),
        close,
        message = L10n.string(
            "Revoke %@? Its token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts.",
            "macbook-air",
        ),
        actions = listOf(
            AlertAction(L10n.string("Revoke device"), ActionRole.destructive),
            AlertAction(L10n.string("Cancel"), ActionRole.cancel),
        ),
    )
    Alert(
        open == GalleryOverlay.stackedAlert,
        L10n.string("Delete account"),
        close,
        message = "Delete alice and its 1 device? The token stops working and its sessions leave this gateway. The machine keeps its agents and transcripts.",
        actions = listOf(
            AlertAction(L10n.string("Delete account"), ActionRole.destructive),
            AlertAction(L10n.string("Cancel"), ActionRole.cancel),
        ),
    )
    Alert(
        open == GalleryOverlay.fieldAlert,
        L10n.string("Rename device"),
        close,
        actions = listOf(AlertAction(L10n.string("Cancel"), ActionRole.cancel), AlertAction(L10n.string("Rename"))),
        textField = AlertTextField(L10n.string("Name"), name, { name = it }),
    )
    ConfirmationDialog(
        open == GalleryOverlay.dialog,
        L10n.string("Sign out of this gateway?"),
        close,
        message = L10n.string("Cached sessions and drafts leave this device. Nothing changes on your machines."),
        actions = listOf(AlertAction(L10n.string("Sign out"), ActionRole.destructive)),
    )
    FullScreenCover(open == GalleryOverlay.cover, close) {
        NavigationScreen(
            L10n.string("Scan a code"),
            displayMode = TitleDisplayMode.inline,
            showsBack = false,
            trailing = { BarTextButton(L10n.string("Cancel"), close) },
            background = SystemColor.label,
        ) { }
    }
}

@Composable
private fun GallerySheetBody(close: () -> Unit) {
    NavigationScreen(
        L10n.string("Add device"),
        displayMode = TitleDisplayMode.inline,
        showsBack = false,
        leading = { BarTextButton(L10n.string("Cancel"), close) },
        trailing = { BarTextButton(L10n.string("Done"), close, enabled = false, prominent = true) },
    ) { insets ->
        Text(
            L10n.string("Run one command on the machine where your agents live. It dials out to the gateway, so nothing is exposed on the host."),
            Modifier.padding(insets.padding()).padding(horizontal = 20.dp, vertical = 10.dp),
            style = com.junbingao.remotecontrol.android.design.SystemFont.subheadline,
            color = Theme.inkSecondary,
        )
    }
}
