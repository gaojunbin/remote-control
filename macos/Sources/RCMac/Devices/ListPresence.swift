import SwiftUI

/// An overlay's `isPresented` for the item it is about, the way the web opens a
/// dialog with `open={item !== null}`: shown while there is one, and closing it
/// — Escape, the backdrop, its own buttons — forgets the item.
@MainActor
enum ListPresence {
    static func of<Item>(_ item: Binding<Item?>) -> Binding<Bool> {
        Binding(get: { item.wrappedValue != nil }, set: { if !$0 { item.wrappedValue = nil } })
    }
}
