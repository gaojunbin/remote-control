package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.SendMode
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.IconBtn
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.btn
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.design.overlay.PopoverSide
import com.junbingao.remotecontrol.win.strings.S
import com.junbingao.remotecontrol.win.voice.PrimarySlot
import com.junbingao.remotecontrol.win.voice.WorkingPill
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager

/**
 * `.composer-buttons`: attach, the mic, the ⋯ beside Send while a turn runs, and the one primary
 * slot — Send, Queue or Answer, or the spinner while the words are still on their way.
 */
@Composable
fun ComposerButtons(composer: ComposerModel, sendMenuOpen: Boolean, modifier: Modifier = Modifier) {
    val gates = composer.gates
    HStack(modifier.padding(bottom = 2.dp), spacing = 2.dp) {
        if (gates.showAttach) {
            Disabled(gates.disabled) {
                IconBtn(LucideIcon.paperclip, size = 16.dp, label = S.composer.attach) { chooseFiles(composer) }
            }
        }
        if (composer.host.sttEnabled) {
            Disabled(gates.disabled) {
                IconBtn(LucideIcon.mic, size = 16.dp, label = S.composer.micStart) { composer.startVoice() }
            }
        }
        if (composer.showsSendMenu) {
            Popover(
                align = PopoverAlign.end,
                side = PopoverSide.top,
                chevron = false,
                triggerStyle = SendAltStyle,
                ariaLabel = S.composer.sendOptions,
                initiallyOpen = sendMenuOpen,
                label = { Text("⋯", css(FontSize.fs13), Modifier.fillMaxHeight().clearAndSetSemantics {}, softWrap = false) },
            ) { close ->
                MenuList {
                    MenuItemRow(S.composer.interruptAndSend) {
                        close()
                        composer.submit(SendMode.interrupt)
                    }
                }
            }
        }
        SendSlot(composer)
    }
}

/**
 * The paperclip opens the system's file dialog, as a browser's file input does — Windows' own
 * Open dialog, over the window the composer is in. It is shown once the click is over, so the
 * dialog's own event loop never runs inside it.
 */
private fun chooseFiles(composer: ComposerModel) {
    EventQueue.invokeLater {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
        val dialog = FileDialog(owner).apply { isMultipleMode = true }
        dialog.isVisible = true
        val chosen = dialog.files.orEmpty().toList()
        dialog.dispose()
        if (chosen.isNotEmpty()) composer.attach(chosen.map { AttachmentSource.File(it) })
    }
}

/**
 * What the one primary slot holds (`PrimarySlot`): Send while the field holds what will be sent,
 * the spinner while the model or the device still has it.
 */
@Composable
fun SendSlot(composer: ComposerModel) {
    if (composer.slot == PrimarySlot.send) {
        val running = composer.gates.running
        Disabled(composer.primaryDisabled) {
            Button(
                { composer.primarySubmit() },
                style = btn(ButtonVariant.primary, ButtonSize.small),
                accessibilityLabel = if (running) composer.primaryLabel else S.composer.send,
            ) {
                if (running) {
                    Text(composer.primaryLabel, css(FontSize.fs13, weight = FontWeight.Medium), Modifier.fillMaxHeight(), softWrap = false)
                } else {
                    Icon(LucideIcon.arrowUp, size = 15.dp)
                }
            }
        }
    } else {
        // Dictation is over and the model has the words, or an edited message is on its way back
        // into the line (A43): the slot waits where Send was, and takes no click while it does.
        WorkingPill(if (composer.returning) S.chat.sending else S.voice.polishing)
    }
}
