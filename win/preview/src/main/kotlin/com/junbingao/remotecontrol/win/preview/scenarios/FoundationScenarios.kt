package com.junbingao.remotecontrol.win.preview.scenarios

import com.junbingao.remotecontrol.win.gallery.Gallery
import com.junbingao.remotecontrol.win.gallery.MenuGallery
import com.junbingao.remotecontrol.win.gallery.OrderedOverlay
import com.junbingao.remotecontrol.win.gallery.OverlayGallery
import com.junbingao.remotecontrol.win.gallery.OverlayGalleryKind
import com.junbingao.remotecontrol.win.gallery.OverlayOrder
import com.junbingao.remotecontrol.win.gallery.OverlayOrderGallery
import com.junbingao.remotecontrol.win.gallery.ScrollThinGallery
import com.junbingao.remotecontrol.win.gallery.TokenGallery
import kotlin.time.Duration.Companion.milliseconds

/**
 * The foundation's scenarios that need no app model — the gallery of every primitive and icon and
 * the overlay layer's checks — under the Mac renderer's names and sizes. The sign-in form, the
 * landing rule, the topbar on each tab and Update required draw the app's own screens, which the
 * model and the features bring (stages 2 and 3), and join this list with them.
 */
object FoundationScenarios {
    val all: List<PreviewScenario>
        get() = listOf(
            PreviewScenario("gallery", height = 1720, content = { Gallery() }),
            PreviewScenario("gallery-tokens", height = 1500, content = { TokenGallery() }),
            PreviewScenario("gallery-popover", content = { OverlayGallery(OverlayGalleryKind.popover) }),
            PreviewScenario("gallery-menu", content = { MenuGallery() }),
            PreviewScenario("gallery-modal", content = { OverlayGallery(OverlayGalleryKind.modal) }),
            PreviewScenario("gallery-modal-sheet", width = 600, height = 760, content = { OverlayGallery(OverlayGalleryKind.modal) }),
            PreviewScenario("gallery-drawer", content = { OverlayGallery(OverlayGalleryKind.drawer) }),
            PreviewScenario("gallery-confirm", content = { OverlayGallery(OverlayGalleryKind.confirm) }),
            PreviewScenario("gallery-scroll-thin", content = { ScrollThinGallery() }),
        ) + overlayOrder

    /**
     * The overlay layer's regression check: every kind opened from a control that asks for them
     * all, the modal in each of the three orders and with its form handed to the views that draw
     * it, and a modal over the drawer.
     */
    private val overlayOrder: List<PreviewScenario>
        get() = listOf(
            Triple("gallery-order-modal", OverlayOrder.confirmFirst, OrderedOverlay.modal),
            Triple("gallery-order-modal-panel-first", OverlayOrder.panelFirst, OrderedOverlay.modal),
            Triple("gallery-order-modal-first", OverlayOrder.modalFirst, OrderedOverlay.modal),
            Triple("gallery-order-modal-binding", OverlayOrder.formBoundToChildren, OrderedOverlay.modal),
            Triple("gallery-order-drawer", OverlayOrder.panelFirst, OrderedOverlay.drawer),
            Triple("gallery-order-confirm", OverlayOrder.modalFirst, OrderedOverlay.confirm),
            Triple("gallery-order-panel", OverlayOrder.confirmFirst, OrderedOverlay.panel),
            Triple("gallery-order-closable-modal", OverlayOrder.modalFirst, OrderedOverlay.closableModal),
            Triple("gallery-order-modal-over-drawer", OverlayOrder.panelFirst, OrderedOverlay.modalOverDrawer),
            Triple("gallery-order-modal-in-drawer", OverlayOrder.confirmFirst, OrderedOverlay.modalInDrawer),
        ).map { (name, order, open) ->
            PreviewScenario(name, settle = 1400.milliseconds, content = { OverlayOrderGallery(order, open) })
        }
}
