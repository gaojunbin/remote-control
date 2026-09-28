import SwiftUI

/// `/devices`. A placeholder until the lists feature replaces it.
public struct DevicesPage: View {
    @Environment(MacAppModel.self) private var model

    public init() {}

    public var body: some View {
        PageHead(S.devices.title)
    }
}
