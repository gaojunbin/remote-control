package com.junbingao.remotecontrol.android.screens.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.junbingao.remotecontrol.android.design.NoticeBanner
import com.junbingao.remotecontrol.android.design.OneAtATime
import com.junbingao.remotecontrol.android.design.SettingsFooter
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.settings
import com.junbingao.remotecontrol.android.design.settingsRowLayout
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.android.system.InsetGroupedList
import com.junbingao.remotecontrol.android.system.RowStyle
import com.junbingao.remotecontrol.android.system.Sheet
import com.junbingao.remotecontrol.core.protocol.ResumeBounds
import com.junbingao.remotecontrol.core.protocol.SessionResume
import com.junbingao.remotecontrol.core.state.ChatStore
import com.junbingao.remotecontrol.core.state.ResumeText
import java.time.Instant

/**
 * Amendment A35 — a session the usage limit paused, said where the session is.
 *
 * One line above the transcript with two actions and no more: Change opens the smallest time
 * picker the platform has, and Cancel takes the resume away at once (`docs/DESIGN.md` § "Paused by
 * the usage limit"). The status dot is not touched: the session is idle and the dot says so, and
 * this carries the pause.
 */
@Composable
internal fun ResumeNotice(chat: ChatStore, resume: SessionResume) {
    val model = LocalAppModel.current
    var showsPicker by remember { mutableStateOf(false) }
    // One request at a time, so a second tap cannot send a second cancel.
    val acting = remember { OneAtATime() }
    NoticeBanner(
        text = ResumeText.notice(resume),
        tint = Theme.attention,
        actionTitle = L10n.string("Change"),
        action = { showsPicker = true },
        actionEnabled = !acting.isBusy,
        secondaryActionTitle = L10n.string("Cancel"),
        secondaryAction = { model.perform { acting.run { chat.cancelResume() } } },
        identifier = "chat.resumeNotice",
    )
    Sheet(isPresented = showsPicker, onDismiss = { showsPicker = false }) {
        ResumeTimeSheet(resume, close = { showsPicker = false }) { date -> acting.run { chat.setResume(at = date) } }
    }
}

/**
 * The time picker Change opens: one compact date-and-time control, prefilled with the time the
 * resume is set for, between a minute from now and eight days out — the bounds the device
 * enforces, so nothing it would refuse can be chosen here.
 */
@Composable
internal fun ResumeTimeSheet(resume: SessionResume, close: () -> Unit, set: suspend (Instant) -> Unit) {
    val model = LocalAppModel.current
    // The bounds are read once, when the sheet opens: a range that slid under the picker while
    // someone was turning it would move the wheel for them.
    val opened = remember { Instant.now() }
    var date by remember { mutableStateOf(maxOf(resume.date, ResumeBounds.earliest(from = Instant.now()))) }
    var isSetting by remember { mutableStateOf(false) }
    val range = ResumeBounds.earliest(from = opened)..ResumeBounds.latest(from = opened)
    NavigationScreen(
        L10n.string("Resume"),
        displayMode = TitleDisplayMode.inline,
        showsBack = false,
        leading = { BarTextButton(L10n.string("Close"), onClick = close) },
        trailing = {
            BarTextButton(
                L10n.string("Set"),
                onClick = {
                    isSetting = true
                    model.perform {
                        set(date)
                        isSetting = false
                        close()
                    }
                },
                enabled = !isSetting,
                prominent = true,
                tag = "resume.set",
            )
        },
    ) { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), contentPadding = insets.padding()) {
            section(
                key = "resume",
                footer = { SettingsFooter(L10n.string("Any time from a minute from now to eight days away.")) },
            ) {
                row(style = RowStyle.settings) {
                    CompactDatePicker(
                        L10n.string("Resume at"),
                        date,
                        range,
                        onDate = { date = it },
                        modifier = Modifier.settingsRowLayout().testTag("resume.picker"),
                    )
                }
            }
        }
    }
}
