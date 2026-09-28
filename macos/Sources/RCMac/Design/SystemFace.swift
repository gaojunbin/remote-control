import AppKit
import CoreText
import SwiftUI
import Synchronization

/// The system face as a browser draws the web's `-apple-system`.
///
/// macOS hands out the system font by the person's preferred languages. With
/// Chinese first — the owner's Mac — the middle dot that joins nearly every
/// meta line ("web · 13m", "Up next · 3") is left to the CJK symbols font,
/// 2.4 points wider at 12 points, so every line with a dot runs longer than the
/// web's and everything after the dot moves. Chrome draws the dot from the
/// system face whatever the page's `lang`: "Up next · 3" measures 62.70 px at
/// 12 px medium under `en` and `zh-Hans` alike. The system UI font made for
/// English carries that dot itself, has the same metrics otherwise and still
/// falls back to PingFang for Chinese text, so it is the face every style uses.
public enum SystemFace {
    private struct Key: Hashable {
        let size: CGFloat
        let weight: CGFloat
    }

    private static let faces = Mutex<[Key: NSFont]>([:])

    /// The system face at a size and weight, made once and kept.
    public static func font(size: CGFloat, weight: NSFont.Weight = .regular) -> NSFont {
        let key = Key(size: size, weight: weight.rawValue)
        return faces.withLock { faces in
            if let face = faces[key] { return face }
            let face = make(size: size, weight: weight)
            faces[key] = face
            return face
        }
    }

    private static func make(size: CGFloat, weight: NSFont.Weight) -> NSFont {
        guard let english = CTFontCreateUIFontForLanguage(.system, size, "en" as CFString) else {
            return .systemFont(ofSize: size, weight: weight)
        }
        let traits = [kCTFontTraitsAttribute: [kCTFontWeightTrait: weight.rawValue]] as CFDictionary
        let descriptor = CTFontDescriptorCreateCopyWithAttributes(CTFontCopyFontDescriptor(english), traits)
        return CTFontCreateWithFontDescriptor(descriptor, size, nil) as NSFont
    }
}

extension Font {
    /// The web's `-apple-system` at a size and weight — `SystemFace`'s face, so
    /// its punctuation measures what the browser's does.
    public static func web(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        Font(SystemFace.font(size: size, weight: weight.nsWeight) as CTFont)
    }
}
