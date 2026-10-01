package com.junbingao.remotecontrol.android.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lines of `ios/VerificationUI/main.swift` § "The Settings screen" that read the screen's own
 * source rather than run it (owner's ruling, 2026-09-18): one file per group, the captions the
 * ruling names in the order it names them, and none of the groups it took away. The pure parts —
 * the initials, the host, the dot and its word, the versions line — are the core's checks.
 */
class SettingsScreenSourceTest {
    private val root = File("src/main/kotlin/com/junbingao/remotecontrol/android/screens/settings")

    private fun source(name: String): String = root.resolve(name).takeIf { it.isFile }?.readText().orEmpty()

    private val files: List<File> get() = root.listFiles { file -> file.extension == "kt" }.orEmpty().toList()

    @Test
    fun theScreenIsAFilePerGroup() {
        assertTrue("the screen is a file per group, not one of 380 lines", files.size >= 9)
        for ((file, caption) in listOf(
            "SettingsAccountGroup.kt" to "Account",
            "SettingsAwayGroup.kt" to "While you're away",
            "SettingsVoiceGroup.kt" to "Voice",
            "SettingsReadingGroup.kt" to "Reading",
            "SettingsSecurityGroup.kt" to "Security",
        )) {
            assertTrue("the group in $file is headed $caption", source(file).contains("SettingsGroup(\"$caption\")"))
        }
    }

    @Test
    fun theGroupsAreDrawnInTheRulingsOrder() {
        val screen = source("SettingsView.kt")
        var cursor = 0
        var inOrder = screen.isNotEmpty()
        for (group in listOf(
            "SettingsIdentityHeader(", "SettingsAccountGroup(", "SettingsAwayGroup(", "SettingsVoiceGroup(",
            "SettingsReadingGroup(", "SettingsSecurityGroup(", "SettingsVersionsRow(",
        )) {
            val found = screen.indexOf(group, cursor)
            if (found < 0) {
                inOrder = false
                break
            }
            cursor = found + group.length
        }
        assertTrue("the header, the five groups and the versions line are drawn in that order", inOrder)
    }

    @Test
    fun noGroupTheRulingTookAwayIsLeft() {
        val sources = files.joinToString("\n") { it.readText() }
        for (gone in listOf("About", "Notifications", "Timeline", "App lock", "Sessions", "Language")) {
            assertFalse("no group is headed $gone any more", sources.contains("FieldLabel(\"$gone\")"))
        }
        assertFalse("and no sentence is left under a group: a state speaks in its row", sources.contains("SettingsFooter("))
    }
}
