package com.junbingao.remotecontrol.win.sessions.drawer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.state.trimmed
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.overlay.claimsEscape
import com.junbingao.remotecontrol.win.sessions.controls.RowBtnStyle
import com.junbingao.remotecontrol.win.sessions.controls.SizedField
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch

/**
 * `NewFolderRow.tsx` (A37): the one row the directory picker reveals to name a folder — a mono
 * field that takes the focus, Create and Cancel — and the device's refusal under it. It owns no
 * request: the picker sends `device.mkdir` and hands the outcome back, so the name survives a clash
 * and can be edited where it was typed.
 */
@Composable
internal fun NewFolderRow(browser: DirectoryBrowser, modifier: Modifier = Modifier) {
    val model = LocalAppModel.current
    var fieldFocused by remember { mutableStateOf(false) }
    // Whether the name had the focus when it was sent: the browser keeps it there through the
    // request, while the field is disabled.
    var refocusAfter by remember { mutableStateOf(false) }
    var refocus by remember { mutableIntStateOf(0) }

    fun create() {
        refocusAfter = fieldFocused
        model.tasks.launch { browser.createFolder() }
    }

    VStack(modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        HStack(Modifier.fillMaxWidth(), spacing = Space.sp2) {
            Disabled(browser.folderBusy) {
                SizedField(
                    browser.folderName, { browser.folderName = it },
                    placeholder = S.newSession.newFolderName, mono = true, fontSize = FontSize.fs13, height = 32.dp,
                    autofocus = true, refocus = refocus, onFocus = { fieldFocused = it }, onSubmit = ::create,
                    // Escape belongs to the row, not to the modal around it, while the name has the focus.
                    modifier = Modifier.weight(1f).claimsEscape { browser.stopNaming() },
                )
            }
            // The field takes one line, so trimming its whitespace is trimming its spaces.
            Disabled(browser.folderBusy || browser.folderName.trimmed.isEmpty()) {
                Button(::create, style = RowBtnStyle(primary = true)) { Text(S.newSession.newFolderCreate) }
            }
            Disabled(browser.folderBusy) {
                Button({ browser.stopNaming() }, style = RowBtnStyle(primary = false)) { Text(S.common.cancel) }
            }
        }
        val error = browser.folderError
        if (error != null) Text(error, css(FontSize.fs13), Modifier.padding(top = Space.sp2), color = Palette.danger)
    }
    LaunchedEffect(browser.folderBusy) {
        if (browser.folderBusy) {
            refocusAfter = refocusAfter || fieldFocused
        } else if (refocusAfter) {
            refocusAfter = false
            refocus += 1
        }
    }
}
