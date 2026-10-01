package com.junbingao.remotecontrol.win.sessions.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.design.Btn
import com.junbingao.remotecontrol.win.design.Button
import com.junbingao.remotecontrol.win.design.ButtonSize
import com.junbingao.remotecontrol.win.design.ButtonStyle
import com.junbingao.remotecontrol.win.design.ButtonVariant
import com.junbingao.remotecontrol.win.design.Disabled
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.FormError
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.HStackScope
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Radius
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.ThinScrollView
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.WithForeground
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.icons.Icon
import com.junbingao.remotecontrol.win.design.icons.LucideIcon
import com.junbingao.remotecontrol.win.design.overlay.Modal
import com.junbingao.remotecontrol.win.devices.ListPresence
import com.junbingao.remotecontrol.win.devices.TextMeasure
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.launch

/**
 * `DirectoryPicker.tsx`: the modal Browse opens over the New session drawer — the path on screen
 * with New folder beside it (A37), Up one level, the sub-directories with a branch mark on
 * repositories, and Cancel and Use this directory. It browses and makes, and never deletes,
 * renames or moves.
 */
@Composable
internal fun DirectoryPicker(browser: MutableState<DirectoryBrowser?>, onPick: (String) -> Unit) {
    val presence = ListPresence.of(browser)
    val open = browser.value
    Modal(
        isPresented = presence.isPresented,
        onDismiss = presence.onDismiss,
        title = S.newSession.browseTitle,
        width = 520.dp,
        showClose = true,
        footer = {
            Btn(S.common.cancel) { browser.value = null }
            Disabled(open?.listing == null) {
                Btn(S.newSession.browseUse, variant = ButtonVariant.primary) {
                    open?.listing?.path?.let(onPick)
                    browser.value = null
                }
            }
        },
    ) {
        if (open != null) DirectoryListingView(open)
    }
}

/** What the picker shows between its title and its buttons. */
@Composable
private fun DirectoryListingView(browser: DirectoryBrowser) {
    val listing = browser.listing
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        HStack(Modifier.fillMaxWidth().padding(bottom = Space.sp3), spacing = Space.sp3, alignment = Alignment.Top) {
            // `word-break: break-all`, so a deep path wraps where it must.
            Text(
                listing?.let { TextMeasure.breakAll(Format.tildePath(it.path)) } ?: S.common.loading,
                css(FontSize.fs13, mono = true),
                Modifier.weight(1f),
                color = Palette.inkSecondary,
            )
            Disabled(listing == null || browser.naming) {
                Btn(S.newSession.newFolder, icon = LucideIcon.folderPlus, size = ButtonSize.small) { browser.startNaming() }
            }
        }
        if (browser.naming) NewFolderRow(browser, Modifier.padding(bottom = Space.sp3))
        // `.login-error`'s -6 top margin, collapsed into the 12 above it.
        browser.error?.let { FormError(it, Modifier.raised(6.dp).padding(bottom = Space.sp4)) }
        DirectoryEntries(browser)
    }
}

/** A negative top margin: the view drawn `by` higher, and its place in the stack that much shorter. */
private fun Modifier.raised(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val lift = by.roundToPx()
    layout(placeable.width, (placeable.height - lift).coerceAtLeast(0)) { placeable.place(0, -lift) }
}

/** `.picker-list`: the rows on a 1 px `--line` edge, parted by the same line, scrolling past 320 px. */
@Composable
private fun DirectoryEntries(browser: DirectoryBrowser) {
    val model = LocalAppModel.current
    val listing = browser.listing
    val shape = RoundedCornerShape(Radius.md)
    Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 320.dp).clip(shape).border(1.dp, Palette.line, shape)) {
        ThinScrollView(modifier = Modifier.fillMaxWidth()) {
            VStack(Modifier.fillMaxWidth().padding(1.dp), spacing = 0.dp) {
                val parent = listing?.parent
                if (parent != null) {
                    DirectoryRow(LucideIcon.chevronUp, action = { model.tasks.launch { browser.open(parent) } }) {
                        Text(S.newSession.browseUp, css(FontSize.fs13))
                    }
                }
                listing?.entries.orEmpty().forEachIndexed { index, entry ->
                    if (index > 0 || parent != null) DirectoryRule()
                    DirectoryRow(LucideIcon.folder, action = { model.tasks.launch { browser.open(entry.path) } }) {
                        Text(entry.name, css(FontSize.fs13, mono = true), color = Palette.ink)
                        if (entry.isGit) {
                            Spacer(Modifier.weight(1f))
                            Icon(LucideIcon.gitBranch, size = 13.dp, color = Palette.inkTertiary)
                        }
                    }
                }
                if (listing != null && listing.entries.isEmpty() && !browser.loading) {
                    if (parent != null) DirectoryRule()
                    Text(
                        S.newSession.browseEmpty, css(FontSize.fs13), Modifier.fillMaxWidth().padding(Space.sp4),
                        color = Palette.inkSecondary, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectoryRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
}

/** One row of the listing: its icon and label in the secondary ink, a tint and the ink under the pointer. */
@Composable
private fun DirectoryRow(icon: LucideIcon, action: () -> Unit, label: @Composable HStackScope.() -> Unit) {
    Button(action, Modifier.fillMaxWidth(), style = DirectoryRowStyle) {
        HStack(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = Space.sp3), spacing = Space.sp3) {
            Icon(icon, size = 15.dp)
            label()
        }
    }
}

private val DirectoryRowStyle = ButtonStyle { configuration, modifier ->
    WithForeground(if (configuration.isHovered) Palette.ink else Palette.inkSecondary) {
        Box(modifier.background(if (configuration.isHovered) Palette.surfaceHover else Color.Transparent)) { configuration.label() }
    }
}
