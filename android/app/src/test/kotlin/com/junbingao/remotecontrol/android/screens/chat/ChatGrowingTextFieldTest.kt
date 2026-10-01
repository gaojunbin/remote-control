package com.junbingao.remotecontrol.android.screens.chat

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junbingao.remotecontrol.android.design.FieldText
import com.junbingao.remotecontrol.android.design.GrowingTextField
import com.junbingao.remotecontrol.android.harness.IPhone
import com.junbingao.remotecontrol.android.harness.IPhoneFrame
import com.junbingao.remotecontrol.android.harness.Variant
import com.junbingao.remotecontrol.android.strings.L10n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The message field's caret: after text written from outside it stands after the last word, as a
 * text view's does, and the field's own edits — a keyboard's composition among them — keep theirs
 * through the round trip to the owner of the text.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = IPhone.QUALIFIERS)
class ChatGrowingTextFieldTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var text by mutableStateOf("")

    private fun showField() = compose.setContent {
        IPhoneFrame(Variant(L10n.english, dark = false)) {
            GrowingTextField("Message", text, { text = it }, identifier = "field")
        }
    }

    private fun selection(): TextRange? =
        compose.onNodeWithTag("field", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)

    @Test
    fun aWriteFromOutsidePutsTheCaretAfterTheLastWord() {
        showField()
        compose.onNodeWithTag("field", useUnmergedTree = true).performTextInput("a note of my own")
        // A queued message is taken back into the field in place of the draft.
        text = "Add a regression test for the refresh race."
        compose.waitForIdle()
        assertEquals("the caret stands after the last word", TextRange(text.length), selection())
        compose.onNodeWithTag("field", useUnmergedTree = true).performTextInput(" And one for logout.")
        assertEquals("so typing carries on where the words end", "Add a regression test for the refresh race. And one for logout.", text)
    }

    @Test
    fun aCompositionSurvivesTheRecompositionItsTextCauses() {
        showField()
        compose.onNodeWithTag("field", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val connection = compose.runOnIdle { inputConnection() }
        assertNotNull("the focused field takes text from a keyboard", connection)
        // Pinyin being composed: each step replaces what is still being composed, and the owner of
        // the text is told of each one, which recomposes the field in between.
        compose.runOnIdle { connection!!.setComposingText("ni", 1) }
        compose.waitForIdle()
        assertEquals("ni", text)
        compose.runOnIdle { connection!!.setComposingText("nihao", 1) }
        compose.waitForIdle()
        assertEquals("the second step replaced the first rather than adding to it", "nihao", text)
        compose.runOnIdle { connection!!.commitText("你好", 1) }
        compose.waitForIdle()
        assertEquals("and the committed words replaced the composition", "你好", text)
    }

    @Test
    fun theFieldsOwnCopyIsResetOnlyByADifferentText() {
        val field = FieldText("你")
        val shown = field.shown("你")
        assertEquals("a field opens with the caret after its text", TextRange(1), shown.selection)
        val composing = TextFieldValue("你hao", selection = TextRange(4), composition = TextRange(1, 4))
        assertTrue("composing changes the text the owner holds", field.take(composing, shown))
        assertEquals("the same text coming back keeps the composition and the caret", composing, field.shown("你hao"))
        val moved = composing.copy(selection = TextRange(2))
        assertFalse("a caret moved is not a change of the text", field.take(moved, field.shown("你hao")))
        assertEquals(moved, field.shown("你hao"))
        val written = field.shown("a queued message")
        assertEquals("a different text is a write from outside", TextRange("a queued message".length), written.selection)
        assertEquals("with nothing left of the composition", null, written.composition)
    }

    /** The view the composition's text input runs through: the one that says it is a text editor. */
    private fun inputConnection(): InputConnection? {
        fun find(view: View): View? {
            if (view.onCheckIsTextEditor()) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return find(compose.activity.window.decorView)?.onCreateInputConnection(EditorInfo())
    }
}
