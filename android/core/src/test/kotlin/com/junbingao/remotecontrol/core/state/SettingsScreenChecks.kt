package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.protocol.GatewayErrorBody
import com.junbingao.remotecontrol.core.protocol.GatewayErrorCode
import com.junbingao.remotecontrol.core.protocol.RemoteProtocol
import com.junbingao.remotecontrol.core.protocol.UserIdentity
import com.junbingao.remotecontrol.core.protocol.UserRole
import kotlin.test.Test

/**
 * The lines of `ios/VerificationUI/main.swift` that read the core's own types rather than a screen:
 * § "The Settings screen" (`docs/DESIGN.md`, owner's ruling, 2026-09-18) — the initials, the host,
 * the dot and its word, the header's own line and the versions — and what the directory picker says
 * when a device refuses a folder (A37), here from the refusals themselves rather than from the demo
 * gateway's.
 */
class SettingsScreenChecks {
    @Test
    fun header() {
        val checks = CheckRunner("settings")
        checks.equal(Initials.of("admin"), "AD", "one word gives its first two letters")
        checks.equal(Initials.of("j.gao"), "JG", "two words give a letter each")
        checks.equal(Initials.of("ci-runner"), "CR", "whatever the separator is")
        checks.equal(Initials.of("李雷"), "李", "and a script whose characters are words of their own gives one")
        checks.equal(Initials.of("x"), "X", "a one-letter name is one letter")
        checks.equal(Initials.of(""), "", "and an account with no name draws nothing")

        checks.equal(GatewayHost.of("https://rc.example.com"), "rc.example.com", "the host without its scheme")
        checks.equal(GatewayHost.of("http://192.168.1.4:8787"), "192.168.1.4:8787",
                     "with the port where the gateway is not on the usual one")
        checks.equal(GatewayHost.of("https://rc.example.com/"), "rc.example.com", "and no trailing slash")

        checks.equal(ConnectionTone.dot(ConnectionPhase.Connected), DotTone.working, "a live connection is green")
        checks.equal(ConnectionTone.dot(ConnectionPhase.Connecting), DotTone.waiting, "one being made pulses amber")
        checks.equal(ConnectionTone.dot(ConnectionPhase.Syncing), DotTone.waiting, "so does one still catching up")
        checks.equal(ConnectionTone.dot(ConnectionPhase.Reconnecting), DotTone.waiting, "and one coming back")
        checks.equal(ConnectionTone.dot(ConnectionPhase.SignedOut), DotTone.off, "a link that is down is grey")
        checks.equal(ConnectionTone.dot(ConnectionPhase.Forbidden), DotTone.failed, "a gateway that refused it is red")
        checks.equal(ConnectionTone.dot(ConnectionPhase.Incompatible(gatewayVersion = 2)), DotTone.failed,
                     "and so is one that will not speak to this build")
        checks.equal(ConnectionTone.word(ConnectionPhase.Connected), L10n.string("Connected"), "the dot's word is Connected")
        checks.equal(ConnectionTone.word(ConnectionPhase.Reconnecting), L10n.string("Connecting"), "Connecting while it is made")
        checks.equal(ConnectionTone.word(ConnectionPhase.SignedOut), L10n.string("Offline"), "Offline while there is none")
        checks.equal(ConnectionTone.word(ConnectionPhase.Expired), L10n.string("Refused"), "and Refused when the gateway said no")

        checks.equal(IdentityLine.label(user = UserIdentity(username = "admin", role = UserRole.admin), host = "Demo",
                                        phase = ConnectionPhase.Connected),
                     "admin, Admin, Demo, Connected",
                     "the header reads who is signed in, as what, where, and how the link is")

        val versionsLine = VersionsLine.text(app = AppBuild.version, gateway = "1.5.0", protocolVersion = RemoteProtocol.version)
        checks.expect(versionsLine.contains(AppBuild.shipped), "the versions line names this build")
        checks.expect(versionsLine.contains("Gateway 1.5.0"), "and the gateway it is talking to")
        checks.expect(versionsLine.endsWith("v${RemoteProtocol.version}"), "and ends on the protocol they both speak")
        checks.expect(!VersionsLine.text(app = AppBuild.version, gateway = "", protocolVersion = 1).contains("Gateway"),
                      "a gateway that has not said its version yet leaves its half out")
        checks.assertAll()
    }

    @Test
    fun directoryErrors() {
        val checks = CheckRunner("settings")
        val clash = GatewayErrorBody(code = GatewayErrorCode.conflict, message = "/Users/me/dev/round-41: File exists")
        checks.equal(DirectoryError.makeFolder(clash), "A folder with that name already exists.",
                     "a clash is said in the app's own words rather than repeating a path")
        val badName = GatewayErrorBody(code = GatewayErrorCode.badRequest, message = "A folder name cannot start with a dot.")
        checks.equal(DirectoryError.makeFolder(badName), badName.message,
                     "and any other refusal is the device's own sentence")
        checks.assertAll()
    }
}
