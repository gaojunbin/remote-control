package com.junbingao.remotecontrol.android.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the field's scroll probe reports to a UI test: "<offset>/<end>" in whole points. */
class FieldScrollProbeTest {
    @Test
    fun theReportIsTheOffsetAndTheEndInWholePoints() {
        assertEquals("0/0", FieldScrollProbe.report(0f, 0f))
        assertEquals("42/42", FieldScrollProbe.report(41.6f, 42.4f))
        assertEquals("a field with nothing to scroll has no end below zero", "0/0", FieldScrollProbe.report(0f, -12f))
    }
}
