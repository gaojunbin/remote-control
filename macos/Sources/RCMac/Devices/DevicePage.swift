import SwiftUI

/// `/devices/:deviceId` (A33). A placeholder until the lists feature replaces it.
public struct DevicePage: View {
    let deviceId: String
    @Environment(MacAppModel.self) private var model

    public init(deviceId: String) { self.deviceId = deviceId }

    public var body: some View {
        PageHead(model.device(deviceId)?.name ?? S.devicePage.gone)
    }
}
