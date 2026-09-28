import Foundation

/// What a demo device holds behind a session's turn, in the order it will
/// deliver it.
///
/// Amendment A43: the queue is always in `ts` order, and no two entries share
/// a `ts`. A message sent without `queue_ts` joins the end under the current
/// time, or one past the last entry's `ts` when that is later. A message sent
/// back with `queue_ts` — a queued message taken out to be edited — goes back
/// under that `ts`, in front of the first entry with a greater one, so it waits
/// in the place it left however the line moved meanwhile.
struct DemoQueue: Sendable {
    /// One held message: what the snapshot says about it, and the files it
    /// carries, which stay on the device until the message is delivered.
    struct Item: Sendable, Equatable {
        let message: QueuedMessage
        let files: [AttachmentInfo]

        init(id: String, text: String, ts: Int64, files: [AttachmentInfo] = []) {
            message = QueuedMessage(id: id, text: text, ts: ts,
                                    attachments: files.isEmpty ? nil : files.count)
            self.files = files
        }

        /// The same message under another `ts`.
        func stamped(_ ts: Int64) -> Item {
            Item(id: message.id, text: message.text, ts: ts, files: files)
        }
    }

    private(set) var items: [Item] = []

    init(_ items: [Item] = []) { self.items = items }

    /// The `queue` snapshot: every held message in delivery order, with the
    /// number of files each carries and never the files themselves.
    var pending: [QueuedMessage] { items.map(\.message) }

    var isEmpty: Bool { items.isEmpty }

    /// A message sent without `queue_ts`, stamped with the time it arrived:
    /// the end of the line, under a `ts` of its own.
    mutating func append(_ item: Item) {
        let after = items.last.map { $0.message.ts + 1 } ?? item.message.ts
        items.append(item.stamped(max(item.message.ts, after)))
    }

    /// A message sent with `queue_ts`: back under that `ts`, after any entry
    /// with the same one and before the first with a greater one.
    mutating func insert(_ item: Item) {
        let place = items.firstIndex { $0.message.ts > item.message.ts } ?? items.endIndex
        items.insert(item, at: place)
    }

    /// `session.queue_remove`: nil when nothing by that id is held — delivered
    /// already, or never queued — which the device answers with `not_found`.
    mutating func remove(id: String) -> Item? {
        guard let index = items.firstIndex(where: { $0.message.id == id }) else { return nil }
        return items.remove(at: index)
    }

    /// The next message to deliver, taken out of the line.
    mutating func next() -> Item? { items.isEmpty ? nil : items.removeFirst() }
}
