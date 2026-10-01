package com.junbingao.remotecontrol.win.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.state.AppUpdateRequirement
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.app.signOut
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.CSSLine
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Hint
import com.junbingao.remotecontrol.win.design.Mark
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Shadow
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithFont
import com.junbingao.remotecontrol.win.design.card
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.overlay.WholePointCenter
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch
import java.net.URI

/**
 * Amendment A46 — PROTOCOL 8.16: the one screen a Windows app older than its gateway shows, in the
 * web's visual language on the login page's card. Below the minimum nothing else is reachable,
 * because everything else would fail in ways the app cannot explain. Two ways forward and no
 * third: fetch the newer build where the operator says it is, or sign out and go to another
 * gateway. The words are the iPhone app's, because it is the same screen.
 */
@Composable
fun UpdateRequiredPage(requirement: AppUpdateRequirement) {
    val layout = LocalLayoutClass.current
    ThinScrollView(modifier = Modifier.fillMaxSize().background(Palette.canvas)) {
        // `.login` centres the card; the browser puts it on a whole point.
        WholePointCenter(minimumHeight = layout.height.dp) { UpdateCard(requirement) }
    }
}

@Composable
private fun UpdateCard(requirement: AppUpdateRequirement) {
    val model = LocalAppModel.current
    val uriHandler = LocalUriHandler.current
    val url = requirement.updateURL
    VStack(
        Modifier.padding(Space.sp6).widthIn(max = 380.dp).card(shadow = Shadow.one).padding(Space.sp8),
        spacing = 0.dp,
        alignment = Alignment.Start,
    ) {
        HStack(Modifier.padding(bottom = Space.sp2), spacing = Space.sp3) {
            Mark(size = 26.dp)
            Text(S.win.updateTitle, css(FontSize.fs22, weight = FontWeight.SemiBold, tracking = -0.01f))
        }
        Hint(S.win.updateBody, Modifier.padding(bottom = Space.sp6))

        VersionLine(S.win.updateThisApp, requirement.current.toString())
        VersionLine(S.win.updateGatewayNeeds, requirement.minimum.toString(), Modifier.padding(bottom = Space.sp6))

        if (url != null) {
            Btn(UpdateRequired.openTitle(url), variant = ButtonVariant.primary, size = ButtonSize.block) {
                uriHandler.openUri(url.toString())
            }
        }
        Btn(
            S.settings.signOut,
            variant = if (url == null) ButtonVariant.primary else ButtonVariant.ghost,
            size = ButtonSize.block,
            modifier = Modifier.padding(top = if (url == null) 0.dp else Space.sp2),
        ) {
            model.tasks.launch { model.signOut() }
        }
    }
}

object UpdateRequired {
    /**
     * Where the button says it goes. The Mac's names TestFlight and the App Store, Apple's two
     * places for a build; a Windows build is handed out from a download page, so the button says
     * that wherever the operator points it.
     */
    fun openTitle(url: URI): String = S.win.updateOpenDownload
}

/** One of the two versions the screen compares: the label in the secondary ink, the number in mono. */
@Composable
private fun VersionLine(label: String, value: String, modifier: Modifier = Modifier) {
    CSSLine(css(FontSize.fs14), modifier.padding(vertical = Space.sp2)) {
        HStack(spacing = Space.sp3) {
            Text(label, color = Palette.inkSecondary)
            Spacer(Modifier.weight(1f))
            WithFont(FontSize.fs13, mono = true) { Text(value, color = Palette.ink) }
        }
    }
}
