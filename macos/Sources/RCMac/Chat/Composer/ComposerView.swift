import RCCore
import SwiftUI

/// The composer of one conversation. A placeholder until the composer feature
/// replaces it.
public struct ComposerView: View {
    let chat: ChatStore
    @Environment(MacAppModel.self) private var model

    public init(chat: ChatStore) { self.chat = chat }

    public var body: some View {
        Hint(S.composer.placeholder)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Space.sp4)
    }
}
