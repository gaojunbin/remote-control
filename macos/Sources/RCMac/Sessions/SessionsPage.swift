import SwiftUI

/// `/sessions`. A placeholder until the lists feature replaces it.
public struct SessionsPage: View {
    @Environment(MacAppModel.self) private var model

    public init() {}

    public var body: some View {
        PageHead(S.sessions.title)
    }
}
