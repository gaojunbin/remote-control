import Testing
import Foundation
@testable import RCCore

/// What back-pressure costs on the two transport streams.
@Suite("Event buffering")
struct EventBufferTests {
    @Test("A full buffer drops the oldest element, never the newest")
    func theNewestSurvive() async {
        let stream = EventBuffer.makeStream(of: Int.self, capacity: 3)
        for value in 1...5 { stream.continuation.yield(value) }
        stream.continuation.finish()

        var received: [Int] = []
        for await value in stream.stream { received.append(value) }
        #expect(received == [3, 4, 5],
                "a burst that outran the reader loses its beginning, not its end")
    }

    @Test("Both transports declare their capacity here")
    func capacities() {
        #expect(EventBuffer.appCapacity == 1024)
        #expect(EventBuffer.sttCapacity == 256)
    }
}
