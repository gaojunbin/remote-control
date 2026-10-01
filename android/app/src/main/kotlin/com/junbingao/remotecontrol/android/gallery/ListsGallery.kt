package com.junbingao.remotecontrol.android.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.FieldLabel
import com.junbingao.remotecontrol.android.design.OnlineDot
import com.junbingao.remotecontrol.android.design.SettingsFooter
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.settings
import com.junbingao.remotecontrol.android.design.settingsRowLayout
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActionRole
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.android.system.MenuItem
import com.junbingao.remotecontrol.android.system.RowStyle
import com.junbingao.remotecontrol.android.system.SwipeAction
import com.junbingao.remotecontrol.android.system.Switch

/** An inset grouped list with every kind of row the screens use. */
@Composable
internal fun ListsGallery() {
    var registration by remember { mutableStateOf(false) }
    // The list is built outside composition, so its colours are read here first.
    val danger = Theme.danger
    val grey = Theme.inkSecondary
    NavigationScreen(GalleryPages.lists.title, displayMode = TitleDisplayMode.large) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), contentPadding = insets.padding()) {
            section(key = "switch", footer = { SettingsFooter(L10n.string("Anyone with the gateway address can create an account")) }) {
                row(key = "registration", style = RowStyle.settings) {
                    Row(Modifier.settingsRowLayout(), verticalAlignment = Alignment.CenterVertically) {
                        Text(L10n.string("Registration"), Modifier.weight(1f), style = Theme.Text.label, color = Theme.ink)
                        Switch(registration, { registration = it })
                    }
                }
            }
            section(key = "accounts", header = { FieldLabel("Accounts") }) {
                for ((name, line) in listOf("admin" to "Admin · Active · 3 devices", "alice" to "Member · Active · 1 device", "bob" to "Member · Disabled")) {
                    row(
                        key = name,
                        style = RowStyle(separator = true),
                        swipeActions = listOf(
                            SwipeAction(L10n.string("Delete"), Sf.trash, danger) {},
                            SwipeAction(L10n.string("Disable"), Sf.handRaised, grey) {},
                            SwipeAction(L10n.string("Reset password"), Sf.key, grey) {},
                        ),
                        contextMenu = listOf(
                            MenuItem.Action(L10n.string("Reset password"), Sf.key),
                            MenuItem.Action(L10n.string("Disable"), Sf.handRaised),
                            MenuItem.Action(L10n.string("Delete"), Sf.trash, role = ActionRole.destructive),
                        ),
                    ) {
                        Text(name, style = Theme.Text.label, color = Theme.ink)
                        Text(line, style = Theme.Text.meta, color = Theme.inkSecondary)
                    }
                }
            }
            section(key = "devices", header = { FieldLabel("Devices") }) {
                row(key = "online", style = RowStyle(separator = false)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OnlineDot(online = true)
                        Text("mac-studio-office", style = Theme.Text.title, color = Theme.ink)
                    }
                }
            }
        }
    }
}
