package com.junbingao.remotecontrol.win.settings

import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.TimelineDetail
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.SegmentOption
import com.junbingao.remotecontrol.win.design.Segmented
import com.junbingao.remotecontrol.win.strings.S

/**
 * `ReadingGroup.tsx`: how the app reads — the words it uses for itself, and how much of a turn
 * the timeline draws. Two choices each, so both are segmented controls in the row
 * (`docs/DESIGN.md` § "The Settings screen"). Both are the account's (A41): a change goes up, and
 * one made elsewhere moves the control in place.
 */
@Composable
fun ReadingGroup() {
    val settings = LocalAppModel.current.settings
    SettingsGroup(S.settings.reading) {
        SettingsRow(S.settings.language, S.settings.languageNote) {
            Segmented(
                value = settings.language,
                options = listOf(InterfaceLanguage.en, InterfaceLanguage.zhHans).map { SegmentOption(it, S.interfaceLanguageLabel(it)) },
                ariaLabel = S.settings.language,
            ) { settings.language = it }
        }
        SettingsRow(S.settings.timelineDetail, S.settings.timelineDetailNote) {
            Segmented(
                value = settings.timelineDetail,
                options = listOf(TimelineDetail.simple, TimelineDetail.detailed).map { SegmentOption(it, S.timelineDetailLabel(it)) },
                ariaLabel = S.settings.timelineDetail,
            ) { settings.timelineDetail = it }
        }
    }
}
