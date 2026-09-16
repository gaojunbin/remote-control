import Observation

/// One run of an action at a time, and a way for its control to say so.
///
/// Retry on the "Delivery unconfirmed" banner reuses the original request id
/// (`docs/DESIGN.md` § "The composer"), so two overlapping retries would put
/// two requests under one id — which is the defect the transport fix names.
/// The button is disabled while `isBusy`, and a tap that beats the redraw
/// starts nothing either.
@MainActor
@Observable
public final class OneAtATime {
    public private(set) var isBusy = false

    public init() {}

    public func run(_ work: () async -> Void) async {
        guard !isBusy else { return }
        isBusy = true
        await work()
        isBusy = false
    }
}
