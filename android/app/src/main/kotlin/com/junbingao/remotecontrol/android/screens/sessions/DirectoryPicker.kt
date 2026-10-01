package com.junbingao.remotecontrol.android.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.SystemFont
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.scrollIndicator
import com.junbingao.remotecontrol.android.design.weight
import com.junbingao.remotecontrol.android.icons.Icon
import com.junbingao.remotecontrol.android.icons.Sf
import com.junbingao.remotecontrol.android.navigation.LocalTabBarReserve
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.navigation.TitleDisplayMode
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.android.system.ActivityIndicator
import com.junbingao.remotecontrol.android.system.BarTextButton
import com.junbingao.remotecontrol.android.system.safeArea
import com.junbingao.remotecontrol.core.protocol.DirectoryListing
import com.junbingao.remotecontrol.core.protocol.GatewayRequest
import com.junbingao.remotecontrol.core.state.DirectoryError
import com.junbingao.remotecontrol.core.state.request
import com.junbingao.remotecontrol.core.state.trimmed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Browses directories on a device through `device.dirs`, and makes one with `device.mkdir` (A37).
 *
 * Select stays disabled until the listing on screen is the path it would return, so a slow reply
 * can never hand back a directory the user did not see. A folder made here is the listing on screen
 * the moment the device answers, which is what lets the same Select pick it.
 */
@Composable
fun DirectoryPicker(deviceID: String, onSelect: (String) -> Unit, dismiss: () -> Unit) {
    val model = LocalAppModel.current
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<DirectoryListing?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Amendment A37: the name being typed, and why the device refused the last one. Both live in a
    // row at the head of the list rather than in an alert, so the refusal is read beside the name
    // that caused it and the name is still there to be corrected.
    var isNaming by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }

    fun stopNaming() {
        isNaming = false
        folderName = ""
        nameError = null
    }

    suspend fun load(path: String?) {
        val channel = model.connection.channel ?: return
        isBusy = true
        try {
            listing = channel.request(GatewayRequest.dirs(deviceID = deviceID, path = path), DirectoryListing.serializer())
            error = null
            stopNaming()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = model.connection.message(failure)
        } finally {
            isBusy = false
        }
    }

    // Makes the folder inside the directory on screen and stands in it, which is the listing the
    // device replies with. A refusal keeps the row up with what the device said and the name left
    // to correct.
    suspend fun makeFolder() {
        val channel = model.connection.channel ?: return
        val path = listing?.path ?: return
        val name = folderName.trimmed
        if (name.isEmpty() || isBusy) return
        isBusy = true
        try {
            listing = channel.request(GatewayRequest.mkdir(deviceID = deviceID, path = path, name = name), DirectoryListing.serializer())
            error = null
            stopNaming()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            nameError = DirectoryError.makeFolder(failure)
        } finally {
            isBusy = false
        }
    }

    LaunchedEffect(Unit) { load(null) }

    CompositionLocalProvider(LocalTabBarReserve provides safeArea().bottom) {
        NavigationScreen(
            listing?.let { lastPathComponent(it.path) } ?: L10n.string("Browse"),
            displayMode = TitleDisplayMode.inline,
            showsBack = false,
            leading = { BarTextButton(L10n.string("Cancel"), dismiss) },
            trailing = {
                // Amendment A37: offered wherever a listing is shown, and only while there is one
                // to make a folder in.
                PickerActions(
                    canMakeFolder = listing != null && !isBusy && !isNaming,
                    canSelect = listing != null && !isBusy,
                    newFolder = {
                        folderName = ""
                        nameError = null
                        isNaming = true
                    },
                    select = {
                        listing?.path?.let(onSelect)
                        dismiss()
                    },
                )
            },
        ) { insets ->
            val list = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize().scrollIndicator(list).testTag("dirs.list"),
                state = list,
                contentPadding = PaddingValues(top = insets.top, bottom = insets.bottom),
            ) {
                val shown = listing
                val failure = error
                item(key = "top") { PlainListTop(firstInset(shown, isNaming)) }
                when {
                    shown != null -> {
                        if (isNaming) {
                            item(key = "newFolder") {
                                NewFolderRow(
                                    name = folderName,
                                    onName = { folderName = it },
                                    error = nameError,
                                    canCreate = folderName.trimmed.isNotEmpty() && !isBusy,
                                    create = { scope.launch { makeFolder() } },
                                    cancel = ::stopNaming,
                                )
                            }
                        }
                        shown.parent?.let { parent ->
                            item(key = "up") {
                                PlainRow(DirectoryMetrics.labelText, onClick = { scope.launch { load(parent) } }) {
                                    // `Label` in a list: the symbol in the middle of the icon's column, the words at a set place after it.
                                    Row(Modifier.heightIn(min = Theme.Touch.minimum), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.width(DirectoryMetrics.labelGlyph), contentAlignment = Alignment.Center) { Icon(Sf.arrowUpLeft, tint = Theme.ink) }
                                        Spacer(Modifier.width(DirectoryMetrics.labelText - DirectoryMetrics.labelGlyph))
                                        Text(L10n.string("Up one level"), color = Theme.ink)
                                    }
                                }
                            }
                        }
                        for (entry in shown.entries) {
                            item(key = entry.path) {
                                PlainRow(DirectoryMetrics.entryText(entry.isGit), onClick = { scope.launch { load(entry.path) } }) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = Theme.Touch.minimum), horizontalArrangement = Arrangement.spacedBy(Theme.Space.small), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.width(DirectoryMetrics.glyph(entry.isGit)), contentAlignment = Alignment.CenterStart) {
                                            Icon(if (entry.isGit) Sf.shippingbox else Sf.folder, tint = Theme.inkSecondary)
                                        }
                                        Text(entry.name, Modifier.weight(1f), color = Theme.ink)
                                        Icon(Sf.chevronRight, font = SystemFont.caption, tint = Theme.inkSecondary)
                                    }
                                }
                            }
                        }
                        if (shown.entries.isEmpty()) {
                            item(key = "empty") {
                                PlainRow { Text(L10n.string("No subdirectories here."), style = SystemFont.footnote, color = Theme.inkSecondary) }
                            }
                        }
                    }
                    failure != null -> item(key = "error") {
                        PlainRow { Text(failure, style = SystemFont.footnote, color = Theme.danger) }
                    }
                    else -> item(key = "loading") {
                        PlainRow {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                ActivityIndicator()
                                Text(L10n.string("Loading"), color = Theme.inkSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A path's last component, as `URL(fileURLWithPath:).lastPathComponent` names it: the root is "/". */
internal fun lastPathComponent(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return "/"
    return trimmed.substringAfterLast('/')
}

/** The hairline over the first row starts where that row's own does. */
private fun firstInset(listing: DirectoryListing?, isNaming: Boolean): Dp {
    if (listing == null || isNaming) return 0.dp
    if (listing.parent != null) return DirectoryMetrics.labelText
    return listing.entries.firstOrNull()?.let { DirectoryMetrics.entryText(it.isGit) } ?: 0.dp
}

/**
 * Where a row's words start, from its leading inset, which is where its hairline starts too
 * (`54-directory-new-folder`).
 */
internal object DirectoryMetrics {
    /** Up one level: the column `Label` gives its symbol, and its words 40 points in. */
    val labelGlyph = 23.67.dp
    val labelText = 40.dp

    /** A folder's symbol at its own width, then the row's 10-point gap: SF's shippingbox is narrower than its folder. */
    fun glyph(isGit: Boolean): Dp = if (isGit) 21.dp else 23.dp

    fun entryText(isGit: Boolean): Dp = glyph(isGit) + Theme.Space.small
}
