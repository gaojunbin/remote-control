package com.junbingao.remotecontrol.android.screens.shell

import org.junit.Assume.assumeFalse
import java.io.File

/**
 * The screens a ported test walks through that are another feature's port — the composer
 * (`android-chat`), the sessions list's own controls (`android-lists`) — while this tree still
 * holds the foundation's placeholder for them. Each test runs its own steps first; a step on such a
 * screen then stops the test as skipped, saying why, until the feature's port replaces the
 * placeholder. From then on the step runs and asserts exactly as the iPhone's does.
 */
object ForeignScreen {
    /** [file] is the screen's source under `screens/`, as the placeholder the foundation left names its owner. */
    fun requires(feature: String, file: String) {
        val source = File("src/main/kotlin/com/junbingao/remotecontrol/android/screens/$file").readText()
        assumeFalse("$feature's port of screens/$file is not in this tree yet", source.contains("Placeholder for `$feature`"))
    }

    /** The conversation and its composer. */
    fun requiresTheComposer() = requires("android-chat", "chat/ChatView.kt")

    /** The sessions list and its bottom bar. */
    fun requiresTheSessionsList() = requires("android-lists", "sessions/SessionsView.kt")
}
