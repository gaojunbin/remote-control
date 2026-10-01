package com.junbingao.remotecontrol.android.security

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checks `ios/VerificationUI/main.swift` makes of `SceneRule`, over Android's states:
 * `RESUMED` is `.active`, `STARTED` is `.inactive`, `CREATED` is `.background`.
 */
class SceneRuleTest {
    private val active = Lifecycle.State.RESUMED
    private val inactive = Lifecycle.State.STARTED
    private val background = Lifecycle.State.CREATED

    @Test
    fun onlyTheBackgroundIsLeavingTheApp() {
        assertTrue(SceneRule.isBackground(background))
        assertFalse(SceneRule.isBackground(inactive))
        assertFalse(SceneRule.isBackground(active))
    }

    @Test
    fun theAppReadsTheStreamOnlyWhileItIsActive() {
        assertTrue(SceneRule.isForeground(active))
        assertFalse(SceneRule.isForeground(inactive))
        assertFalse(SceneRule.isForeground(background))
    }

    @Test
    fun theShieldCoversEverythingButTheActiveApp() {
        assertTrue(SceneRule.shields(inactive))
        assertTrue(SceneRule.shields(background))
        assertFalse(SceneRule.shields(active))
    }
}
