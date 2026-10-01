package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.transport.TransportError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The decoding cases of RCCore's suite — `userIdentity`, `unknownRole`, `helloWithoutUser`,
// `userRecord`, `operatorRecord`, `health`, `passwordLength` — are in `protocol/AccountsTests.kt`.
// The cases that drive the demo gateway — `demoSignIn`, `demoMemberIsRefused`, `demoRegistration` —
// arrive with the demo.

/**
 * Amendment A24: every person on a gateway has their own devices, sessions and settings. These are
 * the parts of that the app owns — what it decodes, what it says when it is refused, and whose
 * preferences it reads.
 */
class AccountsTests {
    /** A store sets the language every string is looked up in; the next suite reads English again. */
    @AfterTest
    fun backToEnglish() = L10n.use(InterfaceLanguage.en)

    // What each refusal says

    /** Signing in never says which half was wrong. */
    @Test
    fun signInWording() {
        for ((status, sentence) in listOf(401 to "Wrong username or password.", 403 to "This account is disabled.",
                                          400 to "Enter a username and a password.")) {
            assertEquals(sentence, AccountError.signIn(error(status)), "$status")
        }
    }

    /** Registering is refused by name, by rule or by the switch. */
    @Test
    fun registerWording() {
        for ((status, sentence) in listOf(409 to "That username is taken.", 403 to "Registration is closed.")) {
            assertEquals(sentence, AccountError.register(error(status)), "$status")
        }
    }

    /** A refused name is the one time the rule is stated. */
    @Test
    fun accountRules() {
        assertEquals(AccountError.rules, AccountError.register(error(400)))
        assertEquals(AccountError.rules, AccountError.create(error(400)))
        assertEquals(AccountError.rules, AccountError.manage(error(400)))
        assertTrue(AccountError.rules.contains("hyphens"))
        assertTrue(AccountError.rules.contains("8 characters or more"))
    }

    /** Only the caller's own password can be wrong. */
    @Test
    fun passwordWording() {
        assertEquals("That is not your current password.", AccountError.passwordChange(TransportError.Unauthorized))
        // The operator's password is the gateway's own, so the route refuses it. The row is never
        // offered to an admin; this is what a race says.
        assertEquals("This account cannot be changed.", AccountError.passwordChange(error(403)))
    }

    /** A conflict on an account that exists is only ever the operator's row. */
    @Test
    fun manageConflict() {
        assertEquals("This account cannot be changed.", AccountError.manage(error(409)))
        assertEquals("That username is taken.", AccountError.create(error(409)))
    }

    // Whose settings the app is reading

    /** Two people on one phone do not share the app's settings. */
    @Test
    fun settingsArePerAccount() {
        val defaults = suite()
        val first = SettingsStore(defaults = defaults)
        first.remember(origin = "https://rc.example.com", username = "alice")
        first.language = InterfaceLanguage.zhHans
        first.timelineDetail = TimelineDetail.detailed
        first.voiceLanguage = "ja"
        first.notificationsEnabled = true

        val second = SettingsStore(defaults = defaults)
        second.remember(origin = "https://rc.example.com", username = "bob")
        assertEquals(InterfaceLanguage.en, second.language)
        assertEquals(TimelineDetail.simple, second.timelineDetail)
        assertEquals("zh", second.voiceLanguage)
        assertFalse(second.notificationsEnabled)

        second.remember(origin = "https://rc.example.com", username = "alice")
        assertEquals(InterfaceLanguage.zhHans, second.language)
        assertEquals(TimelineDetail.detailed, second.timelineDetail)
    }

    /** The same username on another gateway is another person. */
    @Test
    fun settingsArePerGateway() {
        val defaults = suite()
        val store = SettingsStore(defaults = defaults)
        store.remember(origin = "https://one.example.com", username = "alice")
        store.timelineDetail = TimelineDetail.detailed
        store.remember(origin = "https://two.example.com", username = "alice")
        assertEquals(TimelineDetail.simple, store.timelineDetail)
    }

    /** The form is prefilled with the gateway, and with the account used there. */
    @Test
    fun prefill() {
        val defaults = suite()
        val store = SettingsStore(defaults = defaults)
        store.remember(origin = "https://one.example.com", username = "alice")
        store.remember(origin = "https://two.example.com", username = "bob")
        assertEquals("alice", store.username("https://one.example.com"))
        assertEquals("bob", store.username("https://two.example.com"))
        assertTrue(store.username("https://three.example.com").isEmpty())

        // The launch comes back to the gateway it left, with the account that signed in there — which
        // is the one the stored token is filed under.
        val relaunched = SettingsStore(defaults = defaults)
        assertEquals("https://two.example.com", relaunched.lastOrigin)
        assertEquals("bob", relaunched.lastUsername)
        // And it reads that account's preferences, so the first screen is already in their language.
        store.language = InterfaceLanguage.zhHans
        assertEquals(InterfaceLanguage.zhHans, SettingsStore(defaults = defaults).language)
    }

    /** A reset forgets every account, not only the last one. */
    @Test
    fun resetClearsEveryScope() {
        val defaults = suite()
        val store = SettingsStore(defaults = defaults)
        store.remember(origin = "https://rc.example.com", username = "alice")
        store.language = InterfaceLanguage.zhHans
        store.remember(origin = "https://rc.example.com", username = "bob")
        store.timelineDetail = TimelineDetail.detailed
        store.reset()
        assertTrue(store.lastOrigin.isEmpty())
        store.remember(origin = "https://rc.example.com", username = "alice")
        assertEquals(InterfaceLanguage.en, store.language)
        store.remember(origin = "https://rc.example.com", username = "bob")
        assertEquals(TimelineDetail.simple, store.timelineDetail)
    }

    /** A pinned language outlives whichever account signs in. */
    @Test
    fun pinnedLanguage() {
        val defaults = suite()
        val stored = SettingsStore(defaults = defaults)
        stored.remember(origin = "https://rc.example.com", username = "alice")
        stored.language = InterfaceLanguage.en

        val pinned = SettingsStore(defaults = defaults)
        pinned.pinLanguage(InterfaceLanguage.zhHans)
        pinned.remember(origin = "https://rc.example.com", username = "alice")
        assertEquals(InterfaceLanguage.zhHans, pinned.language)
    }

    private fun error(status: Int): TransportError =
        if (status == 401) TransportError.Unauthorized else TransportError.Http(status = status, code = null)

    private fun suite(): UserDefaults = MemoryUserDefaults()
}
