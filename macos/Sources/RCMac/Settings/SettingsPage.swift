import SwiftUI

/// `/settings`. A placeholder until the settings feature replaces it.
public struct SettingsPage: View {
    @Environment(MacAppModel.self) private var model

    public init() {}

    public var body: some View {
        PageHead(S.settings.title)
    }
}
