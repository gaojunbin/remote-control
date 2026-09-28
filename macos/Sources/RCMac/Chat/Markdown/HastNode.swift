import Foundation

/// One node of the hast tree the web hands React: what react-markdown 10 makes
/// of a message with remark-gfm and rehype-highlight, after its own `post`
/// step. The bundled pipeline writes it compactly — an element is `[tag,
/// properties, children]`, a text node is a string — and this reads it back.
enum HastNode: Equatable, Sendable {
    case text(String)
    case element(HastElement)

    /// The nodes of a JSON document the pipeline wrote, or nil when it is not
    /// one.
    static func decode(_ json: String) -> [HastNode]? {
        guard let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [Any] else { return nil }
        return array.compactMap(node)
    }

    private static func node(_ value: Any) -> HastNode? {
        if let text = value as? String { return .text(text) }
        guard let parts = value as? [Any], parts.count == 3, let tag = parts[0] as? String else { return nil }
        let properties = parts[1] as? [String: Any] ?? [:]
        let children = (parts[2] as? [Any] ?? []).compactMap(node)
        return .element(HastElement(tag: tag, properties: HastProperties(properties), children: children))
    }

    /// The text a node holds, as `textContent` reads it.
    var textContent: String {
        switch self {
        case .text(let value): value
        case .element(let element): element.textContent
        }
    }
}

struct HastElement: Equatable, Sendable {
    let tag: String
    let properties: HastProperties
    let children: [HastNode]

    var textContent: String { children.map(\.textContent).joined() }

    /// The children that are elements, without the whitespace between them.
    var elements: [HastElement] {
        children.compactMap { if case .element(let element) = $0 { element } else { nil } }
    }
}

/// The properties the chat's Markdown reads.
struct HastProperties: Equatable, Sendable {
    var className: [String] = []
    var href: String?
    var src: String?
    var alt: String?
    var title: String?
    var checked: Bool?
    var start: Int?
    var align: String?
    var id: String?

    init(_ raw: [String: Any]) {
        className = raw["className"] as? [String] ?? []
        href = raw["href"] as? String
        src = raw["src"] as? String
        alt = raw["alt"] as? String
        title = raw["title"] as? String
        checked = raw["checked"] as? Bool
        start = (raw["start"] as? NSNumber)?.intValue
        align = raw["align"] as? String
        id = raw["id"] as? String
    }

    init() {}
}
