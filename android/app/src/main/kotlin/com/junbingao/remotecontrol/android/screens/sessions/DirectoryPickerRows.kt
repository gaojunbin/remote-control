package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.Button
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.ContinuousShape
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.TextField
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.BarMetrics
import com.junbingao.remotecontrol.android.system.glass

/** The name, what the device said about the last one, and the two actions — at the head of the listing the folder would be made in. */
@Composable
internal fun NewFolderRow(
    name: String,
    onName: (String) -> Unit,
    error: String?,
    canCreate: Boolean,
    create: () -> Unit,
    cancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    PlainRow {
        Column(Modifier.fillMaxWidth().padding(vertical = Theme.Space.tight), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Theme.surfaceSunken, ContinuousShape(Theme.Radius.control))
                    .padding(horizontal = Theme.Space.small),
            ) {
                TextField(
                    L10n.string("Folder name"),
                    name,
                    onName,
                    Modifier.focusRequester(focus),
                    style = Theme.monoBody,
                    onSubmit = create,
                    tag = "dirs.folderName",
                )
            }
            error?.let { Text(it, Modifier.testTag("dirs.folderError"), style = SystemFont.footnote, color = Theme.danger) }
            val words = SystemFont.callout.weight(FontWeight.Medium)
            Row(Modifier.heightIn(min = Theme.Touch.minimum), horizontalArrangement = Arrangement.spacedBy(Theme.Space.large), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = create, Modifier.testTag("dirs.create"), enabled = canCreate) {
                    Text(L10n.string("Create"), style = words, color = if (canCreate) SystemColor.label else SystemColor.tertiaryLabel)
                }
                Button(onClick = cancel, Modifier.testTag("dirs.cancelFolder")) {
                    Text(L10n.string("Cancel"), style = words, color = Theme.inkSecondary)
                }
            }
        }
    }
}

/**
 * New folder and Select at the bar's trailing edge. iOS 26 sets a bar's primary and confirming
 * actions side by side in one glass capsule, the confirming one in the semibold weight.
 */
@Composable
internal fun PickerActions(canMakeFolder: Boolean, canSelect: Boolean, newFolder: () -> Unit, select: () -> Unit) {
    Row(Modifier.height(BarMetrics.buttonHeight).glass(CapsuleShape), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = newFolder,
            Modifier
                .fillMaxHeight()
                .width(PickerMetrics.folderWidth)
                .semantics { contentDescription = L10n.string("New folder") }
                .testTag("dirs.newFolder"),
            enabled = canMakeFolder,
        ) {
            Icon(Sf.folderBadgePlus, font = SystemFont.body, tint = if (canMakeFolder) Theme.ink else SystemColor.tertiaryLabel)
        }
        Button(
            onClick = select,
            Modifier.fillMaxHeight().padding(end = BarMetrics.textPadding).testTag("dirs.select"),
            enabled = canSelect,
        ) {
            Text(
                L10n.string("Select"),
                style = SystemFont.body.weight(FontWeight.SemiBold),
                color = if (canSelect) Theme.ink else SystemColor.tertiaryLabel,
                lineLimit = 1,
            )
        }
    }
}

/** The capsule's measurements, from `53-directory-new-folder-clash`. */
private object PickerMetrics {
    /** New folder's share of the capsule, the symbol in the middle of it. */
    val folderWidth = 66.dp
}
