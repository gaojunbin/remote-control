package com.junbingao.remotecontrol.win.chat.composer

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import com.junbingao.remotecontrol.core.protocol.stringValue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The field itself: a key the composer uses never reaches the text, and the key that confirms an
 * input method's composition is always reported as the input method's — the field's value holds a
 * composition, as the Mac's text view `hasMarkedText()` — which is the rule `useImeGuard.ts` exists
 * for on the web.
 */
class ComposerTextViewTests {
    private class KeyLog {
        val keys = mutableListOf<Triple<ComposerKey, Boolean, Boolean>>()
    }

    private fun field(text: String = ""): Pair<ComposerTextView, KeyLog> {
        val view = ComposerTextView(text)
        val log = KeyLog()
        view.onKey = { key, shift, marked ->
            log.keys += Triple(key, shift, marked)
            ComposerKeys.action(key, shift = shift, hasMarkedText = marked, panel = null) != ComposerKeyAction.Pass
        }
        return view to log
    }

    /** A key press as the window hands it on, built the way Compose's own tests build one. */
    @OptIn(InternalComposeUiApi::class)
    private fun press(key: Key, shift: Boolean = false, ctrl: Boolean = false, meta: Boolean = false) =
        KeyEvent(key = key, type = KeyEventType.KeyDown, isCtrlPressed = ctrl, isMetaPressed = meta, isShiftPressed = shift)

    /** An input method composing `letters`, as the platform's input service writes it into the field. */
    private fun ComposerTextView.compose(letters: String) {
        edit(TextFieldValue(letters, TextRange(letters.length), composition = TextRange(0, letters.length)))
    }

    @Test
    fun enterIsTheComposersAndTypesNothing() {
        val (view, log) = field("send this")
        assertTrue(view.keyDown(press(Key.Enter), editable = true))
        assertEquals(1, log.keys.size)
        assertEquals(ComposerKey.enter, log.keys.first().first)
        assertFalse(log.keys.first().third)
        assertEquals("send this", view.value.text)
    }

    @Test
    fun theEnterThatConfirmsACompositionIsReportedAsTheInputMethods() {
        val (view, log) = field()
        view.compose("zhong")
        assertTrue(view.hasMarkedText)
        view.keyDown(press(Key.Enter), editable = true)
        assertTrue(log.keys.first().third)
        // The input method's Enter types nothing either: the composition is left as it was.
        assertEquals("zhong", view.value.text)
        assertEquals(TextRange(0, 5), view.value.composition)
    }

    @Test
    fun shiftIsReadOffTheEvent() {
        val (view, log) = field()
        view.keyDown(press(Key.Enter, shift = true), editable = true)
        assertTrue(log.keys.first().second)
    }

    @Test
    fun shiftEnterBreaksTheLineWhereTheCaretIs() {
        val (view, _) = field("first second")
        view.edit(TextFieldValue("first second", TextRange(5)))
        assertTrue(view.keyDown(press(Key.Enter, shift = true), editable = true))
        assertEquals("first\n second", view.value.text)
        assertEquals(TextRange(6), view.value.selection)
    }

    @Test
    fun aPictureOnTheClipboardIsAFileNamedAsABrowserNamesIt() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        val sources = ComposerTextView.files(ImageTransfer(image))
        val data = assertNotNull(sources?.single() as? AttachmentSource.Data)
        assertEquals("image.png", data.name)
        assertEquals("image/png", data.mime)
        assertTrue(data.data.isNotEmpty())
    }

    @Test
    fun wordsOnTheClipboardAreNotFiles() {
        assertNull(ComposerTextView.files(StringSelection("just words")))
    }

    @Test
    fun aPasteOfFilesAttachesThemAndAPasteOfWordsIsTheFields() {
        val (view, _) = field()
        val attached = mutableListOf<AttachmentSource>()
        view.acceptsFiles = { true }
        view.onFiles = { attached += it }
        view.clipboard = { ImageTransfer(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)) }
        assertTrue(view.keyDown(press(Key.V, ctrl = !isMac, meta = isMac), editable = true))
        assertEquals(1, attached.size)
        view.clipboard = { StringSelection("words") }
        assertFalse(view.keyDown(press(Key.V, ctrl = !isMac, meta = isMac), editable = true))
        assertEquals(1, attached.size)
    }

    @Test
    fun undoGoesBackToWhatWasTypedAndNeverPastAWriteFromOutside() {
        val (view, _) = field()
        view.adopt(view.shown("dictated words"))
        view.edit(TextFieldValue("dictated words!", TextRange(15)))
        assertTrue(view.undo.canUndo)
        view.keyDown(press(Key.Z, ctrl = !isMac, meta = isMac), editable = true)
        assertEquals("dictated words", view.value.text)
        assertFalse(view.undo.canUndo)
    }

    /**
     * The rule through the field the composer draws: while the field's value holds a composition,
     * Enter reaches neither the composer nor the text field, and the Enter after the input method
     * has committed its words is Send.
     */
    @Test
    fun aCompositionInTheFieldKeepsItsEnterAndTheNextOneSends() = runTest {
        val harness = ComposerHarness(this)
        val view = ComposerTextView("")
        val scene = ImageComposeScene(600, 200, Density(1f)) {
            ComposerField(harness.composer, text = harness.composer.text, disabled = false, readOnly = false, view = view) {}
        }
        try {
            harness.composer.requestFocus()
            frames(scene)
            view.compose("zhong")
            frames(scene)
            assertEquals("zhong", harness.chat.draft)
            assertTrue(scene.sendKeyEvent(press(Key.Enter)))
            frames(scene)
            assertTrue(harness.channel.sent("session.send").isEmpty())
            assertEquals("zhong", view.value.text)
            // The input method commits what it composed: the composition ends, and the next Enter is Send.
            view.edit(TextFieldValue("中", TextRange(1)))
            frames(scene)
            assertTrue(scene.sendKeyEvent(press(Key.Enter)))
            assertTrue(eventually { harness.channel.sent("session.send").isNotEmpty() })
            assertEquals("中", harness.channel.sent("session.send").first()["text"]?.stringValue)
        } finally {
            scene.close()
        }
    }

    /** A few frames, with the composer's work run between them. */
    private fun TestScope.frames(scene: ImageComposeScene) {
        repeat(3) {
            scene.render()
            runCurrent()
        }
    }

    /** The platform's shortcut key: ⌘ where the tests run on a Mac, Ctrl on Windows. */
    private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

    /** A clipboard holding a picture and nothing else. */
    private class ImageTransfer(private val image: java.awt.Image) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}
