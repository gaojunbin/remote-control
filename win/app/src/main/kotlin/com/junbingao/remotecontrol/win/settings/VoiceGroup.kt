package com.junbingao.remotecontrol.win.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Switch
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.S

/**
 * `VoiceGroup.tsx` — A29: dictation, who transcribes it, and whether a model tidies it up before
 * it is sent. A gateway that offers no transcription and a gateway with no polish model each say
 * so in their own row; the group itself never collapses to a note.
 */
@Composable
fun VoiceGroup() {
    val model = LocalAppModel.current
    val stage = LocalPreviewStage.current
    val settings = model.settings
    // A render of a gateway with no transcription service, or no polish model.
    val transcribes = model.connection.stt.enabled && stage != "settings.no-transcription"
    val offered = model.connection.polish.enabled && stage != "settings.polish-unavailable"
    val staged = stage == "settings.polish-on" || stage == "settings.polish-menu"
    val polishOn = offered && (settings.polishEnabled || staged)
    SettingsGroup(S.settings.voice) {
        // A44: Windows, like the web, only transcribes on the gateway, whose provider detects the
        // language, so the row names the gateway and has nothing to choose — no menu, and no
        // language.
        SettingsRow(S.settings.transcribe, if (transcribes) S.settings.transcribeNote else S.settings.voiceServerDisabled) {
            QuietChip(S.settings.transcribeGateway)
        }
        // The switch is always drawn, so the feature exists even where this gateway cannot offer
        // it; the model and the strength appear once it is on, because they mean nothing while it
        // is off.
        SettingsRow(
            title = S.settings.polish,
            sentence = if (offered) S.settings.polishNote else S.settings.polishServerDisabled,
            target = true,
            reach = if (offered) ({ settings.polishEnabled = !polishOn }) else null,
        ) {
            Disabled(!offered) {
                Switch(isOn = polishOn, label = S.settings.polish) { settings.polishEnabled = it }
            }
        }
        if (polishOn) PolishRows()
    }
}

/**
 * `.pill.quiet` on a span: a chip that reads as state rather than as a control — no tint, the
 * secondary ink, almost no padding, and nothing to click.
 */
@Composable
fun QuietChip(text: String) {
    Box(Modifier.height(28.dp).padding(horizontal = 2.dp)) {
        Text(text, css(FontSize.fs13), Modifier.fillMaxHeight(), color = Palette.inkSecondary, lineLimit = 1)
    }
}
