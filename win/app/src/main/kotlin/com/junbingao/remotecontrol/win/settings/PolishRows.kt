package com.junbingao.remotecontrol.win.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.transport.PolishModel
import com.junbingao.remotecontrol.core.transport.PolishStrength
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.LocalPreviewStage
import com.junbingao.remotecontrol.win.design.MenuItemRow
import com.junbingao.remotecontrol.win.design.MenuList
import com.junbingao.remotecontrol.win.design.SegmentOption
import com.junbingao.remotecontrol.win.design.Segmented
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.overlay.Popover
import com.junbingao.remotecontrol.win.design.overlay.PopoverAlign
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.CancellationException

/**
 * `PolishRows` in `VoiceGroup.tsx` — A29: the model and the strength, drawn while polish is on.
 * The list belongs to the gateway's provider, so it is asked for when these rows are drawn. A
 * gateway that lists nothing leaves the menu where it is, with what was chosen before; a list with
 * nothing chosen yet settles on its first model, so the switch is all a first-time reader has to
 * touch.
 */
@Composable
fun PolishRows() {
    val model = LocalAppModel.current
    val stage = LocalPreviewStage.current
    var models by remember { mutableStateOf(emptyList<PolishModel>()) }
    var failed by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val settings = model.settings
    VStack(spacing = 0.dp) {
        SettingsRow(
            title = S.settings.polishModel,
            sentence = if (failed) S.settings.polishModelsFailed else S.settings.polishModelNote,
            target = true,
            reach = { menuOpen = !menuOpen },
        ) {
            Popover(
                isOpen = menuOpen,
                onOpenChange = { menuOpen = it },
                align = PopoverAlign.end,
                ariaLabel = S.settings.polishModel,
                label = { Text(label(settings.polishModel, models)) },
            ) { close ->
                MenuList(Modifier.semantics { contentDescription = S.settings.polishModel }) {
                    for (option in models) {
                        MenuItemRow(option.label, selected = settings.polishModel == option.id) {
                            settings.polishModel = option.id
                            close()
                        }
                    }
                }
            }
        }
        SettingsRow(S.settings.polishStrength, S.settings.polishStrengthNote) {
            Segmented(
                value = settings.polishStrength,
                options = listOf(
                    SegmentOption(PolishStrength.moderate, S.settings.polishModerate),
                    SegmentOption(PolishStrength.strong, S.settings.polishStrong),
                ),
                ariaLabel = S.settings.polishStrength,
            ) { settings.polishStrength = it }
        }
    }
    LaunchedEffect(Unit) {
        val api = model.connection.api ?: return@LaunchedEffect
        try {
            val result = api.polishModels()
            models = result.models
            failed = false
            val first = result.models.firstOrNull()
            if (first != null && model.settings.polishModel.isEmpty()) model.settings.polishModel = first.id
            if (stage == "settings.polish-menu") menuOpen = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
}

private fun label(chosen: String, models: List<PolishModel>): String {
    val known = models.firstOrNull { it.id == chosen }
    if (known != null && known.label.isNotEmpty()) return known.label
    return chosen.ifEmpty { S.settings.polishChooseModel }
}
