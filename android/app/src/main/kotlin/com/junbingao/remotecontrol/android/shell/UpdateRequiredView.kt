package com.junbingao.remotecontrol.android.shell

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.net.toUri
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.Divider
import com.junbingao.remotecontrol.android.design.PrimaryButtonStyle
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.ValueRow
import com.junbingao.remotecontrol.android.design.pageBackground
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.safeArea
import com.junbingao.remotecontrol.core.state.AppUpdateRequirement
import java.net.URI

/**
 * Amendments A31 and A46 — the one screen an app older than its gateway may show: the iPhone's
 * `UpdateRequiredView`. The gateway states the oldest build it works with (`apps.android`); below
 * it nothing else is reachable, because everything else would fail in ways a phone cannot
 * explain. Two ways forward and no third: fetch the newer build where the operator says it is, or
 * sign out and go to another gateway.
 */
@Composable
fun UpdateRequiredView(requirement: AppUpdateRequirement, signOut: () -> Unit) {
    val context = LocalContext.current
    val safe = safeArea()
    Column(
        Modifier
            .fillMaxSize()
            .pageBackground()
            // Nothing under this screen is reachable: a touch anywhere lands here.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            // The page colour runs under the system bars; what is on it is centred between them.
            .padding(top = safe.top, bottom = safe.bottom)
            .padding(Theme.Space.page),
        verticalArrangement = Arrangement.spacedBy(Theme.Space.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.small), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Sf.arrowUpCircle, font = SystemFont.system(40f, FontWeight.Light), tint = Theme.inkSecondary)
            Text(
                L10n.string("Update required"),
                Modifier.testTag("update.title"),
                style = SystemFont.title2.weight(FontWeight.SemiBold),
                color = Theme.ink,
            )
            Text(
                L10n.string("This gateway needs a newer version of the app."),
                style = SystemFont.subheadline,
                color = Theme.inkSecondary,
                alignment = TextAlign.Center,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Theme.surface, ContinuousShape(Theme.Radius.card))
                .padding(horizontal = Theme.Space.medium)
                .testTag("update.versions"),
        ) {
            ValueRow("This app", requirement.current.toString())
            Divider(color = Theme.hairline)
            ValueRow("Gateway needs", requirement.minimum.toString())
        }
        Column(verticalArrangement = Arrangement.spacedBy(Theme.Space.small), horizontalAlignment = Alignment.CenterHorizontally) {
            requirement.updateURL?.let { url ->
                Button(onClick = { open(context, url) }, Modifier.testTag("update.open"), style = PrimaryButtonStyle()) {
                    Text(openTitle(url))
                }
            }
            Button(onClick = signOut, Modifier.testTag("update.signOut")) {
                Text(L10n.string("Sign out"), style = Theme.Text.label, color = Theme.inkSecondary)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The iPhone names the place a build comes from — TestFlight or the App Store — and Android has
 * no such place of its own, so both read as the update page, which is where the operator's link
 * goes.
 */
private fun openTitle(url: URI): String =
    if (url.host?.contains("testflight") == true) L10n.string("Open TestFlight") else L10n.string("Open the App Store")

private fun open(context: android.content.Context, url: URI) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the phone opens the link; Sign out is still there.
    }
}
