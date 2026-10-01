package com.junbingao.remotecontrol.android.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ios/VerificationUI` § "How far the message field grows". */
class ComposerLayoutTest {
    @Test
    fun anEmptyComposerIsOneLineAndStopsGrowingAtEight() {
        assertEquals(1, ComposerLayout.growth.first)
        assertEquals(8, ComposerLayout.growth.last)
    }

    @Test
    fun theFieldGrowsWithTheDraftAndStopsAtTheCap() {
        assertEquals("an empty draft is a single row", 1, ComposerLayout.lines(""))
        assertEquals("a short draft stays compact", 1, ComposerLayout.lines("one line of dictated text"))
        assertEquals("the field grows with the draft", 3, ComposerLayout.lines("one\ntwo\nthree"))
        assertEquals("and stops at the cap", 8, ComposerLayout.lines("line\n".repeat(20)))
    }

    @Test
    fun pastTheCapTheTextScrollsInsideTheField() {
        assertFalse("a short draft does not scroll", ComposerLayout.scrolls("one\ntwo"))
        assertTrue(ComposerLayout.scrolls("line\n".repeat(20)))
        assertFalse("eight lines still fit", ComposerLayout.scrolls("1\n2\n3\n4\n5\n6\n7\n8"))
        assertTrue("a ninth line scrolls", ComposerLayout.scrolls("1\n2\n3\n4\n5\n6\n7\n8\n9"))
    }

    @Test
    fun everyLineBreakSwiftCountsCountsOnce() {
        assertEquals("a CRLF pair is one break", 2, ComposerLayout.lines("one\r\ntwo"))
        assertEquals(2, ComposerLayout.lines("one\rtwo"))
        assertEquals(3, ComposerLayout.lines("one two three"))
        assertEquals(2, ComposerLayout.lines("one\u0085two"))
    }
}
