import SwiftUI

/// `/users` (A24), the admin's. A placeholder until the settings feature
/// replaces it.
public struct UsersPage: View {
    @Environment(MacAppModel.self) private var model

    public init() {}

    public var body: some View {
        PageHead(S.users.title)
    }
}
