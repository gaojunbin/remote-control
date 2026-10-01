package com.junbingao.remotecontrol.android.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamePayloadTest {
    @Test
    fun aCodeStillInViewIsHandedOnOnce() {
        val dedupe = SamePayload()
        assertTrue(dedupe.isNew("https://gw.example/pair#ABC"))
        assertFalse(dedupe.isNew("https://gw.example/pair#ABC"))
    }

    @Test
    fun andAgainOnlyAfterADifferentOneWasSeen() {
        val dedupe = SamePayload()
        dedupe.isNew("one")
        assertTrue(dedupe.isNew("two"))
        assertTrue(dedupe.isNew("one"))
    }
}
