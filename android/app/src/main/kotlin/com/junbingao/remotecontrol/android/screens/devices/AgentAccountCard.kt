package com.junbingao.remotecontrol.android.screens.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.android.design.AgentLogo
import com.junbingao.remotecontrol.android.design.CapsuleShape
import com.junbingao.remotecontrol.android.design.CodeText
import com.junbingao.remotecontrol.android.design.Text
import com.junbingao.remotecontrol.android.design.Theme
import com.junbingao.remotecontrol.android.design.card
import com.junbingao.remotecontrol.android.design.monospacedDigit
import com.junbingao.remotecontrol.android.shell.LocalAppModel
import com.junbingao.remotecontrol.android.strings.L10n
import com.junbingao.remotecontrol.core.protocol.AccountMethod
import com.junbingao.remotecontrol.core.protocol.AgentAccount
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.core.protocol.AgentLimit
import com.junbingao.remotecontrol.core.state.AccountLine
import com.junbingao.remotecontrol.core.state.QuotaWindow
import java.util.Locale
import kotlin.math.max

/**
 * Amendment A33: how far the page has got with the vendors' rate limits.
 *
 * The credentials themselves come from the device list and are on screen at once; the windows are
 * a question asked the moment the page opens, and this is the answer's progress.
 */
enum class QuotaPhase {
    /** The reply to `device.agents` has not arrived yet. */
    checking,

    /** It arrived: each account carries its own windows, its own reason, or neither. */
    ready,

    /** The machine is not there to ask. */
    offline,

    /** Nothing can be said about the windows, and the page says why elsewhere. */
    unavailable,
}

/**
 * One coding agent on a device's page: the logo and the name with its version, how it is signed
 * in, and what is left of each account's quota.
 *
 * `docs/DESIGN.md` § "A device has a page" and § "Quota is a meter, drawn for accounts only".
 * Nothing is drawn for what the device did not report: an agent whose `accounts` is absent says
 * nothing about signing in, and an account whose vendor exposes no windows shows its line alone.
 *
 * [accounts]: the credentials to draw — the fresh ones where a reply has brought them, the stored
 * ones until then, null where the device did not look.
 */
@Composable
fun AgentAccountCard(info: AgentInfo, accounts: List<AgentAccount>?, phase: QuotaPhase) {
    // The reader's own clock words, in the interface language, as the iPhone's `\.locale` gives them.
    val locale = LocalAppModel.current.settings.language.locale
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight), verticalAlignment = Alignment.CenterVertically) {
            AgentLogo(info.agent)
            // On the name rather than on the card: the lines under this one have identifiers of their own.
            Text(info.displayName, Modifier.weight(1f).testTag("device.agent.${info.agent}"), style = Theme.Text.title, color = Theme.ink)
            info.version?.takeIf { it.isNotEmpty() }?.let { CodeText(it, font = Theme.Text.metaMono) }
        }
        if (accounts != null) {
            if (accounts.isEmpty()) {
                Text(AccountLine.notSignedIn, Modifier.testTag("device.agent.${info.agent}.signIn"), style = Theme.Text.meta, color = Theme.inkSecondary)
            } else {
                // pi signs in per provider, so a card can carry more than one line; two credentials
                // can otherwise look alike, so the position is what tells them apart.
                for (account in accounts) Credential(info, account, phase, locale)
            }
        }
    }
}

@Composable
private fun Credential(info: AgentInfo, account: AgentAccount, phase: QuotaPhase, locale: Locale) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
        Text(AccountLine.text(account), Modifier.testTag("device.agent.${info.agent}.signIn"), style = Theme.Text.meta, color = Theme.ink)
        Quota(account, phase, locale)
    }
}

/** A key has no plan window to measure, so it never has a meter — not even a waiting one. */
@Composable
private fun Quota(account: AgentAccount, phase: QuotaPhase, locale: Locale) {
    if (account.method == AccountMethod.apiKey) return
    when (phase) {
        QuotaPhase.checking -> Note(L10n.string("Checking…"), "device.quota.checking")
        QuotaPhase.offline -> Note(L10n.string("Offline · quota unavailable"), "device.quota.offline")
        QuotaPhase.unavailable -> Unit
        QuotaPhase.ready -> {
            val reason = account.limitsError
            val limits = account.limits
            if (!reason.isNullOrEmpty()) {
                Note(reason, "device.quota.error")
            } else if (!limits.isNullOrEmpty()) {
                Column(Modifier.testTag("device.quota.meters"), verticalArrangement = Arrangement.spacedBy(Theme.Space.small)) {
                    for (limit in limits) QuotaMeterRow(limit, locale)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, tag: String) {
    Text(text, Modifier.testTag(tag), style = Theme.Text.caption, color = Theme.inkSecondary)
}

/** One rate-limit window: its name, the share used, and when it comes back. One element for assistive technology. */
@Composable
fun QuotaMeterRow(limit: AgentLimit, locale: Locale) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Space.tight)) {
            Text(QuotaWindow.name(limit), Modifier.weight(1f).alignByBaseline(), style = Theme.Text.caption, color = Theme.ink)
            Text(
                QuotaWindow.percentage(limit.usedPercent),
                Modifier.alignByBaseline(),
                style = Theme.Text.caption.monospacedDigit(),
                color = Theme.inkSecondary,
            )
        }
        QuotaMeter(limit)
        limit.resetsAt?.let { resetsAt ->
            Text(QuotaWindow.resets(at = resetsAt, locale = locale), style = Theme.Text.caption, color = Theme.inkSecondary)
        }
    }
}

/**
 * The bar itself: the ink colour until the window is nearly spent, the warning colour past 80 % and
 * the danger colour at 100 %. No other colour appears on the page.
 */
@Composable
fun QuotaMeter(limit: AgentLimit) {
    val colour = when (QuotaWindow.band(limit.usedPercent)) {
        QuotaWindow.Band.normal -> Theme.ink
        QuotaWindow.Band.warning -> Theme.attention
        QuotaWindow.Band.danger -> Theme.danger
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(MeterHeight)
            .clearAndSetSemantics {}
            .background(Theme.quietFill, CapsuleShape),
    ) {
        val fill = QuotaWindow.fill(limit.usedPercent).toFloat()
        // A window barely touched is still a mark rather than nothing: a bar that draws no fill at
        // all reads as a missing meter.
        if (fill > 0f) {
            Box(Modifier.width(max(MeterHeight.value, maxWidth.value * fill).dp).height(MeterHeight).background(colour, CapsuleShape))
        }
    }
}

private val MeterHeight = 5.dp
