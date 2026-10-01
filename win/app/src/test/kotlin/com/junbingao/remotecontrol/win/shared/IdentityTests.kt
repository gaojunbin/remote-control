package com.junbingao.remotecontrol.win.shared

import kotlin.test.Test
import kotlin.test.assertEquals

/** `web/tests/identity.test.ts`, as the Mac app checks its port. */
class IdentityTests {
    @Test
    fun initialsAsTheTopbarAndSettingsDrawThem() {
        assertEquals("AD", Identity.initials("admin"))
        assertEquals("JG", Identity.initials("j.gao"))
        assertEquals("李", Identity.initials("李雷"))
        assertEquals("?", Identity.initials(""))
        assertEquals("é", Identity.initials("élodie"))
        assertEquals("AB", Identity.initials("anna_bell"))
        assertEquals("?", Identity.initials("--"))
    }

    @Test
    fun theGatewayHostDropsTheSchemeAndThePath() {
        assertEquals("rc.example.com", Identity.gatewayHost("https://rc.example.com"))
        assertEquals("localhost:8787", Identity.gatewayHost("http://localhost:8787/"))
        assertEquals("rc.example.com:8443", Identity.gatewayHost("rc.example.com:8443"))
    }
}
