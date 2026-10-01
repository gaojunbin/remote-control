package com.junbingao.remotecontrol.win.design

import androidx.compose.ui.text.font.FontWeight
import com.junbingao.remotecontrol.win.standin.InterfaceLanguage
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.FontWidth
import org.jetbrains.skia.Typeface
import java.util.concurrent.ConcurrentHashMap

/**
 * The system face as a browser draws the web's font stack (`--font-sans`, `--font-mono`).
 *
 * On Windows the stack resolves to Segoe UI Variable (Segoe UI on Windows 10), Consolas for
 * `ui-monospace`, and Microsoft YaHei UI for Chinese (`docs/DESIGN.md` § "The Windows app").
 *
 * On a Mac — where the renderer runs, so its pictures compare with the Mac renderer's pixel for
 * pixel — it is the Mac app's `SystemFace`. SF is set at the optical size and weight CoreText picks
 * for each size, and carries CoreText's size-specific tracking: SF's `trak` table, which Skia's
 * shaping leaves out, is 0 at 12 px, −0.15 px a glyph at 14 and +0.40 at 30, so without it every
 * line but a 12 px one runs longer or shorter than the Mac's. The middle dot of every meta line
 * ("web · 13m") is SF's own, as it is in Chrome. Chinese follows the interface language, as Chrome's
 * fallback does: under `en` Han is drawn in the cut the system face cascades to, PingFang's UI cut
 * ("中文" measures 27.80 px at 14 px); under `zh-Hans` in PingFang SC at a full em, without the
 * size-specific tracking CoreText would give it (the polish note measures 533.00 px at 13 px).
 */
object SystemFace {
    private data class Key(val size: Float, val weight: Int, val kind: Int)

    private val faces = ConcurrentHashMap<Key, Face>()
    private val host = FaceHost.current

    /** The face a style's Latin text is set in, made once per size and weight and kept. */
    fun face(size: Float, weight: FontWeight, mono: Boolean): Face =
        faces.getOrPut(Key(size, weight.weight, if (mono) 1 else 0)) { host.primary(size, weight.weight, mono) }

    /**
     * The face Chinese falls to in the interface language in use, for the characters the primary
     * face has no glyph for; null where the system's own fallback is the platform's rule.
     */
    fun chinese(size: Float, weight: FontWeight, language: InterfaceLanguage): Face? {
        val kind = if (language == InterfaceLanguage.zhHans) 3 else 2
        val key = Key(size, weight.weight, kind)
        faces[key]?.let { return it }
        val face = host.chinese(size, weight.weight, language) ?: return null
        return faces.getOrPut(key) { face }
    }
}

/** A face at one size and weight, and what it adds after every glyph. */
class Face internal constructor(
    val typeface: Typeface,
    /** CSS px added after every glyph: the platform's size-specific tracking. */
    val tracking: Float,
    alias: String,
) {
    val family = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.platform.Typeface(typeface, alias))

    private val glyphs = ConcurrentHashMap<Int, Boolean>()

    /** Whether this face has its own glyph for a code point. */
    fun draws(codePoint: Int): Boolean = glyphs.getOrPut(codePoint) {
        typeface.getUTF32Glyphs(intArrayOf(codePoint))[0].toInt() != 0
    }

    /** The font's ascent and descent at a size, both positive, in CSS px. */
    fun metrics(size: Float): Pair<Float, Float> {
        val metrics = org.jetbrains.skia.Font(typeface, size).metrics
        return -metrics.ascent to metrics.descent
    }
}

/** Which platform's faces the web's stack resolves to on this machine. */
internal sealed interface FaceHost {
    fun primary(size: Float, weight: Int, mono: Boolean): Face
    fun chinese(size: Float, weight: Int, language: InterfaceLanguage): Face?

    companion object {
        val current: FaceHost by lazy {
            val os = System.getProperty("os.name").orEmpty()
            when {
                os.startsWith("Mac") -> MacFaces
                os.startsWith("Windows") -> WindowsFaces
                else -> OtherFaces
            }
        }
    }
}

private fun fontStyle(weight: Int) = FontStyle(weight, FontWidth.NORMAL, FontSlant.UPRIGHT)

/** A variable face set to the axis values asked for, each clamped to its axis. */
private fun Typeface.at(vararg values: Pair<String, Float>): Typeface {
    val axes = variationAxes ?: return this
    val variations = values.mapNotNull { (tag, value) ->
        axes.firstOrNull { it.tag == tag }?.let { FontVariation(tag, value.coerceIn(it.minValue, it.maxValue)) }
    }
    return if (variations.isEmpty()) this else makeClone(variations.toTypedArray())
}

/** The Mac app's `SystemFace`, drawn by Skia. */
private object MacFaces : FaceHost {
    private val manager = FontMgr.default
    private val sans by lazy { manager.matchFamilyStyle(".AppleSystemUIFont", FontStyle.NORMAL) }
    private val trak by lazy { sans?.let(TrakTable::read) }

    /**
     * CoreText's weights for SF: `NSFont.Weight` puts `.medium` at 510 and `.semibold` at 590 on
     * the weight axis, where the web asks for 500 and 600.
     */
    private fun sfWeight(css: Int): Float = when {
        css <= 100 -> 30.925f
        css <= 200 -> 110.725f
        css <= 300 -> 274.315f
        css <= 400 -> 400f
        css <= 500 -> 510f
        css <= 600 -> 590f
        css <= 700 -> 700f
        css <= 800 -> 860f
        else -> 1000f
    }

    override fun primary(size: Float, weight: Int, mono: Boolean): Face {
        if (mono) {
            // SF Mono's named instances, which CoreText picks for each weight; it has no tracking.
            val face = manager.matchFamilyStyle(".AppleSystemUIFontMonospaced", fontStyle(weight))
                ?: return OtherFaces.primary(size, weight, true)
            return Face(face, 0f, "rc-sf-mono-$weight")
        }
        val base = sans ?: return OtherFaces.primary(size, weight, false)
        val wght = sfWeight(weight)
        // CoreText sets the optical size to the point size, from the axis' floor of 17 up.
        val face = base.at("opsz" to size, "wght" to wght)
        return Face(face, trak?.tracking(size, face.unitsPerEm)?.times(size) ?: 0f, "rc-sf-$size-$wght")
    }

    override fun chinese(size: Float, weight: Int, language: InterfaceLanguage): Face? {
        if (language == InterfaceLanguage.zhHans) {
            // PingFang SC's static cuts at the nearest weight, at their nominal advances.
            val face = manager.matchFamilyStyle("PingFang SC", fontStyle(weight)) ?: return null
            return Face(face, 0f, "rc-pingfang-sc-$weight")
        }
        val cascade = manager.matchFamilyStyleCharacter(".AppleSystemUIFont", fontStyle(weight), arrayOf("en"), '中'.code)
            ?: return null
        val face = cascade.at("wght" to weight.coerceAtMost(800).toFloat())
        return Face(face, 0f, "rc-pingfang-ui-$weight")
    }
}

/** `--font-sans` and `--font-mono` as Chrome resolves them on Windows. */
private object WindowsFaces : FaceHost {
    private val manager = FontMgr.default

    override fun primary(size: Float, weight: Int, mono: Boolean): Face {
        if (mono) {
            val face = manager.matchFamilyStyle("Consolas", fontStyle(weight)) ?: return OtherFaces.primary(size, weight, true)
            return Face(face, 0f, "rc-consolas-$weight")
        }
        // Windows 11's variable face at the size's optical size and the weight asked for; Windows
        // 10 has only the static Segoe UI.
        val variable = manager.matchFamilyStyle("Segoe UI Variable Text", fontStyle(weight))
        if (variable != null) {
            val face = variable.at("opsz" to size, "wght" to weight.toFloat())
            return Face(face, 0f, "rc-segoe-variable-$size-$weight")
        }
        val face = manager.matchFamilyStyle("Segoe UI", fontStyle(weight)) ?: return OtherFaces.primary(size, weight, false)
        return Face(face, 0f, "rc-segoe-$weight")
    }

    override fun chinese(size: Float, weight: Int, language: InterfaceLanguage): Face? {
        val face = manager.matchFamilyStyle("Microsoft YaHei UI", fontStyle(weight)) ?: return null
        return Face(face, 0f, "rc-yahei-ui-$weight")
    }
}

/** Anywhere else — a Linux runner — the system's own sans and mono, and its own fallback. */
private object OtherFaces : FaceHost {
    private val manager = FontMgr.default

    private fun first(families: List<String>, weight: Int): Typeface =
        families.firstNotNullOfOrNull { manager.matchFamilyStyle(it, fontStyle(weight)) }
            ?: manager.legacyMakeTypeface("", fontStyle(weight))
            ?: error("no typeface on this machine")

    override fun primary(size: Float, weight: Int, mono: Boolean): Face {
        val families = if (mono) listOf("Noto Sans Mono", "DejaVu Sans Mono", "Menlo", "Consolas")
        else listOf("Noto Sans", "DejaVu Sans", "Helvetica Neue", "Arial")
        return Face(first(families, weight), 0f, "rc-other-${if (mono) "mono" else "sans"}-$weight")
    }

    override fun chinese(size: Float, weight: Int, language: InterfaceLanguage): Face? = null
}
