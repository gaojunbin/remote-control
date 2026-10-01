package com.junbingao.remotecontrol.win.devices.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.core.protocol.AccountMethod
import com.junbingao.remotecontrol.core.protocol.AgentAccount
import com.junbingao.remotecontrol.core.protocol.AgentInfo
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.design.AgentLogo
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.card
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.strings.S

/**
 * `AgentCard.tsx`: one agent on a device page — its logo, its name and version, how it is signed
 * in, and the quota windows of every account.
 *
 * Nothing is drawn for what the agent does not report. An agent whose `accounts` is absent came
 * from a device that never looked, and says nothing at all; an empty list is an agent installed and
 * signed in nowhere. A key has no meter, because a key has no plan window to measure.
 */
@Composable
internal fun AgentCard(
    agent: AgentInfo,
    /** The accounts to draw: the fresh reply's when it has arrived, else the stored ones. */
    accounts: List<AgentAccount>?,
    quota: DeviceQuota,
) {
    val horizontal = if (LocalLayoutClass.current.maxWidth480) Space.sp4 else Space.sp5
    VStack(Modifier.fillMaxWidth().card().padding(vertical = Space.sp4, horizontal = horizontal), spacing = 0.dp, alignment = Alignment.Start) {
        HStack(spacing = Space.sp2) {
            AgentLogo(agent.agent, size = FontSize.fs15)
            Text(S.agentLabel(agent.agent), css(FontSize.fs15, weight = FontWeight.SemiBold, tracking = -0.01f))
            val version = agent.version
            if (!version.isNullOrEmpty()) Text(version, css(FontSize.fs12, mono = true), color = Palette.inkTertiary)
        }
        if (accounts != null) {
            if (accounts.isEmpty()) {
                AccountNote(S.devicePage.notSignedIn, Palette.inkSecondary)
            } else {
                VStack(Modifier.fillMaxWidth(), spacing = Space.sp4, alignment = Alignment.Start) {
                    accounts.forEachIndexed { index, account -> AccountBlock(account, quota, parted = index > 0) }
                }
            }
        }
    }
}

/**
 * One account: its sign-in line, then the meters or the one line that stands where they would have
 * been. pi signs in per provider, so an agent can carry more than one, parted by a hairline.
 */
@Composable
private fun AccountBlock(account: AgentAccount, quota: DeviceQuota, parted: Boolean) {
    VStack(Modifier.fillMaxWidth(), spacing = 0.dp, alignment = Alignment.Start) {
        if (parted) Box(Modifier.padding(bottom = Space.sp4).fillMaxWidth().height(1.dp).background(Palette.hairline))
        AccountNote(AccountWords.signInLine(account), Palette.inkSecondary)
        QuotaLine(account, quota)
    }
}

@Composable
private fun QuotaLine(account: AgentAccount, quota: DeviceQuota) {
    val failure = account.limitsError
    val limits = account.limits
    when {
        account.method == AccountMethod.apiKey -> {}
        quota.status == DeviceQuota.Status.checking -> AccountNote(S.devicePage.checking, Palette.inkTertiary)
        quota.status == DeviceQuota.Status.offline -> AccountNote(S.devicePage.offlineQuota, Palette.inkTertiary)
        quota.status == DeviceQuota.Status.failed -> AccountNote(quota.error ?: "", Palette.inkTertiary)
        !failure.isNullOrEmpty() -> AccountNote(failure, Palette.inkTertiary)
        !limits.isNullOrEmpty() -> {
            VStack(Modifier.padding(top = Space.sp3).widthIn(max = 520.dp).fillMaxWidth(), spacing = Space.sp3, alignment = Alignment.Start) {
                for (limit in limits) QuotaMeter(limit)
            }
        }
    }
}

/**
 * `.device-page-signin` and `.device-page-note`: 13 px on 1.5 lines, 8 under what comes before,
 * breaking long words rather than overflowing.
 */
@Composable
private fun AccountNote(text: String, ink: Color) {
    Text(text, css(FontSize.fs13, lineHeight = 1.5f), Modifier.padding(top = Space.sp2), color = ink)
}
