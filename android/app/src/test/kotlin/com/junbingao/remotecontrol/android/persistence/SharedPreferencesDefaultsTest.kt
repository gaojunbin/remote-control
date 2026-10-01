package com.junbingao.remotecontrol.android.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The core's defaults on `SharedPreferences`, read as Foundation reads its own. */
@RunWith(AndroidJUnit4::class)
class SharedPreferencesDefaultsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val defaults = SharedPreferencesDefaults(context.getSharedPreferences("defaults-test", Context.MODE_PRIVATE))

    @Test
    fun everyKindOfValueComesBackAsItWasWritten() {
        defaults.set("https://rc.example.com", forKey = "gateway.origin")
        defaults.set(true, forKey = "preference.appLock")
        defaults.set(14.5, forKey = "preference.terminalFontSize")
        defaults.set(listOf("b", "a"), forKey = "sessions.collapsed")
        assertEquals("https://rc.example.com", defaults.string(forKey = "gateway.origin"))
        assertTrue(defaults.bool(forKey = "preference.appLock"))
        assertEquals(14.5, defaults.double(forKey = "preference.terminalFontSize"), 0.0)
        assertEquals(setOf("a", "b"), defaults.stringArray(forKey = "sessions.collapsed")?.toSet())
        assertEquals(setOf("gateway.origin", "preference.appLock", "preference.terminalFontSize", "sessions.collapsed"), defaults.keys)
    }

    @Test
    fun nothingOrAnotherTypeReadsAsFoundationReadsIt() {
        assertNull(defaults.string(forKey = "missing"))
        assertFalse(defaults.bool(forKey = "missing"))
        assertEquals(0.0, defaults.double(forKey = "missing"), 0.0)
        assertNull(defaults.stringArray(forKey = "missing"))
        defaults.set("text", forKey = "k")
        assertFalse("a string is no boolean", defaults.bool(forKey = "k"))
        assertEquals(0.0, defaults.double(forKey = "k"), 0.0)
        assertNull(defaults.stringArray(forKey = "k"))
    }

    @Test
    fun aRemovedKeyHoldsNothing() {
        defaults.set("x", forKey = "k")
        defaults.removeObject(forKey = "k")
        assertNull(defaults.string(forKey = "k"))
        assertFalse("k" in defaults.keys)
    }
}
