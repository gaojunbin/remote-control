import AppKit
import RCCore
import RCMac
import SwiftUI

/// Renders scenarios one at a time, each in a fresh ephemeral model and its
/// own offscreen window, and writes `<out>/<name>.png`.
@MainActor
struct PreviewRenderer {
    let arguments: PreviewArguments

    func render(_ scenario: PreviewScenario, to directory: URL) async throws -> URL {
        let model = MacAppModel(options: options(for: scenario))
        defer { model.discardEphemeralState() }
        try await reach(scenario, model: model)
        model.router.replace(scenario.route)
        await scenario.setup(PreviewContext(model: model, gateway: arguments.gatewayURL))

        let width = scenario.width ?? arguments.width
        let height = scenario.height ?? arguments.height
        let root = PreviewRoot(model: model, stage: scenario.stage, content: scenario.content)
        let window = PreviewWindow.make(width: width, height: height, content: root)
        defer { window.orderOut(nil); window.close() }

        await scenario.prepare(PreviewContext(model: model, gateway: arguments.gatewayURL))
        try await Task.sleep(for: scenario.settle)
        window.displayIfNeeded()
        guard let view = PreviewWindow.frameView(of: window) else { throw LayerCapture.Failure.noLayer }
        let png = try LayerCapture.png(of: view, scale: arguments.scale)
        let file = directory.appending(path: "\(scenario.name).png")
        try png.write(to: file, options: .atomic)
        if model.isSignedIn, !model.isDemo { await model.signOut() }
        return file
    }

    /// Every run is ephemeral: nothing of the person's is read or written.
    private func options(for scenario: PreviewScenario) -> LaunchOptions {
        let demo: Bool
        switch arguments.source {
        case .demo: demo = true
        case .gateway: demo = false
        }
        return LaunchOptions(demo: (demo && scenario.account == .signedIn) || scenario.account == .updateRequired,
                             demoAccount: demo && scenario.account == .signedOut,
                             demoUpdateRequired: scenario.account == .updateRequired,
                             ephemeral: true,
                             language: scenario.language ?? arguments.language)
    }

    /// Sign in, or stay at the form, as the scenario asks.
    private func reach(_ scenario: PreviewScenario, model: MacAppModel) async throws {
        let context = PreviewContext(model: model, gateway: arguments.gatewayURL)
        switch scenario.account {
        case .signedIn:
            if case .gateway(let url, let username, let password) = arguments.source {
                await model.signIn(origin: url.absoluteString, username: username, password: password)
                guard model.isSignedIn else {
                    throw ArgumentError("could not sign in to \(url.absoluteString) as \(username)")
                }
            }
            await model.restoreOrPrompt()
            guard await context.wait(timeout: .seconds(10), until: { model.connection.hasSnapshot }) else {
                throw ArgumentError("\(scenario.name): no hello from the gateway")
            }
        case .signedOut:
            await model.restoreOrPrompt()
        case .updateRequired:
            await model.restoreOrPrompt()
            guard await context.wait(until: { model.connection.updateRequired != nil }) else {
                throw ArgumentError("\(scenario.name): the demo asked for no update")
            }
        }
    }
}

/// The app's root, or one view drawn the way the root draws a screen, with the
/// scenario's stage in the environment.
private struct PreviewRoot: View {
    let model: MacAppModel
    let stage: String?
    let content: (@MainActor @Sendable () -> AnyView)?

    var body: some View {
        Group {
            if let content { RootView(showing: content()) } else { RootView() }
        }
        .environment(model)
        .environment(\.previewStage, stage)
    }
}
