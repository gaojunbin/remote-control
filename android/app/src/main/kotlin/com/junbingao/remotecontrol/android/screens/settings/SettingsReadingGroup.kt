package com.junbingao.remotecontrol.android.screens.settings

import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.GroupedListScope
import com.junbingao.remotecontrol.android.system.Segment
import com.junbingao.remotecontrol.android.system.SegmentedControl
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.core.state.TimelineDetail

/**
 * The Reading group: the words the app writes, and how much of a transcript it draws.
 *
 * `docs/DESIGN.md` § "The Settings screen": two options are a segmented control in the row, so
 * both of these are one — the title and its sentence on the left, the control at the trailing edge
 * at its own width.
 */
fun GroupedListScope.SettingsReadingGroup(
    /** Held so a change of interface language rebuilds the sentences where they stand (`SettingsLabel`). */
    language: InterfaceLanguage,
) {
    SettingsGroup("Reading") {
        val settings = LocalAppModel.current.settings
        SettingsRow("Language", sentence = L10n.string("The app's own words only; what the agent wrote stays as written.")) {
            // Each name in its own script, so the one you want is recognisable from inside the
            // language you cannot read.
            val languages = InterfaceLanguage.allCases
            SegmentedControl(
                segments = languages.map { Segment(it.title, tag = "settings.language.${it.rawValue}") },
                selected = languages.indexOf(settings.language),
                onSelect = { settings.language = languages[it] },
                tag = "settings.language",
            )
        }
        SettingsRow("Detail", sentence = TimelineDetail.footnote) {
            val levels = TimelineDetail.allCases
            SegmentedControl(
                segments = levels.map { Segment(it.title, tag = "settings.timelineDetail.${it.rawValue}") },
                selected = levels.indexOf(settings.timelineDetail),
                onSelect = { settings.timelineDetail = levels[it] },
                tag = "settings.timelineDetail",
            )
        }
    }
}
