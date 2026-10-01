package com.junbingao.remotecontrol.win.platform

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The core's `UserDefaults` on a file: Foundation's answers, and what a second launch reads back. */
class FileUserDefaultsTests {
    @Test
    fun aValueOutlivesTheLaunchThatWroteIt(@TempDir directory: Path) {
        val file = directory.resolve("Remote Control").resolve("defaults.json")
        val first = FileUserDefaults(file)
        first.set("https://rc.example.com", forKey = "gateway.origin")
        first.set(true, forKey = "preference.notifications")
        first.set(15.0, forKey = "preference.terminalFontSize")
        first.set(listOf("a", "b"), forKey = "sessions.collapsed")
        val second = FileUserDefaults(file)
        assertEquals("https://rc.example.com", second.string(forKey = "gateway.origin"))
        assertEquals(true, second.bool(forKey = "preference.notifications"))
        assertEquals(15.0, second.double(forKey = "preference.terminalFontSize"))
        assertEquals(listOf("a", "b"), second.stringArray(forKey = "sessions.collapsed"))
        assertEquals(setOf("gateway.origin", "preference.notifications", "preference.terminalFontSize", "sessions.collapsed"), second.keys)
        second.removeObject(forKey = "gateway.origin")
        assertNull(FileUserDefaults(file).string(forKey = "gateway.origin"))
    }

    @Test
    fun aKeyThatHoldsNothingOrAnotherTypeReadsAsFoundationReadsIt(@TempDir directory: Path) {
        val defaults = FileUserDefaults(directory.resolve("defaults.json"))
        defaults.set("yes", forKey = "text")
        assertNull(defaults.string(forKey = "missing"))
        assertFalse(defaults.bool(forKey = "text"))
        assertEquals(0.0, defaults.double(forKey = "text"))
        assertNull(defaults.stringArray(forKey = "text"))
    }

    @Test
    fun aFileThatCannotBeReadIsAFreshInstalls(@TempDir directory: Path) {
        val file = directory.resolve("defaults.json")
        Files.writeString(file, "not json")
        val defaults = FileUserDefaults(file)
        assertEquals(emptySet(), defaults.keys)
        defaults.set("x", forKey = "k")
        assertEquals("x", FileUserDefaults(file).string(forKey = "k"))
    }
}
