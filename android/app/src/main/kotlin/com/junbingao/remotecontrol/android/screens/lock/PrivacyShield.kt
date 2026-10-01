package com.junbingao.remotecontrol.android.screens.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AppMark
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight

/**
 * What the recents screen shows instead of a transcript — the iPhone's `PrivacyShield` view:
 * the mark and the product's name, over everything while [visible]. The root draws it above the
 * presentations whenever `SceneRule.shields` says so; `RecentsShield` is the platform's half.
 *
 * The foundation's port, which `android-settings` owns from here.
 */
@Composable
fun PrivacyShield(visible: Boolean) {
    if (!visible) return
    Box(Modifier.fillMaxSize().background(Theme.canvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppMark(64.dp)
            // The product's own name, never translated.
            Text("Remote Control", style = SystemFont.title2.weight(FontWeight.Medium), color = Theme.ink)
        }
    }
}
