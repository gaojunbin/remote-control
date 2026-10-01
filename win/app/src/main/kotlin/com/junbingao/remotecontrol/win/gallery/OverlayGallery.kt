package com.junbingao.remotecontrol.win.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WebField
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.overlay.ConfirmDialog
import com.junbingao.remotecontrol.win.design.overlay.Drawer
import com.junbingao.remotecontrol.win.design.overlay.MenuOption
import com.junbingao.remotecontrol.win.design.overlay.Modal
import com.junbingao.remotecontrol.win.design.overlay.SelectMenu
import com.junbingao.remotecontrol.win.design.pill
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.strings.S

/**
 * The overlays, open for a render: a menu open from a pill, and — each in a scenario of its own —
 * a modal with a footer, the drawer and a confirmation.
 */
@Composable
fun OverlayGallery(kind: OverlayGalleryKind) {
    var modal by remember { mutableStateOf(false) }
    var drawer by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf<String?>("sonnet") }
    var name by remember { mutableStateOf("ci-runner-01") }
    VStack(Modifier.fillMaxSize().background(Palette.canvas).padding(Space.sp8), spacing = Space.sp4, alignment = Alignment.Start) {
        PageHead("Overlays") {
            Btn("Add device", icon = LucideIcon.plus, variant = ButtonVariant.primary) {}
        }
        HStack(spacing = Space.sp3) {
            SelectMenu(
                options = listOf(
                    MenuOption("sonnet", "Sonnet 4.5", description = "The default for most work"),
                    MenuOption("opus", "Opus 4.6", description = "Deeper reasoning, slower"),
                    MenuOption("haiku", "Haiku 4.5", disabled = true),
                ),
                value = value,
                ariaLabel = "Model",
                initiallyOpen = kind == OverlayGalleryKind.popover,
                onSelect = { value = it },
            ) { Text(if (value == "opus") "Opus 4.6" else "Sonnet 4.5") }
            Button({}, style = pill) { Text("Another pill") }
        }
        Hint("The page under an overlay is dimmed and blurred.")
    }
    Modal(
        isPresented = modal,
        onDismiss = { modal = false },
        title = "Rename device",
        width = 420.dp,
        footer = {
            Btn(S.common.cancel) { modal = false }
            Btn(S.common.save, variant = ButtonVariant.primary) { modal = false }
        },
    ) {
        Column {
            FieldLabel("Device name")
            WebField(name, { name = it })
        }
    }
    Drawer(
        isPresented = drawer,
        onDismiss = { drawer = false },
        title = "New session",
        subtitle = "Pick a device to start on",
        footer = { Btn("Start session", variant = ButtonVariant.primary, size = ButtonSize.block) {} },
    ) {
        FieldLabel("Working directory")
        WebField("~/github/remote-control", {}, mono = true)
        Hint("A drawer section.")
    }
    ConfirmDialog(
        isPresented = confirm,
        onDismiss = { confirm = false },
        title = "Sign out of this gateway?",
        body = "Cached sessions and drafts leave this device. Nothing changes on your machines.",
        confirmLabel = "Sign out",
        danger = true,
    ) {}
    LaunchedEffect(kind) {
        modal = kind == OverlayGalleryKind.modal
        drawer = kind == OverlayGalleryKind.drawer
        confirm = kind == OverlayGalleryKind.confirm
    }
}

enum class OverlayGalleryKind { popover, modal, drawer, confirm }
