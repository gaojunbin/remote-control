import Foundation
import RCCore

/// `swift run RCMacPreview [--demo | --gateway <url> --username <u> --password <p>]
/// (--scenario <name>… | --all | --list) [--width W --height H] [--scale 1|2]
/// [--language en|zh-Hans] --out <dir>`
struct PreviewArguments {
    enum Source {
        case demo
        case gateway(url: URL, username: String, password: String)
    }

    var source: Source = .demo
    var scenarios: [String] = []
    var all = false
    var list = false
    var width: CGFloat = 1280
    var height: CGFloat = 860
    var scale = 2
    var language: InterfaceLanguage?
    var out: URL?

    static let usage = """
        usage: RCMacPreview [--demo | --gateway <url> --username <u> --password <p>]
                            (--scenario <name>[,<name>…] | --all | --list)
                            [--width W --height H] [--scale 1|2] [--language en|zh-Hans] --out <dir>
        """

    init(_ arguments: [String]) throws {
        var gateway: URL?
        var username = ""
        var password = ""
        var queue = arguments.dropFirst()[...]
        func value(_ flag: String) throws -> String {
            guard let next = queue.popFirst() else { throw ArgumentError("\(flag) needs a value") }
            return next
        }
        while let flag = queue.popFirst() {
            switch flag {
            case "--demo": source = .demo
            case "--gateway":
                guard let url = URL(string: try value(flag)) else { throw ArgumentError("--gateway is not a URL") }
                gateway = url
            case "--username": username = try value(flag)
            case "--password": password = try value(flag)
            case "--scenario": scenarios += try value(flag).split(separator: ",").map(String.init)
            case "--all": all = true
            case "--list": list = true
            case "--width": width = CGFloat(Double(try value(flag)) ?? 1280)
            case "--height": height = CGFloat(Double(try value(flag)) ?? 860)
            case "--scale": scale = Int(try value(flag)) ?? 2
            case "--language": language = InterfaceLanguage(rawValue: try value(flag))
            case "--out": out = URL(fileURLWithPath: try value(flag), isDirectory: true)
            // `-Key value` is a user default for this run (the argument
            // domain), which AppKit reads by itself.
            case let key where key.hasPrefix("-") && !key.hasPrefix("--"): _ = queue.popFirst()
            default: throw ArgumentError("unknown argument \(flag)")
            }
        }
        if let gateway { source = .gateway(url: gateway, username: username, password: password) }
        guard list || all || !scenarios.isEmpty else { throw ArgumentError("name a --scenario, or --all") }
        guard list || out != nil else { throw ArgumentError("--out is required") }
        guard scale == 1 || scale == 2 || scale == 3 else { throw ArgumentError("--scale is 1, 2 or 3") }
    }

    var gatewayURL: URL? {
        if case .gateway(let url, _, _) = source { return url }
        return nil
    }
}

struct ArgumentError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}
