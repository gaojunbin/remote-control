package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.junbingao.remotecontrol.win.platform.Host
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * The composer's text view: the web's `<textarea class="composer-input">` — its words with their
 * selection and an input method's composition, its own undo, and every key the composer may use,
 * asked before the text field acts on any. Whether an input method holds a composition is the one
 * thing asked first: Compose says so directly, in the field's value, as AppKit's `hasMarkedText()`
 * says it to the Mac.
 */
class ComposerTextView(text: String) {
    var value: TextFieldValue by mutableStateOf(TextFieldValue(text, TextRange(text.length)))
        private set
    val undo = ComposerUndo()

    /** A key the composer may use; true when it did, and the text field then does nothing with it. */
    var onKey: (key: ComposerKey, shift: Boolean, hasMarkedText: Boolean) -> Boolean = { _, _, _ -> false }

    /** Every edit of the person's, written straight to the draft. */
    var onTyped: (String) -> Unit = {}

    /** Files pasted, where the composer takes files at all. */
    var onFiles: (List<AttachmentSource>) -> Unit = {}
    var acceptsFiles: () -> Boolean = { false }

    /** Tab leaves a `<textarea>` for the next control rather than typing a tab; Shift+Tab for the one before. */
    var moveFocus: (forward: Boolean) -> Unit = {}

    /** What the clipboard holds, read when a paste asks for it. */
    var clipboard: () -> Transferable? = { systemClipboard() }

    val hasMarkedText: Boolean get() = value.composition != null

    /**
     * What the field shows for the draft's words: its own value, or — for a write that is not a
     * keystroke, a dictation, a polished answer, a command row, an edit coming in — the new words
     * with the caret after them. An input method's composition is never written over.
     */
    fun shown(text: String): TextFieldValue = if (value.text == text || hasMarkedText) value else TextFieldValue(text, TextRange(text.length))

    /** The field takes what it shows; a write from outside forgets the field's history. */
    fun adopt(shown: TextFieldValue) {
        if (shown == value) return
        value = shown
        undo.clear()
    }

    /** An edit the text field made: a keystroke, a composition, a paste of words, a cut. */
    fun edit(next: TextFieldValue) {
        val previous = value
        value = next
        if (next.text == previous.text) return
        undo.record(previous, next)
        onTyped(next.text)
    }

    /** The caret after the words, as the web's `focus()` leaves it. */
    fun caretToEnd() {
        value = value.copy(selection = TextRange(value.text.length))
    }

    /**
     * One key press, answered with whether it was handled here. As the web's `keydown` handler,
     * only Shift changes what a composer key does: Enter with any other modifier is Enter.
     */
    fun keyDown(event: KeyEvent, editable: Boolean): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val shortcut = if (Host.isMac) event.isMetaPressed else event.isCtrlPressed
        when {
            shortcut && event.key == Key.Z -> {
                if (editable) if (event.isShiftPressed) redo() else undo()
                return true
            }
            shortcut && event.key == Key.Y && !Host.isMac -> {
                if (editable) redo()
                return true
            }
            (shortcut && event.key == Key.V) || (event.isShiftPressed && event.key == Key.Insert) ->
                if (editable && pasteFiles()) return true
        }
        val key = ComposerKey(event.key) ?: return false
        val marked = hasMarkedText
        if (onKey(key, event.isShiftPressed, marked)) return true
        // The press is the input method's. The platform hands the input method its keys before the
        // window sees them — Windows marks them VK_PROCESSKEY and AWT drops them, AppKit keeps them
        // in the input context — so none should arrive here; one that does is swallowed, because
        // the text field's own handling would type the line break the input method's Enter is not.
        if (marked) return true
        return when (key) {
            ComposerKey.tab -> {
                moveFocus(!event.isShiftPressed)
                true
            }
            // The composer passes Enter on only with Shift, which breaks the line.
            ComposerKey.enter -> {
                if (editable) insert("\n")
                true
            }
            // Escape and the arrows are the field's, or whatever is listening above it.
            else -> false
        }
    }

    private fun undo() {
        val back = undo.undo(value) ?: return
        value = back
        onTyped(back.text)
    }

    private fun redo() {
        val again = undo.redo(value) ?: return
        value = again
        onTyped(again.text)
    }

    /** The selection replaced by `text`, as typing it would. */
    private fun insert(text: String) {
        val start = value.selection.min
        val end = value.selection.max
        edit(TextFieldValue(value.text.substring(0, start) + text + value.text.substring(end), TextRange(start + text.length)))
    }

    /**
     * A paste that holds files attaches them; one that cannot be attached here pastes as words, the
     * way a browser's `textarea` does.
     */
    private fun pasteFiles(): Boolean {
        if (!acceptsFiles()) return false
        val sources = clipboard()?.let(::files) ?: return false
        onFiles(sources)
        return true
    }

    companion object {
        /**
         * What a clipboard or a drop holds as files: the files themselves, or a picture with no file
         * behind it, which a browser hands a page as `image.png`.
         */
        fun files(on: Transferable): List<AttachmentSource>? {
            if (on.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                val files = runCatching { on.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> }.getOrNull()
                    ?.filterIsInstance<File>()
                if (!files.isNullOrEmpty()) return files.map { AttachmentSource.File(it) }
            }
            if (on.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                val image = runCatching { on.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull()
                val png = image?.let(::png)
                if (png != null) return listOf(AttachmentSource.Data(name = "image.png", mime = "image/png", data = png))
            }
            return null
        }

        /** Whether a drag carries files at all, which is all a drag can be asked before the drop. */
        fun holdsFiles(transferable: Transferable): Boolean =
            transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || transferable.isDataFlavorSupported(DataFlavor.imageFlavor)

        private fun png(image: Image): ByteArray? {
            val width = image.getWidth(null)
            val height = image.getHeight(null)
            if (width <= 0 || height <= 0) return null
            val pixels = image as? BufferedImage ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { copy ->
                val graphics = copy.createGraphics()
                graphics.drawImage(image, 0, 0, null)
                graphics.dispose()
            }
            val out = ByteArrayOutputStream()
            if (!ImageIO.write(pixels, "png", out)) return null
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }

        private fun systemClipboard(): Transferable? = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }.getOrNull()
    }
}
