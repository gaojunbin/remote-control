import Foundation
import JavaScriptCore

/// The web's own Markdown pipeline, run in JavaScriptCore: react-markdown 10's
/// processor — remark-parse, remark-gfm, remark-rehype and rehype-highlight on
/// the common languages of highlight.js 11.11.2, the copy lowlight brings —
/// bundled into
/// `Resources/Highlight/markdown.bundle.js` (its README says how). Running the
/// same code is what makes a table, a task list or a highlighted block come out
/// of the parser exactly as it does in the browser.
///
/// One context for the app, on the main actor, because JavaScriptCore runs a
/// context on one thread at a time. A message is parsed once per text: a
/// streaming answer is parsed again on every delta, as React re-renders it.
@MainActor
final class MarkdownEngine {
    static let shared = MarkdownEngine()

    private var render: JSValue?
    private var loaded = false
    private var cache: [String: ChatMarkdown] = [:]
    private var order: [String] = []

    /// How many documents are kept: enough for every message on screen and a
    /// page either side, few enough that a long day leaves nothing behind.
    private static let capacity = 256

    /// The document a message's text makes. Without the pipeline — a bundle
    /// that failed to load — the text is drawn as it arrived, which is what the
    /// web draws while its Markdown chunk is still on its way.
    func document(_ text: String) -> ChatMarkdown {
        if let cached = cache[text] { return cached }
        let document = parse(text).map { MDBuilder.document($0) } ?? .plain(text)
        store(document, for: text)
        return document
    }

    private func parse(_ text: String) -> [HastNode]? {
        guard let render = renderFunction(),
              let json = render.call(withArguments: [text])?.toString() else { return nil }
        return HastNode.decode(json)
    }

    private func store(_ document: ChatMarkdown, for text: String) {
        cache[text] = document
        order.append(text)
        if order.count > Self.capacity {
            cache.removeValue(forKey: order.removeFirst())
        }
    }

    private func renderFunction() -> JSValue? {
        if loaded { return render }
        loaded = true
        guard let url = Bundle.module.url(forResource: "markdown.bundle", withExtension: "js",
                                          subdirectory: "Highlight"),
              let source = try? String(contentsOf: url, encoding: .utf8),
              let context = JSContext() else { return nil }
        context.evaluateScript(source)
        let function = context.objectForKeyedSubscript("rcMarkdown")
        render = function?.isObject == true ? function : nil
        return render
    }
}
