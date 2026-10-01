package com.junbingao.remotecontrol.android.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN § "The Android app": Back closes what is presented, topmost first, before a screen. */
class PresenterTest {
    private fun layer(presenter: Presenter, kind: PresentationKind, dismissible: Boolean = true, onDismiss: () -> Unit): Presentation =
        Presentation(kind).also {
            it.options = PresentationOptions(dismissible = dismissible)
            it.onDismiss = onDismiss
            presenter.show(it)
        }

    @Test
    fun backDismissesTheTopmostPresentationFirst() {
        val presenter = Presenter()
        val dismissed = mutableListOf<String>()
        val sheet = layer(presenter, PresentationKind.sheet) { dismissed += "sheet" }
        val menu = layer(presenter, PresentationKind.menu) { dismissed += "menu" }
        assertTrue(presenter.isPresenting)
        assertTrue(presenter.dismissTop())
        assertEquals(listOf("menu"), dismissed)
        presenter.hide(menu)
        assertTrue(presenter.dismissTop())
        assertEquals(listOf("menu", "sheet"), dismissed)
        presenter.hide(sheet)
        assertFalse("nothing presented: Back leaves the screen", presenter.dismissTop())
        assertFalse(presenter.isPresenting)
    }

    @Test
    fun anAlertThatMustBeAnsweredTakesBackWithoutClosing() {
        val presenter = Presenter()
        var dismissed = false
        layer(presenter, PresentationKind.alert, dismissible = false) { dismissed = true }
        assertTrue("Back is spent on it", presenter.dismissTop())
        assertFalse("and it stays until it is answered", dismissed)
    }
}
