package com.junbingao.remotecontrol.win.devices

import com.junbingao.remotecontrol.core.protocol.AccountMethod
import com.junbingao.remotecontrol.core.protocol.AgentAccount
import com.junbingao.remotecontrol.core.protocol.AgentLimit
import com.junbingao.remotecontrol.core.state.InterfaceLanguage
import com.junbingao.remotecontrol.win.devices.page.AccountWords
import com.junbingao.remotecontrol.win.strings.InterfaceLanguageSource
import com.junbingao.remotecontrol.win.strings.S
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Mac's "Lists: account words" — `web/tests/accounts.test.ts`: the words a device page puts on
 * an account and its windows — the vendor from a table of three ids, everything else the device
 * reported printed as it arrived, and the meter's colour band.
 */
class ListsAccountWordsTests {
    @BeforeEach
    @AfterEach
    fun english() {
        InterfaceLanguageSource.current = InterfaceLanguage.en
    }

    private fun account(provider: String, method: AccountMethod, plan: String? = null, tier: String? = null,
                        email: String? = null, endpoint: String? = null) =
        AgentAccount(provider = provider, method = method, plan = plan, tier = tier, email = email, endpoint = endpoint)

    private fun limit(minutes: Int, scope: String? = null, used: Double = 0.0) =
        AgentLimit(windowMinutes = minutes, scope = scope, usedPercent = used)

    /** Local wall-clock times, as `new Date(2026, 8, 15, …)` makes them. */
    private fun millis(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun namesTheThreeVendorsTheAppsKnowAndPrintsAnyOtherAsItself() {
        assertEquals("Anthropic", S.vendorLabel("anthropic"))
        assertEquals("OpenAI", S.vendorLabel("openai"))
        assertEquals("xAI", S.vendorLabel("xai"))
        assertEquals("mistral", S.vendorLabel("mistral"))
    }

    @Test
    fun readsVendorPlanTierAndEmailForAnAccount() {
        assertEquals(
            "Anthropic account · Max · Max 5x · me@example.com",
            AccountWords.signInLine(account("anthropic", AccountMethod.account, plan = "max", tier = "Max 5x", email = "me@example.com")),
        )
    }

    @Test
    fun raisesThePlansFirstLetterAndLeavesTheTierExactlyAsReported() {
        assertEquals(
            "OpenAI account · Pro · gpt-5.4 priority",
            AccountWords.signInLine(account("openai", AccountMethod.account, plan = "pro", tier = "gpt-5.4 priority")),
        )
    }

    @Test
    fun drawsNothingForWhatTheDeviceDidNotReport() {
        assertEquals("xAI account", AccountWords.signInLine(account("xai", AccountMethod.account)))
    }

    @Test
    fun leadsAKeyWithTheVendorAndNamesAThirdPartyHost() {
        assertEquals("Anthropic API key", AccountWords.signInLine(account("anthropic", AccountMethod.apiKey)))
        assertEquals("OpenAI API key · api.relay.example", AccountWords.signInLine(account("openai", AccountMethod.apiKey, endpoint = "api.relay.example")))
        assertEquals("mistral API key", AccountWords.signInLine(account("mistral", AccountMethod.apiKey)))
    }

    @Test
    fun keepsThePlanTheTierAndTheEmailUntranslatedInChinese() {
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        assertEquals("Anthropic 账户 · Max · me@x.io", AccountWords.signInLine(account("anthropic", AccountMethod.account, plan = "max", email = "me@x.io")))
    }

    @Test
    fun namesAWindowByItsLength() {
        val cases = listOf(300 to "5-hour", 1440 to "24-hour", 10080 to "7-day", 60 to "1-hour", 20160 to "14-day", 90 to "90-minute")
        for ((minutes, expected) in cases) assertEquals(expected, AccountWords.windowName(limit(minutes)), "$minutes minutes")
    }

    @Test
    fun putsWhatTheWindowIsConfinedToAfterIt() {
        assertEquals("7-day · Fable", AccountWords.windowName(limit(10080, scope = "Fable", used = 64.0)))
    }

    @Test
    fun namesTheSameWindowInChinese() {
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        assertEquals("5 小时", AccountWords.windowName(limit(300)))
    }

    @Test
    fun coloursTheMeterInkThenWarningPast80ThenDangerAt100() {
        assertEquals(AccountWords.MeterTone.ink, AccountWords.meterTone(0.0))
        assertEquals(AccountWords.MeterTone.ink, AccountWords.meterTone(80.0))
        assertEquals(AccountWords.MeterTone.warn, AccountWords.meterTone(80.5))
        assertEquals(AccountWords.MeterTone.warn, AccountWords.meterTone(99.0))
        assertEquals(AccountWords.MeterTone.danger, AccountWords.meterTone(100.0))
    }

    @Test
    fun drawsAWholePercentageInsideTheTrack() {
        assertEquals(16, AccountWords.usedPercent(limit(300, used = 16.4)))
        assertEquals(17, AccountWords.usedPercent(limit(300, used = 16.5)))
        assertEquals(100, AccountWords.usedPercent(limit(300, used = 120.0)))
        assertEquals(0, AccountWords.usedPercent(limit(300, used = -1.0)))
    }

    @Test
    fun givesTheClockAloneForAResetLaterTodayAndTheDayOtherwise() {
        val now = millis(15, 9, 0)
        assertEquals("resets 15:40", AccountWords.resetsText(millis(15, 15, 40), now = now))
        assertEquals("resets Tue 22:00", AccountWords.resetsText(millis(22, 22, 0), now = now))
    }

    @Test
    fun saysWhenItResetsInChineseWithTheSameClock() {
        InterfaceLanguageSource.current = InterfaceLanguage.zhHans
        assertEquals("15:40 重置", AccountWords.resetsText(millis(15, 15, 40), now = millis(15, 9, 0)))
        assertEquals("检查中…", S.devicePage.checking)
    }
}
