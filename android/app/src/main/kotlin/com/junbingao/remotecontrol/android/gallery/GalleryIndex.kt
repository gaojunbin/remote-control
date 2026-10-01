package com.junbingao.remotecontrol.android.gallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.junbingao.remotecontrol.android.design.SystemColor
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.navigation.LocalNavigator
import com.junbingao.remotecontrol.android.navigation.NavigationScreen
import com.junbingao.remotecontrol.android.shell.GalleryPageRoute
import com.junbingao.remotecontrol.android.system.InsetGroupedList

/**
 * The debug gallery: every primitive of the design system and every iPhone system piece, drawn
 * as the screens will draw them, so a change can be looked at before a screen uses it and a
 * screenshot test has something to hold. It is a developer's tool, reached from a debug build's
 * Settings or `--gallery`, and its own words are not product strings.
 */
@Composable
fun GalleryIndex() {
    val navigator = LocalNavigator.current
    NavigationScreen("Gallery") { insets ->
        InsetGroupedList(Modifier.fillMaxSize(), contentPadding = insets.padding()) {
            section(key = "pages") {
                for (page in GalleryPages.all) {
                    row(key = page.id, onClick = { navigator?.push(GalleryPageRoute(page.id)) }, tag = "gallery.${page.id}") {
                        Text(page.title, style = Theme.Text.label, color = Theme.ink)
                        Text(page.summary, style = Theme.Text.caption, color = SystemColor.secondaryLabel)
                    }
                }
            }
        }
    }
}

/** One page of the gallery. */
@Composable
fun GalleryPage(id: String) {
    when (id) {
        GalleryPages.type.id -> TypeGallery()
        GalleryPages.controls.id -> ControlsGallery()
        GalleryPages.glyphs.id -> GlyphsGallery()
        GalleryPages.symbols.id -> SymbolsGallery()
        GalleryPages.inputs.id -> InputsGallery()
        GalleryPages.lists.id -> ListsGallery()
        GalleryPages.presentations.id -> PresentationsGallery()
        GalleryPages.services.id -> ServicesGallery()
    }
}

/** The gallery's pages, in the order the index lists them. */
object GalleryPages {
    class Page(val id: String, val title: String, val summary: String)

    val type = Page("type", "Type and colour", "The text styles and every token, light or dark")
    val controls = Page("controls", "Controls", "Dots, labels, chips, buttons, banners, rows")
    val glyphs = Page("glyphs", "Glyphs", "Laptop, folder, effort gauge, prompt shield, agent logos")
    val symbols = Page("symbols", "Symbols", "Every SF Symbol the iPhone draws, as lucide draws it here")
    val inputs = Page("inputs", "Inputs", "Growing field, stop slider, switch, segmented control, search")
    val lists = Page("lists", "Lists", "Inset grouped sections, separators, swipe actions, context menus")
    val presentations = Page("presentations", "Presentations", "Sheets, alerts, dialogs, menus, covers")
    val services = Page("services", "Services", "Terminal, Markdown diagrams, the pairing scanner")

    val all = listOf(type, controls, glyphs, symbols, inputs, lists, presentations, services)
}
