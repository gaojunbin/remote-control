import SwiftUI
import RCCore

/// Wraps a preview in a live demo gateway, so every `#Preview` shows the same
/// data the offline demo shows and exercises the real decoding path.
public struct DemoPreview<Content: View>: View {
    @State private var model = AppModel(arguments: [])
    @State private var ready = false
    private let content: () -> Content

    public init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    public var body: some View {
        Group {
            if ready {
                content().environment(model)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .tint(Theme.accent)
        .pageBackground()
        .task {
            guard !ready else { return }
            await model.enterDemo()
            ready = true
        }
    }
}

/// The demo session that shows a live turn, for previews that need one.
@MainActor
public func demoSession(_ id: String = DemoFixtures.liveSessionID) -> Session {
    DemoFixtures.sessions.first { $0.sessionID == id } ?? DemoFixtures.sessions[0]
}
