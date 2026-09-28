import RCCore
import RCMac
import SwiftUI

/// The composer feature's scenarios.
enum ComposerScenarios {
    static var all: [PreviewScenario] {
        [
            // How a scenario draws the composer alone while the chat page that
            // hosts it is another feature's: open the conversation in `setup`,
            // then draw `ComposerView` from it.
            PreviewScenario(name: "composer-alone", height: 240,
                            setup: { context in
                                if context.gateway == nil {
                                    await context.openChat(deviceId: DemoFixtures.macDeviceID,
                                                           sessionId: DemoFixtures.liveSessionID)
                                } else {
                                    await context.openChat(deviceId: "dev-mac", sessionId: "ses-vite")
                                }
                            },
                            content: { context in AnyView(context.chat.map { ComposerView(chat: $0) }) })
        ]
    }
}
