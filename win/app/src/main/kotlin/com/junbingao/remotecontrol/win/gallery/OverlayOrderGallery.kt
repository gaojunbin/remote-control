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
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FieldLabel
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WebField
import com.junbingao.remotecontrol.win.design.overlay.ConfirmDialog
import com.junbingao.remotecontrol.win.design.overlay.Drawer
import com.junbingao.remotecontrol.win.design.overlay.Modal
import com.junbingao.remotecontrol.win.design.overlay.anchoredPanel
import com.junbingao.remotecontrol.win.design.pill
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.delay

/**
 * A regression check for the overlay layer: one control carrying a modal, a modal with a close
 * button, the drawer, a confirmation and an anchored panel, asked for in one of three orders, with
 * one of them opened after the first frame. The modal is built from a form made for the opening,
 * as the web's dialogs make theirs, and the form changes while the modal is open. Whatever the
 * order, the open overlay is drawn whole and current; a modal or the drawer blurs the page, and a
 * modal blurs the drawer under it, opened beside it or from inside it. Focus moves as the web's
 * does: to the rename modal's field, and to no field where a close button or a button comes first.
 */
@Composable
fun OverlayOrderGallery(order: OverlayOrder, open: OrderedOverlay) {
    var form by remember { mutableStateOf<RenameForm?>(null) }
    var drawer by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(false) }
    var closable by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    VStack(Modifier.fillMaxSize().background(Palette.canvas).padding(Space.sp8), spacing = Space.sp4, alignment = Alignment.Start) {
        PageHead("Overlay order")
        val trigger = @Composable { modifier: Modifier -> Button({}, modifier, style = pill) { Text("Actions") } }
        val rename = @Composable { RenameModal(form) { form = null } }
        val folder = @Composable { FolderModal(closable) { closable = false } }
        val signOut = @Composable { SignOutConfirm(confirm) { confirm = false } }
        val newSession = @Composable { NewSessionDrawer(drawer, { drawer = false }, picker) { picker = it } }
        // The same presenters in each order: none of them changes what is drawn.
        when (order) {
            OverlayOrder.confirmFirst -> { trigger(Modifier.actionsAnchor(panel) { panel = false }); signOut(); rename(); newSession(); folder() }
            OverlayOrder.panelFirst -> { trigger(Modifier.actionsAnchor(panel) { panel = false }); folder(); newSession(); rename(); signOut() }
            OverlayOrder.modalFirst -> { trigger(Modifier.actionsAnchor(panel) { panel = false }); folder(); rename(); signOut(); newSession() }
            OverlayOrder.formBoundToChildren -> { trigger(Modifier); signOut(); RenameModal(form) { form = null } }
        }
        Hint("One control carries every kind of overlay; the order it chains them in changes nothing.")
    }
    LaunchedEffect(Unit) {
        delay(200)
        when (open) {
            OrderedOverlay.modal -> {
                val opened = RenameForm()
                form = opened
                delay(300)
                opened.name = "ci-runner-01"
            }
            OrderedOverlay.drawer -> drawer = true
            OrderedOverlay.confirm -> confirm = true
            OrderedOverlay.panel -> panel = true
            OrderedOverlay.closableModal -> closable = true
            OrderedOverlay.modalOverDrawer -> {
                drawer = true
                delay(300)
                form = RenameForm().also { it.name = "ci-runner-01" }
            }
            OrderedOverlay.modalInDrawer -> {
                drawer = true
                delay(300)
                picker = true
            }
        }
    }
}

/**
 * The three orders the presenters are asked for in, and the fourth way a dialog stays current:
 * its form handed on to the views that draw it.
 */
enum class OverlayOrder { confirmFirst, panelFirst, modalFirst, formBoundToChildren }

enum class OrderedOverlay { modal, drawer, confirm, panel, closableModal, modalOverDrawer, modalInDrawer }

/** The modal's form: empty when it opens, and Save waits for a name. */
class RenameForm {
    var name by mutableStateOf("")
    val ready: Boolean get() = name.isNotEmpty()
}

@Composable
private fun RenameModal(form: RenameForm?, close: () -> Unit) {
    Modal(
        isPresented = form != null,
        onDismiss = close,
        title = "Rename device",
        width = 420.dp,
        footer = {
            if (form != null) {
                Btn(S.common.cancel, action = close)
                Disabled(!form.ready) { Btn(S.common.save, variant = ButtonVariant.primary, action = close) }
            }
        },
    ) {
        if (form != null) {
            Column {
                FieldLabel("Device name")
                WebField(form.name, { form.name = it })
            }
        }
    }
}

/** The drawer, with a modal it opens itself, as the New session drawer opens its directory picker. */
@Composable
private fun NewSessionDrawer(isPresented: Boolean, close: () -> Unit, picker: Boolean, onPicker: (Boolean) -> Unit) {
    Drawer(
        isPresented = isPresented,
        onDismiss = close,
        title = "New session",
        subtitle = "Pick a device to start on",
        footer = { Btn("Start session", variant = ButtonVariant.primary, size = ButtonSize.block) {} },
    ) {
        FieldLabel("Working directory")
        WebField("~/github/remote-control", {}, mono = true)
        FolderModal(picker) { onPicker(false) }
    }
}

/** A modal whose head holds a close button, like the directory picker. */
@Composable
private fun FolderModal(isPresented: Boolean, close: () -> Unit) {
    Modal(isPresented = isPresented, onDismiss = close, title = "Choose a folder", width = 520.dp, showClose = true) {
        FieldLabel("Path")
        WebField("~/github", {}, mono = true)
    }
}

@Composable
private fun SignOutConfirm(isPresented: Boolean, close: () -> Unit) {
    ConfirmDialog(
        isPresented = isPresented,
        onDismiss = close,
        title = "Sign out of this gateway?",
        body = "Cached sessions and drafts leave this device. Nothing changes on your machines.",
        confirmLabel = "Sign out",
        danger = true,
    ) {}
}

private fun Modifier.actionsAnchor(isPresented: Boolean, close: () -> Unit): Modifier =
    anchoredPanel(isPresented, close) {
        MenuList {
            MenuItemRow(S.common.rename) {}
            MenuItemRow(S.common.revoke, danger = true) {}
        }
    }
