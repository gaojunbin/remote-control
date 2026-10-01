package com.junbingao.remotecontrol.win.users

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junbingao.remotecontrol.win.app.LocalLayoutClass
import com.junbingao.remotecontrol.win.design.FontSize
import com.junbingao.remotecontrol.win.design.HStack
import com.junbingao.remotecontrol.win.design.Palette
import com.junbingao.remotecontrol.win.design.Space
import com.junbingao.remotecontrol.win.design.Switch
import com.junbingao.remotecontrol.win.design.Text
import com.junbingao.remotecontrol.win.design.VStack
import com.junbingao.remotecontrol.win.design.css
import com.junbingao.remotecontrol.win.design.surface
import com.junbingao.remotecontrol.win.strings.S

/**
 * `.users-registration`: the one switch at the top of Users, with its caption under it
 * (`docs/DESIGN.md` § "Accounts": off on a fresh gateway).
 */
@Composable
fun RegistrationCard(isOpen: Boolean, onChange: (Boolean) -> Unit) {
    val layout = LocalLayoutClass.current
    VStack(
        Modifier
            .fillMaxWidth()
            .surface()
            .padding(top = Space.sp3, bottom = Space.sp4)
            .padding(horizontal = if (layout.maxWidth640) Space.sp4 else Space.sp5),
        spacing = 0.dp,
        alignment = Alignment.Start,
    ) {
        HStack(Modifier.fillMaxWidth().heightIn(min = 40.dp), spacing = Space.sp4) {
            Text(S.users.registration, css(FontSize.fs14), Modifier.weight(1f))
            Switch(isOn = isOpen, label = S.users.registration, onChange = onChange)
        }
        Text(S.users.registrationCaption, css(FontSize.fs12, lineHeight = 1.5f), Modifier.padding(top = 2.dp), color = Palette.inkTertiary)
    }
}
