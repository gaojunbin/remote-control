package com.junbingao.remotecontrol.win.preview.scenarios

import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.app.Route
import com.junbingao.remotecontrol.win.app.lastSignInError
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
import kotlin.time.Duration.Companion.seconds

/**
 * The foundation's scenarios, under the Mac renderer's names and sizes: the sign-in form in its
 * states, the landing rule, the topbar on each tab and at each of its breakpoints, Update required,
 * and the gallery of every primitive and icon with the overlay layer's checks.
 */
object FoundationScenarios {
    val all: List<PreviewScenario>
        get() = login + topbar + listOf(
            PreviewScenario("landing", route = Route.Landing),
            PreviewScenario("update-required", account = PreviewScenario.Account.updateRequired),
            PreviewScenario("update-required-zh", account = PreviewScenario.Account.updateRequired, language = InterfaceLanguage.zhHans),
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

    private val login: List<PreviewScenario>
        get() = listOf(
            PreviewScenario("login", account = PreviewScenario.Account.signedOut),
            PreviewScenario("login-remembered", account = PreviewScenario.Account.signedOut, setup = { context ->
                context.model.settings.remember(origin = context.gateway?.toString() ?: "https://rc.example.com", username = "admin")
            }),
            PreviewScenario(
                "login-error", stage = "login.error", account = PreviewScenario.Account.signedOut, settle = 2.seconds,
                setup = { context ->
                    // The demo takes any password for an account it has, so the refusal there is an
                    // account it does not.
                    context.model.settings.remember(
                        origin = context.gateway?.toString() ?: "https://demo.remote-control.invalid",
                        username = if (context.gateway == null) "nobody" else "admin",
                    )
                },
                // The refusal is what the picture is of, however long the first request of a run takes.
                prepare = { context -> context.wait { context.model.lastSignInError != null } },
            ),
            PreviewScenario("login-register", stage = "login.register", account = PreviewScenario.Account.signedOut),
            PreviewScenario("login-zh", account = PreviewScenario.Account.signedOut, language = InterfaceLanguage.zhHans),
            PreviewScenario("login-narrow", width = 480, height = 760, account = PreviewScenario.Account.signedOut),
        )

    /** The topbar on each tab, at 1280 and below each of its breakpoints. */
    private val topbar: List<PreviewScenario>
        get() = listOf(Route.Devices to "devices", Route.Sessions to "sessions", Route.Settings to "settings")
            .map { (route, name) -> PreviewScenario("topbar-$name", route = route) } + listOf(
            PreviewScenario("topbar-760", route = Route.Sessions, width = 760),
            PreviewScenario("topbar-480", route = Route.Sessions, width = 480, height = 760),
            PreviewScenario("topbar-420", route = Route.Settings, width = 420, height = 760),
            PreviewScenario("topbar-zh", route = Route.Devices, language = InterfaceLanguage.zhHans),
        )
}
