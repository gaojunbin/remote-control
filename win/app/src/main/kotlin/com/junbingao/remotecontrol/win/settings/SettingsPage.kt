package com.junbingao.remotecontrol.win.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.strings.S

/**
 * `/settings`, `web/src/features/settings/SettingsPage.tsx`, in the shape `docs/DESIGN.md`
 * § "The Settings screen" gives it: a header saying who and where, four groups named for the
 * question each answers, and the versions as one caption line.
 */
@Composable
fun SettingsPage() {
    VStack(spacing = 0.dp, alignment = Alignment.Start) {
        PageHead(S.settings.title)
        VStack(Modifier.widthIn(max = 620.dp).fillMaxWidth(), spacing = Space.sp6, alignment = Alignment.Start) {
            VStack(spacing = 0.dp, alignment = Alignment.Start) {
                IdentityHeader()
                AccountGroup()
            }
            WhileAwayGroup()
            VoiceGroup()
            VStack(spacing = 0.dp) {
                ReadingGroup()
                VersionsLine()
            }
        }
    }
}
