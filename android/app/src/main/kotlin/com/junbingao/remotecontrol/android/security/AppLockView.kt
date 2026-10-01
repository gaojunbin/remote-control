package com.junbingao.remotecontrol.android.security

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.junbingao.remotecontrol.android.design.AppMark
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.Label
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import kotlinx.coroutines.launch

/**
 * The phone's biometric unlock or its screen lock before the transcript is shown — the iPhone's
 * `AppLockView`, with Android's words for what unlocks it (`docs/DESIGN.md` § "The Android app").
 */
@Composable
fun AppLockView(onUnlock: () -> Unit) {
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var authenticating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.canvas)
            .padding(32.dp)
            .testTag("app.lock"),
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppMark(62.dp)
        Text(L10n.string("Remote Control is locked"), style = SystemFont.title2.weight(FontWeight.Medium), color = SystemColor.label)
        Text(
            L10n.string("Unlock with Face ID, Touch ID or your passcode to see your sessions."),
            style = SystemFont.subheadline,
            color = Theme.inkSecondary,
            alignment = TextAlign.Center,
        )
        error?.let { Text(it, style = SystemFont.caption, color = SystemColor.secondaryLabel) }
        Button(
            onClick = {
                val host = activity ?: return@Button
                authenticating = true
                scope.launch {
                    if (BiometricLock.authenticate(host, L10n.string("Unlock Remote Control"))) {
                        onUnlock()
                    } else {
                        error = L10n.string("Not unlocked. You can try again.")
                    }
                    authenticating = false
                }
            },
            enabled = !authenticating,
            style = PrimaryButtonStyle(fullWidth = false),
        ) {
            Row(
                Modifier.widthIn(min = 160.dp).heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (authenticating) ActivityIndicator()
                Label(L10n.string("Unlock"), Sf.lockOpen)
            }
        }
    }
}

/** What the recents screen shows instead of a transcript: the mark and the product's name. */
@Composable
fun AppPrivacyCover() {
    Box(Modifier.fillMaxSize().background(Theme.canvas), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppMark(64.dp)
            // The product's own name, never translated.
            Text("Remote Control", style = SystemFont.title2.weight(FontWeight.Medium), color = Theme.ink)
        }
    }
}
