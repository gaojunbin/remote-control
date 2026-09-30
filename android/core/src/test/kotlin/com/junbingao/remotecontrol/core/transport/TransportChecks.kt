package com.junbingao.remotecontrol.core.transport

import com.junbingao.remotecontrol.core.CheckRunner
import kotlin.test.Test

/** `ios/Verification/TransportChecks.swift`: the rules an address is read by. */
class TransportChecks {
    @Test
    fun canonicalisation() {
        val checks = CheckRunner("transport")
        checks.noThrow("an https origin canonicalises") {
            val endpoint = GatewayEndpoint("HTTPS://RC.Example.com:443/")
            if (endpoint.origin != "https://rc.example.com" || !endpoint.isSecure) throw TransportError.InvalidEndpoint
        }
        checks.noThrow("a bare host defaults to https") {
            if (GatewayEndpoint("rc.example.com").origin != "https://rc.example.com") throw TransportError.InvalidEndpoint
        }
        checks.noThrow("a non-default port is kept") {
            if (GatewayEndpoint("https://rc.example.com:8443").origin != "https://rc.example.com:8443") {
                throw TransportError.InvalidEndpoint
            }
        }
        checks.assertAll()
    }

    /** Plain http is allowed only where a developer's own gateway can live. */
    @Test
    fun development() {
        val checks = CheckRunner("transport")
        for (host in listOf("http://127.0.0.1:8787", "http://localhost:8787", "http://192.168.1.20:8787",
                            "http://10.0.0.5:8787", "http://172.20.0.4:8787", "http://mac-studio.local:8787")) {
            checks.noThrow("$host is accepted for development") {
                if (GatewayEndpoint(host).isSecure) throw TransportError.InvalidEndpoint
            }
        }
        for (host in listOf("http://rc.example.com", "http://8.8.8.8", "http://172.32.0.1")) {
            checks.throwsError("$host is refused over plain http") { GatewayEndpoint(host) }
        }
        checks.expect(GatewayEndpoint.isDevelopmentHost("127.0.0.1"), "loopback is a development host")
        checks.expect(!GatewayEndpoint.isDevelopmentHost("172.15.0.1"), "172.15 is not private")
        checks.expect(GatewayEndpoint.isDevelopmentHost("172.31.255.255"), "172.31 is private")
        checks.assertAll()
    }

    @Test
    fun rejections() {
        val checks = CheckRunner("transport")
        for (bad in listOf("https://user:pass@rc.example.com", "https://rc.example.com/path",
                           "https://rc.example.com?token=abc", "https://rc.example.com#frag",
                           "ftp://rc.example.com", "", "   ", "https://")) {
            checks.throwsError("${bad.ifEmpty { "<empty>" }} is refused") { GatewayEndpoint(bad) }
        }
        checks.assertAll()
    }

    @Test
    fun socketURLs() {
        val checks = CheckRunner("transport")
        checks.noThrow("an https origin upgrades to wss") {
            val endpoint = GatewayEndpoint("https://rc.example.com")
            if (endpoint.socketURL(path = "/ws/app").toString() != "wss://rc.example.com/ws/app" ||
                endpoint.socketURL(path = "/ws/stt").toString() != "wss://rc.example.com/ws/stt") {
                throw TransportError.InvalidEndpoint
            }
        }
        checks.noThrow("a development origin upgrades to ws") {
            val endpoint = GatewayEndpoint("http://127.0.0.1:8787")
            if (endpoint.socketURL(path = "/ws/app").toString() != "ws://127.0.0.1:8787/ws/app" ||
                endpoint.apiURL("/api/health").toString() != "http://127.0.0.1:8787/api/health") {
                throw TransportError.InvalidEndpoint
            }
        }
        checks.assertAll()
    }

    /**
     * Beyond RCCore's checks: the address rules the Kotlin core reimplements rather than inherits
     * from `URLComponents`, each case the answer Foundation gives on macOS 27 (round 56).
     */
    @Test
    fun urlComponentsParity() {
        val checks = CheckRunner("transport")
        val accepted = listOf(
            "https://[::1]:8080" to "https://[::1]:8080",
            "http://[::1]:8787" to "http://[::1]:8787",
            "http://[::1]:80" to "http://[::1]",
            "https://[::1]:443" to "https://[::1]",
            "https://my_host.com" to "https://my_host.com",
            "https://rc.example.com/" to "https://rc.example.com",
            "https://rc.example.com:" to "https://rc.example.com",
            "http://LOCALHOST:80" to "http://localhost",
            "https://rc.example.com." to "https://rc.example.com.",
            "https://ünicode.com" to "https://xn--nicode-2ya.com",
            "  rc.example.com  " to "https://rc.example.com",
            "rc.example.com:8443" to "https://rc.example.com:8443",
            "https://rc.example.com:08443" to "https://rc.example.com:8443",
            "https://%72c.example.com" to "https://rc.example.com",
            "https://rc.example.com\n" to "https://rc.example.com",
            "\thttps://rc.example.com" to "https://rc.example.com",
            "https://rc.example.com:65535" to "https://rc.example.com:65535",
            "https://*.example.com" to "https://*.example.com",
            "https://rc.example.com:80" to "https://rc.example.com:80",
            "http://foo.localhost:3000" to "http://foo.localhost:3000",
            "http://127.0.0.1:80/" to "http://127.0.0.1",
        )
        for ((input, origin) in accepted) {
            checks.equal(runCatching { GatewayEndpoint(input).origin }.getOrNull(), origin, "${input.trim()} reads as $origin")
        }
        val refused = listOf(
            "https://rc.example.com:0", "https://rc.example.com:99999", "https://rc example.com",
            "https://rc.example.com//", "https://user@rc.example.com", "https://rc.example.com?",
            "https://rc.example.com#", "http://172.15.0.1", "http://0.0.0.0:1", "http://127.1",
            "http://10.0.0.256", "https://rc.example.com:+443", "wss://rc.example.com", "https:rc.example.com",
            "https:/rc.example.com", "//rc.example.com", "rc.example.com/path", "https://rc.example.com/%20",
            "https://rc.exa%20mple.com", "https://rc.example.com:65536", "https://a:b", "https://@rc.example.com",
            "https://:@rc.example.com", "rc.example.com?x",
        )
        for (input in refused) checks.throwsError("$input is refused") { GatewayEndpoint(input) }
        checks.assertAll()
    }
}
