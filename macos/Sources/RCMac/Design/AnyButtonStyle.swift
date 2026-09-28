import SwiftUI

/// Any button style, erased, so a control that draws its own trigger — a
/// popover — can take whichever style the feature gives it.
public struct AnyButtonStyle: ButtonStyle {
    private let make: @MainActor (Configuration) -> AnyView

    public init<Style: ButtonStyle>(_ style: Style) {
        make = { AnyView(style.makeBody(configuration: $0)) }
    }

    public func makeBody(configuration: Configuration) -> some View { make(configuration) }
}
