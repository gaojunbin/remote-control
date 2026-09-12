import Foundation
import RCCore

/// The interface language, checked against the sources rather than by eye.
///
/// Two failures are worth catching before a build reaches a phone: a key the
/// catalogue has no Chinese for, which would silently show English inside a
/// Chinese screen, and a word written straight into a view with no catalogue
/// entry at all, which no language setting could ever move.
enum LocalizationChecks {
    static func run() -> CheckResult {
        let checks = CheckRunner(group: "language")
        guard let catalogue = StringCatalogue.load() else {
            checks.expect(false, "App/Localizable.xcstrings loads")
            return checks.result()
        }

        checks.expect(catalogue.keys.count > 200,
                      "the catalogue holds the app's words (\(catalogue.keys.count))")

        let untranslated = catalogue.keys.filter { catalogue.translation(of: $0) == nil }.sorted()
        checks.equal(untranslated, [], "every key carries a zh-Hans translation")

        let empty = catalogue.keys.filter { (catalogue.translation(of: $0) ?? "x").isEmpty }.sorted()
        checks.equal(empty, [], "and none of them is blank")

        // A translation that reorders `%@` and `%lld` without saying so
        // positionally hands an integer to `%@` and crashes the app the first
        // time the line is drawn. This is the check that would have caught it.
        var mismatched: [String] = []
        for key in catalogue.keys.sorted() {
            guard let translation = catalogue.translation(of: key),
                  !FormatSpecifiers.agree(key: key, translation: translation) else { continue }
            mismatched.append(key)
        }
        for key in mismatched.prefix(6) {
            checks.expect(false, "\"\(key)\" is translated with different placeholders")
        }
        checks.equal(mismatched.count, 0, "every translation takes the same values, in a stated order")

        // The two names are shown in their own script, so neither is translated.
        for language in InterfaceLanguage.allCases {
            checks.expect(!language.title.isEmpty, "\(language.rawValue) names itself")
        }
        checks.equal(InterfaceLanguage.allCases.map(\.rawValue), ["en", "zh-Hans"],
                     "the app offers exactly two languages")

        let used = SourceStrings.collect()
        checks.expect(used.count > 200, "the sources ask for the app's words (\(used.count))")
        let uncatalogued = used.keys.filter { !catalogue.keys.contains($0) }.sorted()
        for key in uncatalogued.prefix(12) {
            checks.expect(false, "\(used[key] ?? "?") writes \"\(key)\", which the catalogue does not hold")
        }
        if uncatalogued.count > 12 {
            checks.expect(false, "and \(uncatalogued.count - 12) more words with no catalogue entry")
        }
        checks.expect(uncatalogued.isEmpty, "every word a view writes is in the catalogue")

        return checks.result()
    }
}

/// The `%…` placeholders in a format key, and whether a translation still takes
/// the same values. A translation may put them in another order, but only by
/// numbering them (`%2$@`), which is what `String(format:)` needs to read them
/// back in the right order.
enum FormatSpecifiers {
    /// One placeholder: its position in the argument list where it says so, and
    /// the conversion it performs.
    struct Placeholder: Equatable {
        var index: Int?
        var conversion: Character
    }

    static func agree(key: String, translation: String) -> Bool {
        let source = parse(key)
        let target = parse(translation)
        guard source.count == target.count else { return false }
        guard target.contains(where: { $0.index != nil }) else {
            return source.map(\.conversion) == target.map(\.conversion)
        }
        // Once one is numbered they all must be, or the unnumbered ones are
        // read from wherever the last numbered one left off.
        guard target.allSatisfy({ $0.index != nil }) else { return false }
        return target.allSatisfy { placeholder in
            guard let index = placeholder.index, (1...source.count).contains(index) else { return false }
            return source[index - 1].conversion == placeholder.conversion
        }
    }

    static func parse(_ text: String) -> [Placeholder] {
        var placeholders: [Placeholder] = []
        var characters = Array(text)[...]
        while let start = characters.firstIndex(of: "%") {
            characters = characters[characters.index(after: start)...]
            guard let first = characters.first else { break }
            if first == "%" {
                characters = characters.dropFirst()
                continue
            }
            var digits = ""
            var rest = characters
            while let digit = rest.first, digit.isNumber {
                digits.append(digit)
                rest = rest.dropFirst()
            }
            var index: Int?
            if rest.first == "$", !digits.isEmpty {
                index = Int(digits)
                rest = rest.dropFirst()
            } else {
                rest = characters
            }
            // Length modifiers and width carry no value of their own.
            while let character = rest.first,
                  character.isNumber || "lhqzjt.+- #'".contains(character) {
                rest = rest.dropFirst()
            }
            guard let conversion = rest.first else { break }
            placeholders.append(Placeholder(index: index, conversion: conversion))
            characters = rest.dropFirst()
        }
        return placeholders
    }
}

/// `App/Localizable.xcstrings`, read as the app reads it.
struct StringCatalogue {
    private let strings: [String: JSONValue]

    var keys: [String] { Array(strings.keys) }

    static func load() -> StringCatalogue? {
        let url = SourceStrings.appRoot.appending(path: "App/Localizable.xcstrings")
        guard let data = try? Data(contentsOf: url),
              let json = try? JSONDecoder().decode(JSONValue.self, from: data),
              let strings = json["strings"]?.objectValue else { return nil }
        return StringCatalogue(strings: strings)
    }

    func translation(of key: String) -> String? {
        strings[key]?["localizations"]?["zh-Hans"]?["stringUnit"]?["value"]?.stringValue
    }
}

/// Every word the app writes into a view, and the file it is written in.
///
/// Two shapes reach the reader: a literal in a `LocalizedStringKey` position,
/// which SwiftUI resolves through the environment's locale, and a `L10n.string`
/// key, which a store or an error resolves through the chosen language's table.
/// Both have to be in the catalogue; neither is checked by the compiler.
enum SourceStrings {
    static let appRoot = URL(filePath: #filePath)
        .deletingLastPathComponent()   // ios/Verification
        .deletingLastPathComponent()   // ios

    private static let constructors = [
        "Text", "Button", "Label", "navigationTitle", "FieldLabel", "SettingsRow",
        "Toggle", "Picker", "TextField", "SecureField", "GrowingTextField",
        "confirmationDialog", "alert", "accessibilityLabel", "accessibilityHint"
    ]

    /// Key to the file that writes it.
    static func collect() -> [String: String] {
        var found: [String: String] = [:]
        let sources = appRoot.appending(path: "Sources")
        guard let walker = FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil) else {
            return found
        }
        for case let url as URL in walker where url.pathExtension == "swift" {
            guard let text = try? String(contentsOf: url, encoding: .utf8) else { continue }
            let name = url.lastPathComponent
            for key in keys(in: strippingComments(text)) where found[key] == nil {
                found[key] = name
            }
        }
        return found
    }

    private static func strippingComments(_ text: String) -> String {
        text.split(separator: "\n", omittingEmptySubsequences: false)
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
            .joined(separator: "\n")
    }

    private static func keys(in source: String) -> [String] {
        let scalars = Array(source)
        var keys: [String] = []
        for constructor in constructors {
            // An interpolated literal is a format key the compiler builds; it is
            // not a plain key and cannot be matched by reading the source.
            keys += leadingLiterals(after: "\(constructor)(", in: scalars)
                .filter { !$0.contains("\\(") && !$0.isEmpty }
        }
        // Every literal inside the call, so a ternary between two keys counts as
        // both rather than as neither.
        keys += allLiterals(after: "L10n.string(", in: scalars).filter { !$0.isEmpty }
        return keys
    }

    /// The literal that is the first argument of each call, where it is one.
    private static func leadingLiterals(after opening: String, in scalars: [Character]) -> [String] {
        var results: [String] = []
        for start in occurrences(of: opening, in: scalars) {
            var cursor = start
            while cursor < scalars.count, scalars[cursor].isWhitespace { cursor += 1 }
            guard cursor < scalars.count, scalars[cursor] == "\"",
                  let literal = readLiteral(scalars, &cursor) else { continue }
            results.append(literal)
        }
        return results
    }

    /// Every literal in the argument list of each call, up to its matching
    /// close parenthesis.
    private static func allLiterals(after opening: String, in scalars: [Character]) -> [String] {
        var results: [String] = []
        for start in occurrences(of: opening, in: scalars) {
            var cursor = start
            var depth = 1
            while cursor < scalars.count, depth > 0 {
                switch scalars[cursor] {
                case "\"":
                    if let literal = readLiteral(scalars, &cursor) { results.append(literal) }
                    continue
                case "(": depth += 1
                case ")": depth -= 1
                default: break
                }
                cursor += 1
            }
        }
        return results
    }

    /// Where each call's argument list begins, skipping a match that is the tail
    /// of a longer name.
    private static func occurrences(of opening: String, in scalars: [Character]) -> [Int] {
        let pattern = Array(opening)
        var starts: [Int] = []
        var index = 0
        while index + pattern.count <= scalars.count {
            if Array(scalars[index..<(index + pattern.count)]) == pattern,
               index == 0 || !isIdentifier(scalars[index - 1]) {
                starts.append(index + pattern.count)
                index += pattern.count
            } else {
                index += 1
            }
        }
        return starts
    }

    /// Reads one Swift string literal, leaving the cursor past its closing
    /// quote. Returns nil for a multi-line or malformed one.
    private static func readLiteral(_ scalars: [Character], _ cursor: inout Int) -> String? {
        cursor += 1
        var literal = ""
        while cursor < scalars.count {
            let character = scalars[cursor]
            if character == "\\" {
                literal.append(character)
                cursor += 1
                if cursor < scalars.count { literal.append(scalars[cursor]) }
                cursor += 1
                continue
            }
            if character == "\"" {
                cursor += 1
                return literal
            }
            if character == "\n" { return nil }
            literal.append(character)
            cursor += 1
        }
        return nil
    }

    private static func isIdentifier(_ character: Character) -> Bool {
        character.isLetter || character.isNumber || character == "_"
    }
}
