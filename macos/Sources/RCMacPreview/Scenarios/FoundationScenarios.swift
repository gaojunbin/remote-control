import RCCore
import RCMac
import SwiftUI

/// The foundation's scenarios: the sign-in form in its states, the landing
/// rule, the topbar on each tab and at each of its breakpoints, Update
/// required, and the gallery of every primitive and icon.
enum FoundationScenarios {
    static var all: [PreviewScenario] {
        login + topbar + [
            PreviewScenario(name: "landing", route: .landing),
            PreviewScenario(name: "update-required", account: .updateRequired),
            PreviewScenario(name: "update-required-zh", account: .updateRequired, language: .zhHans),
            PreviewScenario(name: "gallery", height: 1720, content: { _ in AnyView(Gallery()) }),
            PreviewScenario(name: "gallery-tokens", height: 1500, content: { _ in AnyView(TokenGallery()) }),
            PreviewScenario(name: "gallery-popover", content: { _ in AnyView(OverlayGallery(kind: .popover)) }),
            PreviewScenario(name: "gallery-menu", content: { _ in AnyView(MenuGallery()) }),
            PreviewScenario(name: "gallery-modal", content: { _ in AnyView(OverlayGallery(kind: .modal)) }),
            PreviewScenario(name: "gallery-modal-sheet", width: 600, height: 760,
                            content: { _ in AnyView(OverlayGallery(kind: .modal)) }),
            PreviewScenario(name: "gallery-drawer", content: { _ in AnyView(OverlayGallery(kind: .drawer)) }),
            PreviewScenario(name: "gallery-confirm", content: { _ in AnyView(OverlayGallery(kind: .confirm)) }),
            PreviewScenario(name: "gallery-scroll-thin", content: { _ in AnyView(ScrollThinGallery()) })
        ] + overlayOrder
    }

    /// The overlay layer's regression check: every kind opened from a control
    /// that chains them all, the modal in each of the three orders and with its
    /// form bound to the views that draw it, and a modal over the drawer.
    private static var overlayOrder: [PreviewScenario] {
        [
            ("gallery-order-modal", OverlayOrderGallery.Order.confirmFirst, OverlayOrderGallery.Overlay.modal),
            ("gallery-order-modal-panel-first", .panelFirst, .modal),
            ("gallery-order-modal-first", .modalFirst, .modal),
            ("gallery-order-modal-binding", .formBoundToChildren, .modal),
            ("gallery-order-drawer", .panelFirst, .drawer),
            ("gallery-order-confirm", .modalFirst, .confirm),
            ("gallery-order-panel", .confirmFirst, .panel),
            ("gallery-order-closable-modal", .modalFirst, .closableModal),
            ("gallery-order-modal-over-drawer", .panelFirst, .modalOverDrawer),
            ("gallery-order-modal-in-drawer", .confirmFirst, .modalInDrawer)
        ].map { name, order, open in
            PreviewScenario(name: name, settle: .milliseconds(1400),
                            content: { _ in AnyView(OverlayOrderGallery(order: order, open: open)) })
        }
    }

    private static var login: [PreviewScenario] {
        [
            PreviewScenario(name: "login", account: .signedOut),
            PreviewScenario(name: "login-remembered", account: .signedOut, setup: { context in
                context.model.settings.remember(origin: context.gateway?.absoluteString ?? "https://rc.example.com",
                                                username: "admin")
            }),
            PreviewScenario(name: "login-error", stage: "login.error", account: .signedOut, settle: .seconds(2),
                            setup: { context in
                // The demo takes any password for an account it has, so the
                // refusal there is an account it does not.
                context.model.settings.remember(origin: context.gateway?.absoluteString
                                                    ?? "https://demo.remote-control.invalid",
                                                username: context.gateway == nil ? "nobody" : "admin")
            }),
            PreviewScenario(name: "login-register", stage: "login.register", account: .signedOut),
            PreviewScenario(name: "login-zh", account: .signedOut, language: .zhHans),
            PreviewScenario(name: "login-narrow", width: 480, height: 760, account: .signedOut)
        ]
    }

    /// The topbar on each tab, at 1280 and below each of its breakpoints.
    private static var topbar: [PreviewScenario] {
        var scenarios: [PreviewScenario] = []
        for (route, name) in [(Route.devices, "devices"), (.sessions, "sessions"), (.settings, "settings")] {
            scenarios.append(PreviewScenario(name: "topbar-\(name)", route: route))
        }
        scenarios.append(PreviewScenario(name: "topbar-760", route: .sessions, width: 760))
        scenarios.append(PreviewScenario(name: "topbar-480", route: .sessions, width: 480, height: 760))
        scenarios.append(PreviewScenario(name: "topbar-420", route: .settings, width: 420, height: 760))
        scenarios.append(PreviewScenario(name: "topbar-zh", route: .devices, language: .zhHans))
        return scenarios
    }
}
