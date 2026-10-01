package com.junbingao.remotecontrol.win.chat.support

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp

/**
 * SwiftUI's `.kerning(_:)` on one character — room after it, in points — carried through the face
 * pass as an annotation. The face pass (`FaceRuns`) sets every run's letter spacing to its face's
 * tracking and keeps none of a text's annotations, so `restore` puts the annotations and links
 * back on what it made and adds the kerning on top of the tracking each character already has.
 */
object ChatKerning {
    private const val TAG = "rc.kerning"

    fun add(builder: AnnotatedString.Builder, points: Float, start: Int, end: Int) {
        builder.addStringAnnotation(TAG, points.toString(), start, end)
    }

    /**
     * `faced`, the face pass's text for `original`, with the original's annotations and links on it
     * and its kerning applied. `tracking` is the letter spacing, in points, a character the face
     * pass left alone takes from the style.
     */
    fun restore(faced: AnnotatedString, original: AnnotatedString, tracking: Float): AnnotatedString {
        val annotations = original.getStringAnnotations(0, original.length)
        val kerning = annotations.filter { it.tag == TAG }
        if (faced === original && kerning.isEmpty()) return original
        val builder = AnnotatedString.Builder(faced)
        if (faced !== original) {
            for (annotation in annotations) builder.addStringAnnotation(annotation.tag, annotation.item, annotation.start, annotation.end)
            for (link in original.getLinkAnnotations(0, original.length)) {
                when (val item = link.item) {
                    is LinkAnnotation.Url -> builder.addLink(item, link.start, link.end)
                    is LinkAnnotation.Clickable -> builder.addLink(item, link.start, link.end)
                }
            }
        }
        for (kern in kerning) {
            val own = faced.spanStyles.lastOrNull {
                it.start <= kern.start && kern.start < it.end && it.item.letterSpacing.type == TextUnitType.Sp
            }?.item?.letterSpacing?.value ?: tracking
            builder.addStyle(SpanStyle(letterSpacing = (own + kern.item.toFloat()).sp), kern.start, kern.end)
        }
        return builder.toAnnotatedString()
    }
}
