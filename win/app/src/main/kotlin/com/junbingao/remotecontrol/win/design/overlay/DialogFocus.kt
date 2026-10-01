package com.junbingao.remotecontrol.win.design.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/**
 * `useOverlay` in `Modal.tsx`: as a dialog or the drawer opens, focus moves to the first focusable
 * element of its panel — `input, textarea, select, button:not([disabled]), [tabindex]` in
 * document order — and the page gives up the keyboard. A field takes it with its caret after the
 * text, and with the ring a focused field always shows. A button takes it with no ring, since a
 * focus a script moves after a click never shows one, which here is the same as nothing in the
 * panel focused.
 *
 * The head comes first in document order: the drawer's close button, and a modal's when it shows
 * one, so those panels start in no field. Otherwise the first field is found in the panel, top to
 * bottom and then leading to trailing, once it is laid out: every `WebField` in a panel is one of
 * its fields.
 */
@Composable
internal fun DialogFocus(startsInField: Boolean, fields: DialogFields) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        // After the panel's content has been laid out and placed.
        withFrameNanos {}
        withFrameNanos {}
        val first = if (startsInField) fields.first() else null
        if (first != null) first.requestFocus() else focusManager.clearFocus()
    }
}

/** The fields of one dialog's panel, with where each is. */
internal class DialogFields {
    private class Field(val requester: FocusRequester) {
        var bounds: Rect? = null
    }

    private val fields = mutableListOf<Field>()

    fun add(requester: FocusRequester): Any = Field(requester).also { fields += it }

    fun remove(token: Any) {
        fields.remove(token)
    }

    fun move(token: Any, bounds: Rect) {
        (token as Field).bounds = bounds
    }

    /** The panel's highest field, the leftmost of a row. */
    fun first(): FocusRequester? = fields
        .filter { it.bounds != null }
        .minWithOrNull(compareBy<Field>({ it.bounds!!.top }, { it.bounds!!.left }))
        ?.requester
}

internal val LocalDialogFields = staticCompositionLocalOf<DialogFields?> { null }

/** Marks a field as one a dialog's panel may put the focus in as it opens. Outside a dialog it does nothing. */
fun Modifier.dialogField(requester: FocusRequester): Modifier = composed {
    val fields = LocalDialogFields.current
    if (fields == null) {
        Modifier
    } else {
        val token = remember(fields, requester) { fields.add(requester) }
        DisposableEffect(token) { onDispose { fields.remove(token) } }
        Modifier.onGloballyPositioned { fields.move(token, it.boundsInRoot()) }
    }
}
