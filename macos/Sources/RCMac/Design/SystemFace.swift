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
/// English carries that dot itself and has the same metrics otherwise, so it is
/// the face every style uses.
///
/// Chinese text follows the page's `lang`, as Chrome's fallback does. Under
/// `en` Chrome draws Han in the cut the system face cascades to, PingFang's UI
/// cut ("中文" measures 27.80 px at 14 px), and so does CoreText. Under `zh-Hans`
/// it draws them in PingFang SC at a full em: the polish note measures 533.00 px
/// at 13 px, 41 characters of 13, where the UI cut gives 528.86. So in Chinese
/// the face cascades to PingFang SC first, at optical size 0 — CoreText gives a
/// system face's cascade PingFang's size-specific tracking, 2 % of an em at 13
/// points, and Chrome gives it none.
public enum SystemFace {
    private struct Key: Hashable {
        let size: CGFloat
        let weight: CGFloat
        let chinese: Bool
    }

    private static let faces = Mutex<[Key: NSFont]>([:])

    /// The system face at a size and weight for the interface language in use,
    /// made once and kept.
    public static func font(size: CGFloat, weight: NSFont.Weight = .regular) -> NSFont {
        let key = Key(size: size, weight: weight.rawValue, chinese: InterfaceLanguageSource.shared.current == .zhHans)
        return faces.withLock { faces in
            if let face = faces[key] { return face }
            let face = make(size: size, weight: weight, chinese: key.chinese)
            faces[key] = face
            return face
        }
    }

    private static func make(size: CGFloat, weight: NSFont.Weight, chinese: Bool) -> NSFont {
        guard let english = CTFontCreateUIFontForLanguage(.system, size, "en" as CFString) else {
            return .systemFont(ofSize: size, weight: weight)
        }
        var attributes: [CFString: Any] = [kCTFontTraitsAttribute: [kCTFontWeightTrait: weight.rawValue]]
        if chinese { attributes[kCTFontCascadeListAttribute] = [pingFang(weight: weight)] }
        let descriptor = CTFontDescriptorCreateCopyWithAttributes(CTFontCopyFontDescriptor(english),
                                                                  attributes as CFDictionary)
        return CTFontCreateWithFontDescriptor(descriptor, size, nil) as NSFont
    }

    /// PingFang SC at the nearest weight it has, at its nominal advances.
    private static func pingFang(weight: NSFont.Weight) -> CTFontDescriptor {
        CTFontDescriptorCreateWithAttributes([
            kCTFontFamilyNameAttribute: "PingFang SC",
            kCTFontTraitsAttribute: [kCTFontWeightTrait: weight.rawValue],
            kCTFontOpticalSizeAttribute: 0
        ] as CFDictionary)
    }
}

extension Font {
    /// The web's `-apple-system` at a size and weight — `SystemFace`'s face, so
    /// its punctuation and its Chinese measure what the browser's do.
    public static func web(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        Font(SystemFace.font(size: size, weight: weight.nsWeight) as CTFont)
    }
}
