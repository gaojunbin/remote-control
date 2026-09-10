import SwiftUI
import WebKit
import RCCore

enum MarkdownVisualKind: String { case diagram, math, inlineMath }

private struct MarkdownTextScaleKey: EnvironmentKey { static let defaultValue: CGFloat = 1 }
extension EnvironmentValues {
    var markdownTextScale: CGFloat {
        get { self[MarkdownTextScaleKey.self] }
        set { self[MarkdownTextScaleKey.self] = newValue }
    }
}

/// Only a formula or diagram enters this renderer. Navigation, conversation and
/// code/text UI remain native. It has no login cookies, remote URLs or API bridge.
struct MarkdownVisualView: View {
    let kind: MarkdownVisualKind
    let source: String
    var maximumHeight: CGFloat = 720
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.openURL) private var openURL
    @Environment(\.markdownTextScale) private var textScale
    @ScaledMetric(relativeTo: .body) private var fontPixels = 17
    @State private var height: CGFloat = 72
    @State private var error: String?

    var body: some View {
        Group {
            if source.utf8.count > 128 * 1024 || (kind == .diagram && (source.count > 32 * 1024 || source.components(separatedBy: "\n").count > 500)) {
                Label("Too long to render. Read the source instead.", systemImage: "doc.text").font(.caption).foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    MarkdownWebSurface(kind: kind, source: source, dark: colorScheme == .dark, fontPixels: Double(fontPixels * textScale), onHeight: { measured in
                        height = min(maximumHeight, max(36, measured))
                    }, onError: { message in error = message }, onLink: { url in openURL(url) })
                        .frame(height: height)
                    if let error { Text(error).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
                }
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct MarkdownWebSurface {
    let kind: MarkdownVisualKind
    let source: String
    let dark: Bool
    let fontPixels: Double
    var onHeight: (CGFloat) -> Void
    var onError: (String?) -> Void
    var onLink: (URL) -> Void

    @MainActor func coordinator() -> Coordinator { Coordinator(onHeight: onHeight, onError: onError, onLink: onLink) }
    @MainActor func create(_ coordinator: Coordinator) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        // A missing resource bundle must degrade to "no preview", never crash.
        if let root = Bundle.module.resourceURL?.appending(path: "Markdown", directoryHint: .isDirectory) {
            let handler = MarkdownLocalAssets(root: root)
            coordinator.assets = handler
            config.setURLSchemeHandler(handler, forURLScheme: "rcmarkdown")
        }
        config.userContentController.add(coordinator, name: "renderStatus")
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = coordinator
        #if os(iOS)
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = true
        webView.scrollView.bounces = false
        #endif
        return webView
    }
    @MainActor func update(_ webView: WKWebView, coordinator: Coordinator) {
        coordinator.onHeight = onHeight; coordinator.onError = onError; coordinator.onLink = onLink
        let identity = "\(kind.rawValue)|\(dark)|\(fontPixels)|\(source)"
        guard coordinator.identity != identity else { return }
        coordinator.identity = identity
        guard let assets = coordinator.assets, let url = URL(string: "rcmarkdown://assets/index.html") else {
            onError("The preview assets are unavailable. The source is still readable.")
            return
        }
        assets.setDocument(documentHTML())
        webView.load(URLRequest(url: url))
    }
    private func documentHTML() -> Data {
        var payload: [String: JSONValue] = ["kind": .string(kind.rawValue), "source": .string(source), "dark": .bool(dark)]
        if kind == .inlineMath {
            var parts: [JSONValue] = []
            for span in MarkdownMath.spans(in: source) {
                if span.isMath { parts.append(["math": .string(span.text), "display": .bool(span.display)]) }
                else {
                    let attributed = markdownAttributed(span.text)
                    for run in attributed.runs {
                        var part: [String: JSONValue] = ["text": .string(String(attributed[run.range].characters))]
                        if let intent = run.inlinePresentationIntent {
                            part["strong"] = .bool(intent.contains(.stronglyEmphasized)); part["em"] = .bool(intent.contains(.emphasized))
                            part["code"] = .bool(intent.contains(.code)); part["strike"] = .bool(intent.contains(.strikethrough))
                        }
                        if let link = run.link, ["http", "https", "mailto"].contains(link.scheme?.lowercased() ?? "") { part["link"] = .string(link.absoluteString) }
                        parts.append(.object(part))
                    }
                }
            }
            payload["parts"] = .array(parts)
        }
        let encoded = ((try? JSONEncoder().encode(payload)) ?? Data()).base64EncodedString()
        let library = kind == .diagram ? "mermaid.min.js" : "katex.min.js"
        let color = dark ? "#f2f2f0" : "#111111"
        let html = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src rcmarkdown:; style-src 'unsafe-inline' rcmarkdown:; font-src rcmarkdown:; img-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
        <link rel="stylesheet" href="rcmarkdown://assets/katex.min.css">
        <style>:root{color-scheme:\(dark ? "dark" : "light")}
        html,body{margin:0;padding:0;background:transparent;color:\(color);font:\(fontPixels)px -apple-system,BlinkMacSystemFont,sans-serif;line-height:1.6}
        #content{padding:4px 0 8px;overflow-x:auto;overflow-y:hidden;overflow-wrap:anywhere;white-space:pre-wrap}
        svg{display:block;max-width:100%;height:auto;margin:auto} .katex-display{margin:8px 0;text-align:left}.katex{font-size:1.08em}
        code{font-family:ui-monospace,SFMono-Regular,monospace;background:rgba(128,128,128,.12);padding:1px 3px;border-radius:3px}a{color:\(dark ? "#f2f2f0" : "#111111")}
        </style><script src="rcmarkdown://assets/\(library)" defer></script><script src="rcmarkdown://assets/renderer.js" defer></script>
        </head><body><main id="content" data-payload="\(encoded)"></main></body></html>
        """
        return Data(html.utf8)
    }
    @MainActor final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var identity = ""
        var assets: MarkdownLocalAssets?
        var onHeight: (CGFloat) -> Void
        var onError: (String?) -> Void
        var onLink: (URL) -> Void
        init(onHeight: @escaping (CGFloat) -> Void, onError: @escaping (String?) -> Void, onLink: @escaping (URL) -> Void) { self.onHeight = onHeight; self.onError = onError; self.onLink = onLink }
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == "renderStatus", message.frameInfo.isMainFrame, let value = message.body as? [String: Any] else { return }
            if let height = value["height"] as? Double, height.isFinite { onHeight(CGFloat(min(4000, max(36, height)))) }
            onError((value["error"] as? String).map { String($0.prefix(160)) })
        }
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
            guard let url = navigationAction.request.url else { return .cancel }
            if url.scheme == "rcmarkdown" && url.host == "assets" { return .allow }
            if navigationAction.navigationType == .linkActivated, ["http", "https", "mailto"].contains(url.scheme?.lowercased() ?? "") { onLink(url) }
            return .cancel
        }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: any Error) { onError("The preview did not finish. The source is still available.") }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: any Error) { onError("The preview did not finish. The source is still available.") }
    }
}

#if os(iOS)
extension MarkdownWebSurface: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { coordinator() }
    func makeUIView(context: Context) -> WKWebView { create(context.coordinator) }
    func updateUIView(_ uiView: WKWebView, context: Context) { update(uiView, coordinator: context.coordinator) }
    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.stopLoading(); uiView.navigationDelegate = nil
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "renderStatus")
    }
}
#else
extension MarkdownWebSurface: NSViewRepresentable {
    func makeCoordinator() -> Coordinator { coordinator() }
    func makeNSView(context: Context) -> WKWebView { create(context.coordinator) }
    func updateNSView(_ nsView: WKWebView, context: Context) { update(nsView, coordinator: context.coordinator) }
    static func dismantleNSView(_ nsView: WKWebView, coordinator: Coordinator) {
        nsView.stopLoading(); nsView.navigationDelegate = nil
        nsView.configuration.userContentController.removeScriptMessageHandler(forName: "renderStatus")
    }
}
#endif

/// The scheme serves only packaged assets. It cannot read an arbitrary local
/// file, reach the relay, or issue a network request on behalf of Markdown.
private final class MarkdownLocalAssets: NSObject, WKURLSchemeHandler, @unchecked Sendable {
    private let root: URL
    private let lock = NSLock()
    private var document = Data()
    init(root: URL) { self.root = root.standardizedFileURL }
    func setDocument(_ data: Data) { lock.lock(); document = data; lock.unlock() }
    func webView(_ webView: WKWebView, start urlSchemeTask: any WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url, url.scheme == "rcmarkdown", url.host == "assets" else {
            urlSchemeTask.didFailWithError(URLError(.noPermissionsToReadFile)); return
        }
        let path = url.path
        let data: Data, mime: String
        if path == "/index.html" {
            lock.lock(); data = document; lock.unlock(); mime = "text/html"
        } else {
            let target = root.appendingPathComponent(String(path.dropFirst())).standardizedFileURL
            guard target.path.hasPrefix(root.path + "/"), ["js", "css", "woff2"].contains(target.pathExtension), let bytes = try? Data(contentsOf: target) else {
                urlSchemeTask.didFailWithError(URLError(.fileDoesNotExist)); return
            }
            data = bytes
            mime = target.pathExtension == "js" ? "application/javascript" : target.pathExtension == "css" ? "text/css" : "font/woff2"
        }
        urlSchemeTask.didReceive(URLResponse(url: url, mimeType: mime, expectedContentLength: data.count, textEncodingName: mime == "font/woff2" ? nil : "utf-8"))
        urlSchemeTask.didReceive(data); urlSchemeTask.didFinish()
    }
    func webView(_ webView: WKWebView, stop urlSchemeTask: any WKURLSchemeTask) {}
}

/// The vendored KaTeX and mermaid assets, or nil when the resource bundle is
/// missing. Nothing force-unwraps this; a missing bundle degrades to source.
public enum MarkdownAssets {
    public static var directory: URL? {
        Bundle.module.resourceURL?.appending(path: "Markdown", directoryHint: .isDirectory)
    }
}
