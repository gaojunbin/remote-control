import Testing
@testable import RCMac

@Suite("Router") @MainActor
struct RouterTests {
    @Test func historyWalksBackAndForwardAsABrowserDoes() {
        let router = Router()
        router.replace(.sessions)
        router.go(.devices)
        router.go(.device(id: "mac"))
        #expect(router.canGoBack)
        router.back()
        #expect(router.route == .devices)
        router.back()
        #expect(router.route == .sessions)
        #expect(!router.canGoBack)
        router.forward()
        #expect(router.route == .devices)
        // A link followed from the middle of the history drops what was ahead.
        router.go(.settings)
        #expect(!router.canGoForward)
    }

    @Test func goingWhereTheAppAlreadyIsAddsNothing() {
        let router = Router()
        router.replace(.sessions)
        router.go(.sessions)
        #expect(!router.canGoBack)
    }

    @Test func signingOutRemembersWhereASignInReturns() {
        let router = Router()
        router.replace(.settings)
        router.go(.devices)
        router.signedOut()
        #expect(router.route == .login)
        #expect(!router.canGoBack)
        router.signedIn()
        #expect(router.route == .devices)
        router.signedOut()
        router.signedOut()
        router.signedIn()
        #expect(router.route == .devices)
    }

    @Test func aSignInWithNowhereToReturnLands() {
        let router = Router()
        router.signedOut()
        router.signedIn()
        #expect(router.route == .landing)
    }

    @Test func aMemberWhoAsksForUsersIsSentToSessions() {
        let router = Router()
        let account = SignedInAccount()
        router.canSeeUsers = { account.isAdmin }
        router.go(.users)
        #expect(router.route == .sessions)
        account.isAdmin = true
        router.go(.users)
        #expect(router.route == .users)
    }

    @Test func newSessionIsAskedForOnce() {
        let router = Router()
        router.replace(.devices)
        router.requestNewSession()
        #expect(router.route == .sessions)
        #expect(router.takeNewSessionRequest())
        #expect(!router.takeNewSessionRequest())
    }

    @Test func routesAreTheWebsPaths() {
        let routes: [Route] = [.landing, .login, .devices, .device(id: "dev mac"), .terminal(deviceId: "dev-mac"),
                               .sessions, .chat(deviceId: "dev-mac", sessionId: "ses-vite"), .settings, .users]
        for route in routes { #expect(Route(path: route.path) == route) }
        #expect(Route.chat(deviceId: "dev-mac", sessionId: "ses-vite").path == "/sessions/dev-mac/ses-vite")
        #expect(Route(path: "/pair") == .landing)
        #expect(Route(path: "/nowhere/at/all/here") == .landing)
    }

    @Test func theTopbarMarksATabForItsPlaceAndThoseBelowIt() {
        #expect(Route.device(id: "x").tab == .devices)
        #expect(Route.chat(deviceId: "d", sessionId: "s").tab == .sessions)
        #expect(Route.users.tab == nil)
        #expect(Route.devices.isInLayout && !Route.chat(deviceId: "d", sessionId: "s").isInLayout)
    }

    @Test func landingPicksSessionsOnlyWhenThereIsADevice() {
        #expect(Landing.destination(hasDevices: true) == .sessions)
        #expect(Landing.destination(hasDevices: false) == .devices)
    }
}

/// Whether the signed-in account is the admin, which a test changes halfway.
@MainActor
private final class SignedInAccount {
    var isAdmin = false
}
